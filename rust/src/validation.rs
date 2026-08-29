//! Independent model validation framework.
//!
//! Checks (all pinned):
//!
//! * Benchmarking — project BS pricer vs an independent CRR binomial
//!   lattice (501 steps) on a pinned strike/maturity/call-put grid;
//!   PASS iff max abs price diff <= 0.05.
//! * Sensitivity — analytic BS delta vs central finite difference
//!   (`h = 1e-4 * S`) on the same grid; PASS iff max diff <= 1e-6.
//! * Stability — SBM capital recomputed with GIRR delta RWs x0.9 / x1.1;
//!   finding if `|delta capital| / base capital > 0.25`.
//! * Backtesting — desk VaR backtest zone (from [`crate::ima`]).
//! * PLAT — desk PLAT zone (from [`crate::plat`]).
//! * Data quality — staleness: # of zero-change days > 15 -> finding;
//!   gaps: any missing (NaN) value -> finding.
//!
//! Findings classification (pinned rule table, see [`classify_findings`]):
//! High -> verdict `reject`; Medium -> `approve-with-conditions` (if no
//! High); else `approve`.

use std::collections::BTreeMap;

use crate::error::{invalid, Result};
use crate::ima::DeskIma;
use crate::pricers::{binomial_price, bs_delta, bs_price};

/// Pinned binomial benchmark steps.
pub const BENCH_STEPS: usize = 501;
/// Pinned benchmark pass tolerance on |BS - binomial|.
pub const BENCH_TOL: f64 = 0.05;
/// Pinned sensitivity pass tolerance on |analytic - FD| delta.
pub const SENS_TOL: f64 = 1e-6;
/// Pinned stability threshold on |delta capital| / capital.
pub const STABILITY_THRESHOLD: f64 = 0.25;
/// Pinned staleness threshold (zero-change days).
pub const STALENESS_THRESHOLD: usize = 15;
/// Pinned benchmark grid strikes.
pub const BENCH_GRID_STRIKES: [f64; 5] = [70.0, 85.0, 100.0, 115.0, 130.0];
/// Pinned benchmark grid maturities.
pub const BENCH_GRID_MATURITIES: [f64; 4] = [0.25, 0.5, 1.0, 2.0];
/// Pinned benchmark spot / rate / dividend yield / vol.
pub const BENCH_SPOT: f64 = 100.0;
/// Pinned benchmark risk-free rate.
pub const BENCH_RATE: f64 = 0.03;
/// Pinned benchmark dividend yield.
pub const BENCH_DIV: f64 = 0.01;
/// Pinned benchmark volatility.
pub const BENCH_VOL: f64 = 0.2;

/// Finding severities, highest first.
pub const SEVERITIES: [&str; 3] = ["High", "Medium", "Low"];
/// Possible overall verdicts.
pub const VERDICTS: [&str; 3] = ["approve", "approve-with-conditions", "reject"];

/// One validation finding: pinned rule id, severity, human description.
#[derive(Debug, Clone, PartialEq)]
pub struct Finding {
    /// Rule identifier (e.g. "BENCH-01").
    pub rule_id: String,
    /// "High", "Medium" or "Low".
    pub severity: String,
    /// Human-readable description.
    pub description: String,
}

impl Finding {
    fn new(rule_id: &str, severity: &str, description: String) -> Finding {
        Finding { rule_id: rule_id.to_string(), severity: severity.to_string(), description }
    }
}

// --------------------------------------------------------------------------
// Checks
// --------------------------------------------------------------------------

/// Max abs diff |BS - binomial(501)| over the pinned option grid.
pub fn benchmark_max_diff() -> Result<f64> {
    let mut worst = 0.0f64;
    for k in BENCH_GRID_STRIKES {
        for t in BENCH_GRID_MATURITIES {
            for call in [true, false] {
                let a = bs_price(BENCH_SPOT, k, t, BENCH_RATE, BENCH_DIV, BENCH_VOL, call)?;
                let b = binomial_price(
                    BENCH_SPOT, k, t, BENCH_RATE, BENCH_DIV, BENCH_VOL, call, BENCH_STEPS,
                )?;
                worst = worst.max((a - b).abs());
            }
        }
    }
    Ok(worst)
}

