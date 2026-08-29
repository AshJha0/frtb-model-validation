//! Standardised Approach assembly: SBM charges per risk class and scenario,
//! plus DRC-lite and RRAO. SA capital = SBM + DRC + RRAO.

use std::collections::BTreeMap;

use crate::error::{invalid, Result};
use crate::instruments::Instrument;
use crate::market::Market;
use crate::params::SbmParams;
use crate::pricers::price_bond;
use crate::sbm::{curvature_charge, delta_vega_charge, Scenario, SCENARIOS};
use crate::sensitivities::Sensitivities;

/// Risk classes in the pinned summation order.
pub const RISK_CLASSES: [&str; 3] = ["girr", "equity", "fx"];
/// Measures in the pinned summation order.
pub const MEASURES: [&str; 3] = ["delta", "vega", "curvature"];

/// SBM capital with full drill-down.
///
/// * `charges[risk_class][measure][scenario] -> charge`
/// * `kb_medium[risk_class][measure] -> {bucket -> K_b}` (medium scenario)
/// * `scenario_totals[scenario] -> sum over risk classes and measures`
/// * `capital` = max over scenarios of `scenario_totals`.
#[derive(Debug, Clone, PartialEq)]
pub struct SbmResult {
    /// Per risk class / measure / scenario charge.
    pub charges: BTreeMap<String, BTreeMap<String, BTreeMap<String, f64>>>,
    /// Medium-scenario per-bucket K_b per risk class / measure.
    pub kb_medium: BTreeMap<String, BTreeMap<String, BTreeMap<String, f64>>>,
    /// Sum of all risk-class charges per scenario.
    pub scenario_totals: BTreeMap<String, f64>,
    /// SBM capital = max over the three scenario totals.
    pub capital: f64,
}

impl SbmResult {
    /// Convenience accessor: charge for one risk class / measure / scenario.
    pub fn charge(&self, risk_class: &str, measure: &str, scenario: &str) -> f64 {
        self.charges
            .get(risk_class)
            .and_then(|m| m.get(measure))
            .and_then(|s| s.get(scenario))
            .copied()
            .unwrap_or(0.0)
    }
}

/// Tenor label used as the GIRR factor key: matches Python's `f"{t:g}"`
/// (e.g. `0.25`, `0.5`, `1`, `10`).
pub fn tenor_label(t: f64) -> String {
    format!("{t}")
}

/// GIRR delta WS per currency bucket (factor key = tenor label).
fn girr_bucket_ws(
    sens: &Sensitivities,
    params: &SbmParams,
) -> Result<BTreeMap<String, BTreeMap<String, f64>>> {
    let mut out = BTreeMap::new();
    for (ccy, ladder) in &sens.girr {
        let mut factors = BTreeMap::new();
        for (i, &s) in ladder.iter().enumerate() {
            let t = params.girr_tenors[i];
            factors.insert(tenor_label(t), params.girr_delta_rw[i] * s);
        }
        out.insert(ccy.clone(), factors);
    }
    Ok(out)
}

/// Equity delta or vega WS per bucket (factor key = underlier name).
fn equity_bucket_ws(
    sens_map: &BTreeMap<String, f64>,
    market: &Market,
    params: &SbmParams,
    vega: bool,
) -> Result<BTreeMap<String, BTreeMap<String, f64>>> {
    let mut out: BTreeMap<String, BTreeMap<String, f64>> = BTreeMap::new();
    for (name, &s) in sens_map {
        let b = market.equity(name)?.bucket.clone();
        let p = params.equity_bucket(&b)?; // error if the bucket is not pinned
        let rw = if vega { p.vega_rw } else { p.delta_rw };
        out.entry(b).or_default().insert(name.clone(), rw * s);
    }
    Ok(out)
}

/// FX WS: single pinned bucket "FX" (factor key = currency pair).
fn fx_bucket_ws(
    sens_map: &BTreeMap<String, f64>,
    params: &SbmParams,
) -> BTreeMap<String, BTreeMap<String, f64>> {
    let mut out = BTreeMap::new();
    if !sens_map.is_empty() {
        let factors: BTreeMap<String, f64> =
            sens_map.iter().map(|(p, &s)| (p.clone(), params.fx_delta_rw * s)).collect();
        out.insert("FX".to_string(), factors);
    }
    out
}

