//! End-to-end orchestration: load the bundled data set, compute SA (SBM +
//! DRC + RRAO), the IMA sketch (ES/IMCC, backtesting, PLAT, SES) and the
//! independent validation results for every desk and for the firm.
//!
//! Fully deterministic: pure revaluation and closed-form statistics, no
//! RNG.

use std::collections::BTreeMap;
use std::path::Path;

use serde_json::Value;

use crate::error::{invalid, FrtbError, Result};
use crate::ima::{backtest, es_base_10d, es_lh_scaled, ima_capital, imcc, ses, DeskIma, NmrfEntry};
use crate::instruments::{load_portfolio, Desk, Instrument};
use crate::market::{load_market, Market};
use crate::params::{load_params, SbmParams};
use crate::plat::{plat_surcharge, plat_test};
use crate::sa::{
    drc_charge, drc_positions_from_instruments, rrao_charge, sbm_capital, SbmResult,
};
use crate::sensitivities::{compute_sensitivities, Sensitivities};
use crate::validation::{
    benchmark_max_diff, classify_findings, data_quality, overall_verdict, render_report,
    sensitivity_max_diff, DeskCheckInputs, ValidationSummary,
};

/// P&L table: (column name, series) pairs in file column order (the order
/// matters — category sums replicate the reference's dict iteration).
pub type PnlTable = Vec<(String, Vec<f64>)>;

/// Load a P&L CSV (`date` + numeric columns) into a [`PnlTable`].
///
/// Empty cells become NaN (picked up by the data-quality check).
pub fn load_pnl_csv(path: &Path) -> Result<PnlTable> {
    let text = std::fs::read_to_string(path)
        .map_err(|e| FrtbError::Io(format!("cannot read {}: {e}", path.display())))?;
    let mut lines = text.lines().filter(|l| !l.trim().is_empty());
    let header: Vec<&str> = lines
        .next()
        .ok_or_else(|| FrtbError::Io(format!("{}: empty CSV", path.display())))?
        .split(',')
        .map(str::trim)
        .collect();
    let date_idx = header.iter().position(|h| *h == "date").ok_or_else(|| {
        FrtbError::Invalid(format!("load_pnl_csv: {} must have a 'date' column", path.display()))
    })?;
    let mut out: PnlTable = header
        .iter()
        .enumerate()
        .filter(|(i, _)| *i != date_idx)
        .map(|(_, h)| (h.to_string(), Vec::new()))
        .collect();
    for line in lines {
        let cells: Vec<&str> = line.split(',').map(str::trim).collect();
        let mut k = 0;
        for (i, cell) in cells.iter().enumerate() {
            if i == date_idx {
                continue;
            }
            let v = if cell.is_empty() {
                f64::NAN
            } else {
                cell.parse::<f64>().map_err(|_| {
                    FrtbError::Invalid(format!("load_pnl_csv: cannot parse '{cell}'"))
                })?
            };
            out[k].1.push(v);
            k += 1;
        }
    }
    if out.is_empty() || out[0].1.is_empty() {
        return invalid(format!("load_pnl_csv: {} contains no data rows", path.display()));
    }
    Ok(out)
}

/// Look up one column of a [`PnlTable`].
pub fn pnl_column<'a>(table: &'a PnlTable, name: &str) -> Result<&'a Vec<f64>> {
    table
        .iter()
        .find(|(c, _)| c == name)
        .map(|(_, v)| v)
        .ok_or_else(|| FrtbError::Invalid(format!("pnl table: missing column '{name}'")))
}

/// Extract the per-category P&L columns `<desk>_<cat>` for one desk,
/// preserving file column order.
pub fn desk_categories(desk: &str, table: &PnlTable) -> PnlTable {
    let prefix = format!("{desk}_");
    table
        .iter()
        .filter(|(c, _)| c.starts_with(&prefix))
        .map(|(c, v)| (c[prefix.len()..].to_string(), v.clone()))
        .collect()
}