/// Max abs diff between analytic BS delta and a central finite difference
/// (`h = 1e-4 * S`) over the pinned grid.
pub fn sensitivity_max_diff() -> Result<f64> {
    let h = 1e-4 * BENCH_SPOT;
    let mut worst = 0.0f64;
    for k in BENCH_GRID_STRIKES {
        for t in BENCH_GRID_MATURITIES {
            for call in [true, false] {
                let analytic =
                    bs_delta(BENCH_SPOT, k, t, BENCH_RATE, BENCH_DIV, BENCH_VOL, call)?;
                let up = bs_price(BENCH_SPOT + h, k, t, BENCH_RATE, BENCH_DIV, BENCH_VOL, call)?;
                let dn = bs_price(BENCH_SPOT - h, k, t, BENCH_RATE, BENCH_DIV, BENCH_VOL, call)?;
                worst = worst.max((analytic - (up - dn) / (2.0 * h)).abs());
            }
        }
    }
    Ok(worst)
}

/// Data-quality metrics of one series.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DataQuality {
    /// Number of zero-change days between consecutive non-missing values.
    pub stale_days: usize,
    /// Number of missing (NaN) values.
    pub gaps: usize,
}

/// Staleness (# zero-change days) and gaps (# NaN values) of one series.
pub fn data_quality(series: &[f64]) -> Result<DataQuality> {
    if series.len() < 2 {
        return invalid("data_quality: need at least 2 observations");
    }
    let gaps = series.iter().filter(|v| v.is_nan()).count();
    let clean: Vec<f64> = series.iter().copied().filter(|v| !v.is_nan()).collect();
    let stale = clean.windows(2).filter(|w| w[1] - w[0] == 0.0).count();
    Ok(DataQuality { stale_days: stale, gaps })
}

// --------------------------------------------------------------------------
// Findings classification (pinned rule table)
// --------------------------------------------------------------------------

/// Everything the rule table needs to classify one desk.
#[derive(Debug, Clone, PartialEq)]
pub struct DeskCheckInputs {
    /// Benchmark check result.
    pub benchmark_max_diff: f64,
    /// Sensitivity (delta vs FD) check result.
    pub sensitivity_max_diff: f64,
    /// `max(|dCap(x1.1)|, |dCap(x0.9)|) / base capital`.
    pub stability_rel_change: f64,
    /// Desk backtest zone.
    pub backtest_zone: String,
    /// Desk PLAT zone.
    pub plat_zone: String,
    /// Zero-change days in the desk P&L.
    pub stale_days: usize,
    /// Missing values in the desk P&L.
    pub gaps: usize,
}