/// Assemble the full SBM capital: 3 risk classes x 3 measures x 3
/// scenarios; capital is the max scenario total.
pub fn sbm_capital(
    sens: &Sensitivities,
    market: &Market,
    params: &SbmParams,
) -> Result<SbmResult> {
    let (hi, lo) = (params.scenario_high, params.scenario_low);
    let mut charges: BTreeMap<String, BTreeMap<String, BTreeMap<String, f64>>> = BTreeMap::new();
    let mut kb_medium: BTreeMap<String, BTreeMap<String, BTreeMap<String, f64>>> = BTreeMap::new();
    for rc in RISK_CLASSES {
        let mut m = BTreeMap::new();
        for measure in MEASURES {
            m.insert(measure.to_string(), BTreeMap::new());
        }
        charges.insert(rc.to_string(), m);
        kb_medium.insert(rc.to_string(), BTreeMap::new());
    }

    // GIRR tenor-label -> tenor-index lookup for the correlation matrix.
    let tenor_index: BTreeMap<String, usize> = params
        .girr_tenors
        .iter()
        .enumerate()
        .map(|(i, &t)| (tenor_label(t), i))
        .collect();
    let girr_rho = |_b: &str, k: &str, l: &str| -> Result<f64> {
        match (tenor_index.get(k), tenor_index.get(l)) {
            (Some(&i), Some(&j)) => Ok(params.girr_rho_kl(i, j)),
            _ => invalid(format!("girr rho: unknown tenor label '{k}'/'{l}'")),
        }
    };
    let girr_gamma = |_b: &str, _c: &str| -> Result<f64> { Ok(params.girr_gamma) };
    let eq_rho = |bucket: &str, _k: &str, _l: &str| -> Result<f64> {
        Ok(params.equity_bucket(bucket)?.rho)
    };
    let eq_gamma = |_b: &str, _c: &str| -> Result<f64> { Ok(params.equity_gamma) };
    let fx_rho = |_b: &str, _k: &str, _l: &str| -> Result<f64> { Ok(params.fx_rho) };
    let fx_gamma = |_b: &str, _c: &str| -> Result<f64> { Ok(params.fx_gamma) };

    // -- delta / vega ------------------------------------------------------
    let girr_ws = girr_bucket_ws(sens, params)?;
    let eqd_ws = equity_bucket_ws(&sens.equity_delta, market, params, false)?;
    let eqv_ws = equity_bucket_ws(&sens.equity_vega, market, params, true)?;
    let fx_ws = fx_bucket_ws(&sens.fx_delta, params);
    let empty_ws: BTreeMap<String, BTreeMap<String, f64>> = BTreeMap::new();

    type RhoFn<'a> = &'a dyn Fn(&str, &str, &str) -> Result<f64>;
    type GammaFn<'a> = &'a dyn Fn(&str, &str) -> Result<f64>;
    type WsMap = BTreeMap<String, BTreeMap<String, f64>>;
    type DvSpec<'a> = (&'a str, &'a str, &'a WsMap, RhoFn<'a>, GammaFn<'a>);
    let dv_specs: [DvSpec; 5] = [
        ("girr", "delta", &girr_ws, &girr_rho, &girr_gamma),
        // No IR-vol instruments in scope: GIRR vega is identically zero.
        ("girr", "vega", &empty_ws, &girr_rho, &girr_gamma),
        ("equity", "delta", &eqd_ws, &eq_rho, &eq_gamma),
        ("equity", "vega", &eqv_ws, &eq_rho, &eq_gamma),
        ("fx", "delta", &fx_ws, &fx_rho, &fx_gamma),
    ];
    for (rc, measure, ws, rho, gamma) in dv_specs {
        for scen in SCENARIOS {
            let res = delta_vega_charge(ws, rho, gamma, scen, hi, lo)?;
            charges.get_mut(rc).unwrap().get_mut(measure).unwrap()
                .insert(scen.name().to_string(), res.charge);
            if scen == Scenario::Medium {
                kb_medium.get_mut(rc).unwrap().insert(measure.to_string(), res.kb);
            }
        }
    }

    // -- curvature ---------------------------------------------------------
    let mut girr_cvr = BTreeMap::new();
    let mut girr_keys = BTreeMap::new();
    for (ccy, &(up, dn)) in &sens.girr_cvr {
        girr_cvr.insert(ccy.clone(), (vec![up], vec![dn]));
        girr_keys.insert(ccy.clone(), vec!["crv".to_string()]);
    }
    let mut eq_cvr: BTreeMap<String, (Vec<f64>, Vec<f64>)> = BTreeMap::new();
    let mut eq_keys: BTreeMap<String, Vec<String>> = BTreeMap::new();
    for (name, &(up, dn)) in &sens.equity_cvr {
        let b = market.equity(name)?.bucket.clone();
        params.equity_bucket(&b)?;
        let entry = eq_cvr.entry(b.clone()).or_default();
        entry.0.push(up);
        entry.1.push(dn);
        eq_keys.entry(b).or_default().push(name.clone());
    }
    let mut fx_cvr = BTreeMap::new();
    let mut fx_keys = BTreeMap::new();
    if !sens.fx_cvr.is_empty() {
        let ups: Vec<f64> = sens.fx_cvr.values().map(|&(u, _)| u).collect();
        let dns: Vec<f64> = sens.fx_cvr.values().map(|&(_, d)| d).collect();
        fx_cvr.insert("FX".to_string(), (ups, dns));
        fx_keys.insert("FX".to_string(), sens.fx_cvr.keys().cloned().collect());
    }

    type CvrMap = BTreeMap<String, (Vec<f64>, Vec<f64>)>;
    type CrvSpec<'a> =
        (&'a str, &'a CvrMap, RhoFn<'a>, GammaFn<'a>, &'a BTreeMap<String, Vec<String>>);
    let crv_specs: [CrvSpec; 3] = [
        ("girr", &girr_cvr, &girr_rho, &girr_gamma, &girr_keys),
        ("equity", &eq_cvr, &eq_rho, &eq_gamma, &eq_keys),
        ("fx", &fx_cvr, &fx_rho, &fx_gamma, &fx_keys),
    ];
    for (rc, cvr, rho, gamma, keys) in crv_specs {
        for scen in SCENARIOS {
            let res = curvature_charge(cvr, rho, gamma, scen, keys, hi, lo)?;
            charges.get_mut(rc).unwrap().get_mut("curvature").unwrap()
                .insert(scen.name().to_string(), res.charge);
            if scen == Scenario::Medium {
                kb_medium.get_mut(rc).unwrap().insert("curvature".to_string(), res.kb);
            }
        }
    }

    // FX vega not modelled: pin to zero for all scenarios.
    for scen in SCENARIOS {
        charges.get_mut("fx").unwrap().get_mut("vega").unwrap()
            .insert(scen.name().to_string(), 0.0);
    }

    // Scenario totals in the pinned risk-class x measure order, capital =
    // max over the three totals.
    let mut scenario_totals = BTreeMap::new();
    let mut capital = f64::NEG_INFINITY;
    for scen in SCENARIOS {
        let mut total = 0.0;
        for rc in RISK_CLASSES {
            for measure in MEASURES {
                total += charges[rc][measure].get(scen.name()).copied().unwrap_or(0.0);
            }
        }
        scenario_totals.insert(scen.name().to_string(), total);
        if total > capital {
            capital = total;
        }
    }
    Ok(SbmResult { charges, kb_medium, scenario_totals, capital })
}