/// SA results for one scope (a desk or the whole firm).
#[derive(Debug, Clone, PartialEq)]
pub struct SaScope {
    /// SBM drill-down and capital.
    pub sbm: SbmResult,
    /// DRC-lite charge.
    pub drc: f64,
    /// DRC hedge benefit ratio.
    pub drc_hbr: f64,
    /// Residual risk add-on.
    pub rrao: f64,
}

impl SaScope {
    /// SA capital = SBM + DRC + RRAO.
    pub fn capital(&self) -> f64 {
        self.sbm.capital + self.drc + self.rrao
    }
}

/// SA capital for one instrument scope: SBM + DRC-lite + RRAO.
pub fn compute_sa(
    instruments: &[Instrument],
    market: &Market,
    params: &SbmParams,
) -> Result<SaScope> {
    let sens = compute_sensitivities(instruments, market, params)?;
    let sbm = sbm_capital(&sens, market, params)?;
    let drc = drc_charge(&drc_positions_from_instruments(instruments, market)?, params)?;
    Ok(SaScope { sbm, drc: drc.charge, drc_hbr: drc.hbr, rrao: rrao_charge(instruments, params)? })
}

/// The full result tree computed from the bundled data directory.
#[derive(Debug, Clone, PartialEq)]
pub struct Results {
    /// The pinned parameter set.
    pub params: SbmParams,
    /// The loaded market snapshot.
    pub market: Market,
    /// Desks keyed by name.
    pub desks: BTreeMap<String, Desk>,
    /// SA per scope: every desk plus `"firm"`.
    pub sa: BTreeMap<String, SaScope>,
    /// Firm-wide sensitivities (reused by the stability check).
    pub sens_firm: Sensitivities,
    /// IMA sketch per desk.
    pub ima: BTreeMap<String, DeskIma>,
    /// Validation checks, findings, verdicts and the rendered report.
    pub validation: ValidationSummary,
}

/// Parse `nmrf.json` into entries.
fn load_nmrf(path: &Path) -> Result<Vec<NmrfEntry>> {
    let text = std::fs::read_to_string(path)
        .map_err(|e| FrtbError::Io(format!("cannot read {}: {e}", path.display())))?;
    let raw: Value = serde_json::from_str(&text)
        .map_err(|e| FrtbError::Io(format!("{}: bad JSON: {e}", path.display())))?;
    let factors = raw
        .get("factors")
        .and_then(Value::as_array)
        .ok_or_else(|| FrtbError::Invalid("nmrf.json: missing 'factors' list".into()))?;
    let mut out = Vec::new();
    for f in factors {
        out.push(NmrfEntry {
            factor: f
                .get("factor")
                .and_then(Value::as_str)
                .ok_or_else(|| FrtbError::Invalid("nmrf.json: entry missing 'factor'".into()))?
                .to_string(),
            desk: f
                .get("desk")
                .and_then(Value::as_str)
                .ok_or_else(|| FrtbError::Invalid("nmrf.json: entry missing 'desk'".into()))?
                .to_string(),
            stressed_loss: f
                .get("stressed_loss")
                .and_then(Value::as_f64)
                .ok_or_else(|| {
                    FrtbError::Invalid("nmrf.json: entry missing 'stressed_loss'".into())
                })?,
        });
    }
    Ok(out)
}