/// Apply the pinned rule table; returns findings in table order.
///
/// | rule | severity | fires when |
/// |---|---|---|
/// | BENCH-01 | High   | benchmark max diff > 0.05 |
/// | SENS-01  | High   | delta FD max diff > 1e-6 |
/// | BT-01    | High   | backtest zone red |
/// | BT-02    | Medium | backtest zone amber |
/// | PLAT-01  | High   | PLAT zone red |
/// | PLAT-02  | Medium | PLAT zone amber |
/// | STAB-01  | Medium | max abs rel capital move > 0.25 |
/// | DQ-01    | Medium | zero-change days > 15 |
/// | DQ-02    | Low    | any missing value |
///
/// All comparisons are strict `>`.
pub fn classify_findings(c: &DeskCheckInputs) -> Vec<Finding> {
    let mut out = Vec::new();
    if c.benchmark_max_diff > BENCH_TOL {
        out.push(Finding::new(
            "BENCH-01",
            "High",
            format!(
                "Pricing benchmark max diff {} exceeds tolerance {BENCH_TOL}",
                fmt_g6(c.benchmark_max_diff)
            ),
        ));
    }
    if c.sensitivity_max_diff > SENS_TOL {
        out.push(Finding::new(
            "SENS-01",
            "High",
            format!(
                "Analytic vs FD delta max diff {} exceeds {SENS_TOL}",
                fmt_g6(c.sensitivity_max_diff)
            ),
        ));
    }
    if c.backtest_zone == "red" {
        out.push(Finding::new("BT-01", "High", "VaR backtest in RED zone".to_string()));
    }
    if c.backtest_zone == "amber" {
        out.push(Finding::new("BT-02", "Medium", "VaR backtest in AMBER zone".to_string()));
    }
    if c.plat_zone == "red" {
        out.push(Finding::new("PLAT-01", "High", "PLAT in RED zone".to_string()));
    }
    if c.plat_zone == "amber" {
        out.push(Finding::new("PLAT-02", "Medium", "PLAT in AMBER zone".to_string()));
    }
    if c.stability_rel_change > STABILITY_THRESHOLD {
        out.push(Finding::new(
            "STAB-01",
            "Medium",
            format!(
                "Capital moves {:.1}% under +/-10% GIRR RW (threshold {:.0}%)",
                c.stability_rel_change * 100.0,
                STABILITY_THRESHOLD * 100.0
            ),
        ));
    }
    if c.stale_days > STALENESS_THRESHOLD {
        out.push(Finding::new(
            "DQ-01",
            "Medium",
            format!(
                "{} zero-change days exceed staleness threshold {STALENESS_THRESHOLD}",
                c.stale_days
            ),
        ));
    }
    if c.gaps > 0 {
        out.push(Finding::new(
            "DQ-02",
            "Low",
            format!("{} missing values in the P&L series", c.gaps),
        ));
    }
    out
}

/// Pinned verdict rule: any High -> `reject`; any Medium ->
/// `approve-with-conditions`; else `approve`.
pub fn overall_verdict(findings: &[Finding]) -> &'static str {
    if findings.iter().any(|f| f.severity == "High") {
        "reject"
    } else if findings.iter().any(|f| f.severity == "Medium") {
        "approve-with-conditions"
    } else {
        "approve"
    }
}

// --------------------------------------------------------------------------
// Validation summary + report generation
// --------------------------------------------------------------------------

/// All validation outputs for the report and the golden suite.
#[derive(Debug, Clone, PartialEq)]
pub struct ValidationSummary {
    /// Benchmark check result.
    pub benchmark_max_diff: f64,
    /// Delta-vs-FD check result.
    pub sensitivity_max_diff: f64,
    /// Firm SBM capital under the base parameter set.
    pub stability_base_capital: f64,
    /// Firm SBM capital under GIRR delta RW x1.1.
    pub stability_capital_rw_up10: f64,
    /// Firm SBM capital under GIRR delta RW x0.9.
    pub stability_capital_rw_dn10: f64,
    /// `max(|dCap up|, |dCap dn|) / base` (0 when base is 0).
    pub stability_rel_change: f64,
    /// Per-desk data-quality metrics.
    pub data_quality: BTreeMap<String, DataQuality>,
    /// Per-desk findings, in rule-table order.
    pub findings: BTreeMap<String, Vec<Finding>>,
    /// Per-desk overall verdicts (exact pinned strings).
    pub verdicts: BTreeMap<String, String>,
    /// The rendered markdown report.
    pub report_md: String,
}

/// The ten pinned report section titles (always all emitted, prefixed
/// `## `).
pub const REPORT_SECTIONS: [&str; 10] = [
    "1. Scope & Overview",
    "2. Pricing Benchmark",
    "3. Sensitivity Verification",
    "4. Capital Stability",
    "5. VaR Backtesting",
    "6. P&L Attribution (PLAT)",
    "7. Data Quality",
    "8. NMRF / SES",
    "9. Findings",
    "10. Overall Verdict",
];