// --------------------------------------------------------------------------
// DRC-lite
// --------------------------------------------------------------------------

/// One default-risk position: issuer, rating, notional (signed), market
/// value.
#[derive(Debug, Clone, PartialEq)]
pub struct DrcPosition {
    /// Issuer (netting key).
    pub issuer: String,
    /// Rating label for the pinned RW table.
    pub rating: String,
    /// Signed notional (shorts negative).
    pub notional: f64,
    /// Current market value of the position.
    pub market_value: f64,
    /// Loss-given-default in [0, 1].
    pub lgd: f64,
}

impl DrcPosition {
    /// Jump-to-default: `JTD = LGD*notional + (MV - notional)` (signed).
    pub fn jtd(&self) -> f64 {
        self.lgd * self.notional + (self.market_value - self.notional)
    }
}

/// DRC-lite output: charge + netting/HBR drill-down.
#[derive(Debug, Clone, PartialEq)]
pub struct DrcResult {
    /// The default risk charge.
    pub charge: f64,
    /// Hedge benefit ratio (1 when there are no net shorts).
    pub hbr: f64,
    /// Net JTD per issuer, in first-seen issuer order.
    pub net_jtd: Vec<(String, f64)>,
    /// Sum of positive net JTDs.
    pub gross_long: f64,
    /// Sum of |negative net JTDs|.
    pub gross_short: f64,
}