/// Compute the full result tree from the bundled data directory.
pub fn compute_results(data_dir: &Path) -> Result<Results> {
    let params = load_params(&data_dir.join("sbm_params.json"))?;
    let market = load_market(&data_dir.join("curves.csv"), &data_dir.join("spots.csv"))?;
    let desks = load_portfolio(&data_dir.join("portfolio.json"))?;
    let hypo = load_pnl_csv(&data_dir.join("pnl_hypo.csv"))?;
    let rtpl = load_pnl_csv(&data_dir.join("pnl_rtpl.csv"))?;
    let var99 = load_pnl_csv(&data_dir.join("pnl_var.csv"))?;
    let nmrf = load_nmrf(&data_dir.join("nmrf.json"))?;

    let desk_names: Vec<String> = desks.keys().cloned().collect();
    let all_instruments: Vec<Instrument> = desk_names
        .iter()
        .flat_map(|d| desks[d].instruments.iter().cloned())
        .collect();

    // ---- SA per desk + firm ---------------------------------------------
    let mut sa = BTreeMap::new();
    for d in &desk_names {
        sa.insert(d.clone(), compute_sa(&desks[d].instruments, &market, &params)?);
    }
    sa.insert("firm".to_string(), compute_sa(&all_instruments, &market, &params)?);
    let sens_firm = compute_sensitivities(&all_instruments, &market, &params)?;

    // ---- IMA per desk ----------------------------------------------------
    let mut ima = BTreeMap::new();
    for d in &desk_names {
        let cats = desk_categories(d, &hypo);
        if cats.is_empty() {
            return invalid(format!("compute_results: no category P&L columns for desk '{d}'"));
        }
        let full = pnl_column(&hypo, d)?;
        let es_b = es_base_10d(full, params.ima_alpha)?;
        let es_lh =
            es_lh_scaled(full, &cats, &params.category_lh, &params.lh_ladder, params.ima_alpha)?;
        let imcc_d = imcc(full, &cats, &params)?;
        let bt = backtest(full, pnl_column(&var99, d)?, &params)?;
        let pl = plat_test(full, pnl_column(&rtpl, d)?, &params)?;
        let desk_nmrf: Vec<NmrfEntry> =
            nmrf.iter().filter(|e| e.desk == *d).cloned().collect();
        let ses_d = ses(&desk_nmrf)?;
        let core = ima_capital(imcc_d, bt.multiplier, ses_d, 0.0)?;
        let surcharge = plat_surcharge(&pl.zone, sa[d].capital(), core, &params)?;
        ima.insert(
            d.clone(),
            DeskIma {
                es_base: es_b,
                es_lh,
                imcc: imcc_d,
                backtest: bt,
                plat: pl,
                ses: ses_d,
                capital_core: core,
                plat_surcharge: surcharge,
                capital: core + surcharge,
            },
        );
    }

    // ---- validation checks ----------------------------------------------
    let bench = benchmark_max_diff()?;
    let sens_diff = sensitivity_max_diff()?;
    let base_cap = sa["firm"].sbm.capital;
    let cap_up =
        sbm_capital(&sens_firm, &market, &params.with_girr_delta_rw_scaled(1.1)?)?.capital;
    let cap_dn =
        sbm_capital(&sens_firm, &market, &params.with_girr_delta_rw_scaled(0.9)?)?.capital;
    let stability_rel = if base_cap > 0.0 {
        (cap_up - base_cap).abs().max((cap_dn - base_cap).abs()) / base_cap
    } else {
        0.0
    };
    let mut dq = BTreeMap::new();
    for d in &desk_names {
        dq.insert(d.clone(), data_quality(pnl_column(&hypo, d)?)?);
    }

    let mut findings = BTreeMap::new();
    let mut verdicts = BTreeMap::new();
    for d in &desk_names {
        let inputs = DeskCheckInputs {
            benchmark_max_diff: bench,
            sensitivity_max_diff: sens_diff,
            stability_rel_change: stability_rel,
            backtest_zone: ima[d].backtest.zone.clone(),
            plat_zone: ima[d].plat.zone.clone(),
            stale_days: dq[d].stale_days,
            gaps: dq[d].gaps,
        };
        let f = classify_findings(&inputs);
        verdicts.insert(d.clone(), overall_verdict(&f).to_string());
        findings.insert(d.clone(), f);
    }

    let mut validation = ValidationSummary {
        benchmark_max_diff: bench,
        sensitivity_max_diff: sens_diff,
        stability_base_capital: base_cap,
        stability_capital_rw_up10: cap_up,
        stability_capital_rw_dn10: cap_dn,
        stability_rel_change: stability_rel,
        data_quality: dq,
        findings,
        verdicts,
        report_md: String::new(),
    };
    validation.report_md = render_report(&ima, &validation);

    Ok(Results { params, market, desks, sa, sens_firm, ima, validation })
}