/// Format with thousands separators and a fixed number of decimals
/// (Python's `{:,.2f}` style).
pub fn fmt_thousands(x: f64, decimals: usize) -> String {
    let s = format!("{:.*}", decimals, x);
    let (sign, rest) = match s.strip_prefix('-') {
        Some(r) => ("-", r),
        None => ("", s.as_str()),
    };
    let (int_part, frac_part) = match rest.split_once('.') {
        Some((i, f)) => (i, Some(f)),
        None => (rest, None),
    };
    let mut grouped = String::new();
    let bytes = int_part.as_bytes();
    for (i, b) in bytes.iter().enumerate() {
        if i > 0 && (bytes.len() - i) % 3 == 0 {
            grouped.push(',');
        }
        grouped.push(*b as char);
    }
    match frac_part {
        Some(f) => format!("{sign}{grouped}.{f}"),
        None => format!("{sign}{grouped}"),
    }
}

/// Scientific notation with 3 decimals and a signed two-digit exponent
/// (Python's `{:.3e}` style, e.g. `5.163e-03`).
pub fn fmt_sci3(x: f64) -> String {
    let s = format!("{:.3e}", x);
    match s.split_once('e') {
        Some((mant, exp)) => {
            let e: i32 = exp.parse().unwrap_or(0);
            format!("{mant}e{}{:02}", if e < 0 { "-" } else { "+" }, e.abs())
        }
        None => s,
    }
}

/// Python `%g`-flavored formatting with 6 significant digits (used in
/// finding descriptions).
pub fn fmt_g6(x: f64) -> String {
    if x == 0.0 {
        return "0".to_string();
    }
    let exp = x.abs().log10().floor() as i32;
    if (-4..6).contains(&exp) {
        let decimals = (5 - exp).max(0) as usize;
        let mut s = format!("{:.*}", decimals, x);
        if s.contains('.') {
            s = s.trim_end_matches('0').trim_end_matches('.').to_string();
        }
        s
    } else {
        let mut mant = format!("{:.5e}", x);
        if let Some((m, e)) = mant.clone().split_once('e') {
            let m = m.trim_end_matches('0').trim_end_matches('.');
            let ev: i32 = e.parse().unwrap_or(0);
            mant = format!("{m}e{}{:02}", if ev < 0 { "-" } else { "+" }, ev.abs());
        }
        mant
    }
}

fn fmt(x: f64) -> String {
    fmt_thousands(x, 2)
}