/// Default Risk Charge (lite).
///
/// 1. `JTD_i = LGD*notional + (MV - notional)` per position (signed;
///    shorts have negative notional).
/// 2. Net JTD per issuer (long/short netting within the same issuer).
/// 3. `HBR = sum(netLong) / (sum(netLong) + sum(|netShort|))`; HBR = 1 when
///    there are no net shorts (all-long edge case) and when the book is
///    empty.
/// 4. `DRC = max(0, sum RW_i*netLong_i - HBR * sum RW_i*|netShort_i|)`
///    with RW from the pinned rating table (unknown rating -> error).
pub fn drc_charge(positions: &[DrcPosition], params: &SbmParams) -> Result<DrcResult> {
    let mut order: Vec<String> = Vec::new();
    let mut net: BTreeMap<String, f64> = BTreeMap::new();
    let mut rating_of: BTreeMap<String, String> = BTreeMap::new();
    for p in positions {
        if !net.contains_key(&p.issuer) {
            order.push(p.issuer.clone());
        }
        *net.entry(p.issuer.clone()).or_insert(0.0) += p.jtd();
        match rating_of.get(&p.issuer) {
            None => {
                rating_of.insert(p.issuer.clone(), p.rating.clone());
            }
            Some(prev) if *prev != p.rating => {
                return invalid(format!(
                    "drc_charge: issuer '{}' has inconsistent ratings ('{prev}' vs '{}')",
                    p.issuer, p.rating
                ));
            }
            Some(_) => {}
        }
    }
    let mut long_sum = 0.0;
    let mut short_sum = 0.0;
    for issuer in &order {
        let v = net[issuer];
        if v > 0.0 {
            long_sum += v;
        } else if v < 0.0 {
            short_sum += -v;
        }
    }
    let denom = long_sum + short_sum;
    let hbr = if denom > 0.0 { long_sum / denom } else { 1.0 };
    let mut weighted_long = 0.0;
    let mut weighted_short = 0.0;
    for issuer in &order {
        let v = net[issuer];
        // Rating is only looked up for issuers that contribute (mirrors the
        // reference, which filters before the RW lookup).
        if v > 0.0 {
            weighted_long += params.drc_rw(&rating_of[issuer])? * v;
        } else if v < 0.0 {
            weighted_short += params.drc_rw(&rating_of[issuer])? * (-v);
        }
    }
    let charge = (weighted_long - hbr * weighted_short).max(0.0);
    let net_jtd = order.into_iter().map(|i| { let v = net[&i]; (i, v) }).collect();
    Ok(DrcResult { charge, hbr, net_jtd, gross_long: long_sum, gross_short: short_sum })
}

/// Extract DRC positions from an instrument scope (bonds only in this
/// educational kit — documented simplification).
pub fn drc_positions_from_instruments(
    instruments: &[Instrument],
    market: &Market,
) -> Result<Vec<DrcPosition>> {
    let mut out = Vec::new();
    for inst in instruments {
        if let Instrument::Bond(b) = inst {
            let mv = price_bond(b, market.curve(&b.currency)?)?;
            out.push(DrcPosition {
                issuer: b.issuer.clone(),
                rating: b.rating.clone(),
                notional: b.notional,
                market_value: mv,
                lgd: b.lgd,
            });
        }
    }
    Ok(out)
}

// --------------------------------------------------------------------------
// RRAO
// --------------------------------------------------------------------------

/// Residual risk add-on: sum over flagged instruments of `rate * notional`.
///
/// Pinned rates: `exotic` 1.0%, `other` 0.1% of the flagged notional.
pub fn rrao_charge(instruments: &[Instrument], params: &SbmParams) -> Result<f64> {
    let mut total = 0.0;
    for inst in instruments {
        if let Some(flag) = inst.rrao() {
            total += params.rrao_rate(&flag.category)? * flag.notional;
        }
    }
    Ok(total)
}