/// Render the validation report markdown from the engine's results.
///
/// Always emits every section in [`REPORT_SECTIONS`] (tested by
/// string-contains checks in every language).
pub fn render_report(ima: &BTreeMap<String, DeskIma>, val: &ValidationSummary) -> String {
    let desks: Vec<&String> = ima.keys().collect();
    let mut lines: Vec<String> = Vec::new();
    let mut add = |s: String| lines.push(s);

    add("# Independent Model Validation Report".to_string());
    add(String::new());
    add("> Educational FRTB implementation — Basel-2019-flavored pinned parameter set.".to_string());
    add("> NOT a compliant capital engine; for teaching and testing only.".to_string());
    add(String::new());
    add(format!("## {}", REPORT_SECTIONS[0]));
    add(String::new());
    let desk_list: Vec<String> = desks.iter().map(|d| d.to_string()).collect();
    add(format!(
        "Desks in scope: {}. Framework: SBM + DRC + RRAO (SA) and ES/IMCC + PLAT + backtesting + SES (IMA sketch).",
        desk_list.join(", ")
    ));
    add(String::new());

    add(format!("## {}", REPORT_SECTIONS[1]));
    add(String::new());
    add("| metric | value | threshold | result |".to_string());
    add("|---|---|---|---|".to_string());
    let bmd = val.benchmark_max_diff;
    add(format!(
        "| max abs diff BS vs binomial({BENCH_STEPS}) | {} | {BENCH_TOL} | {} |",
        fmt_sci3(bmd),
        if bmd <= BENCH_TOL { "PASS" } else { "FAIL" }
    ));
    add(String::new());

    add(format!("## {}", REPORT_SECTIONS[2]));
    add(String::new());
    let smd = val.sensitivity_max_diff;
    // Python renders the 1e-6 threshold as "1e-06" (two-digit exponent).
    let sens_tol = {
        let s = format!("{SENS_TOL:e}");
        match s.split_once('e') {
            Some((m, e)) => {
                let ev: i32 = e.parse().unwrap_or(0);
                format!("{m}e{}{:02}", if ev < 0 { "-" } else { "+" }, ev.abs())
            }
            None => s,
        }
    };
    add(format!(
        "Analytic BS delta vs central finite difference: max abs diff {} (threshold {sens_tol}) — {}.",
        fmt_sci3(smd),
        if smd <= SENS_TOL { "PASS" } else { "FAIL" }
    ));
    add(String::new());

    add(format!("## {}", REPORT_SECTIONS[3]));
    add(String::new());
    add("| scenario | SBM capital | change vs base |".to_string());
    add("|---|---|---|".to_string());
    let (base_cap, up_cap, dn_cap) = (
        val.stability_base_capital,
        val.stability_capital_rw_up10,
        val.stability_capital_rw_dn10,
    );
    add(format!("| base | {} | — |", fmt(base_cap)));
    add(format!("| GIRR delta RW x1.1 | {} | {} |", fmt(up_cap), fmt(up_cap - base_cap)));
    add(format!("| GIRR delta RW x0.9 | {} | {} |", fmt(dn_cap), fmt(dn_cap - base_cap)));
    add(String::new());

    add(format!("## {}", REPORT_SECTIONS[4]));
    add(String::new());
    add("| desk | exceptions | zone | multiplier |".to_string());
    add("|---|---|---|---|".to_string());
    for d in &desks {
        let bt = &ima[*d].backtest;
        add(format!("| {d} | {} | {} | {:.2} |", bt.exceptions, bt.zone, bt.multiplier));
    }
    add(String::new());

    add(format!("## {}", REPORT_SECTIONS[5]));
    add(String::new());
    add("| desk | spearman | KS | zone | surcharge |".to_string());
    add("|---|---|---|---|---|".to_string());
    for d in &desks {
        let pl = &ima[*d].plat;
        let sp = pl.spearman.map_or("n/a".to_string(), |v| format!("{v:.4}"));
        let ks = pl.ks.map_or("n/a".to_string(), |v| format!("{v:.4}"));
        add(format!("| {d} | {sp} | {ks} | {} | {} |", pl.zone, fmt(ima[*d].plat_surcharge)));
    }
    add(String::new());

    add(format!("## {}", REPORT_SECTIONS[6]));
    add(String::new());
    add("| desk | zero-change days | gaps |".to_string());
    add("|---|---|---|".to_string());
    for d in &desks {
        let dq = &val.data_quality[*d];
        add(format!("| {d} | {} | {} |", dq.stale_days, dq.gaps));
    }
    add(String::new());

    add(format!("## {}", REPORT_SECTIONS[7]));
    add(String::new());
    add("| desk | SES |".to_string());
    add("|---|---|".to_string());
    for d in &desks {
        add(format!("| {d} | {} |", fmt(ima[*d].ses)));
    }
    add(String::new());

    add(format!("## {}", REPORT_SECTIONS[8]));
    add(String::new());
    let mut any_finding = false;
    for d in &desks {
        for f in &val.findings[*d] {
            add(format!("- **{}** [{}] ({d}): {}", f.severity, f.rule_id, f.description));
            any_finding = true;
        }
    }
    if !any_finding {
        add("- No findings.".to_string());
    }
    add(String::new());

    add(format!("## {}", REPORT_SECTIONS[9]));
    add(String::new());
    for d in &desks {
        add(format!("- {d}: **{}**", val.verdicts[*d]));
    }
    add(String::new());
    lines.join("\n")
}
