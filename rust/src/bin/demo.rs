//! End-to-end FRTB demo: SBM capital breakdown per desk / risk class /
//! scenario, DRC-lite, RRAO, the IMA sketch (ES, IMCC, PLAT, backtesting,
//! SES) and the generated independent validation report.
//!
//! Run: `cd rust && cargo run --bin demo`
//!
//! The validation report markdown is written to `rust/validation_report.md`
//! (the Rust port only writes inside its own language directory).

use std::path::Path;
use std::process::ExitCode;

use frtb::validation::fmt_thousands;
use frtb::{compute_results, FrtbError, Results, MEASURES, RISK_CLASSES};

fn fmt(x: f64) -> String {
    format!("{:>14}", fmt_thousands(x, 0))
}

fn print_sbm_scope(res: &Results, scope: &str) {
    let label = res
        .desks
        .get(scope)
        .map(|d| d.display.clone())
        .unwrap_or_else(|| "FIRM (all desks)".to_string());
    let dashes = 44usize.saturating_sub(scope.len() + label.len());
    println!("\n--- SBM: {scope} ({label}) {}", "-".repeat(dashes));
    println!("{:<10} {:<10} {:>14} {:>14} {:>14}", "risk class", "measure", "high", "medium", "low");
    let s = &res.sa[scope].sbm;
    for rc in RISK_CLASSES {
        for m in MEASURES {
            println!(
                "{:<10} {:<10} {} {} {}",
                rc,
                m,
                fmt(s.charge(rc, m, "high")),
                fmt(s.charge(rc, m, "medium")),
                fmt(s.charge(rc, m, "low"))
            );
        }
    }
    let st = &s.scenario_totals;
    println!(
        "{:<10} {:<10} {} {} {}",
        "TOTAL", "", fmt(st["high"]), fmt(st["medium"]), fmt(st["low"])
    );
    println!("SBM capital (max over scenarios): {}", fmt_thousands(s.capital, 2));
    println!(
        "DRC-lite: {}   (HBR = {:.4})   RRAO: {}",
        fmt_thousands(res.sa[scope].drc, 2),
        res.sa[scope].drc_hbr,
        fmt_thousands(res.sa[scope].rrao, 2)
    );
    println!("SA capital (SBM + DRC + RRAO):    {}", fmt_thousands(res.sa[scope].capital(), 2));
}

fn run() -> Result<(), FrtbError> {
    println!("{}", "=".repeat(76));
    println!("FRTB & Model Validation demo  —  EDUCATIONAL parameter set (Basel-2019-flavored)");
    println!("{}", "=".repeat(76));

    let root = Path::new(env!("CARGO_MANIFEST_DIR"));
    let data_dir = root.join("..").join("data");
    let res = compute_results(&data_dir)?;

    // ---- SBM breakdown ---------------------------------------------------
    for scope in ["desk1", "desk2", "firm"] {
        print_sbm_scope(&res, scope);
    }

    // ---- IMA sketch ------------------------------------------------------
    println!("\n--- IMA sketch (per desk) {}", "-".repeat(49));
    println!(
        "{:<7} {:>12} {:>12} {:>12} {:>4} {:>6} {:>5} {:>6} {:>10} {:>10} {:>12}",
        "desk", "ES base10d", "ES LH", "IMCC", "exc", "zone", "mult", "PLAT", "SES", "surchg",
        "capital"
    );
    for d in ["desk1", "desk2"] {
        let i = &res.ima[d];
        println!(
            "{:<7} {:>12} {:>12} {:>12} {:>4} {:>6} {:>5.2} {:>6} {:>10} {:>10} {:>12}",
            d,
            fmt_thousands(i.es_base, 0),
            fmt_thousands(i.es_lh, 0),
            fmt_thousands(i.imcc, 0),
            i.backtest.exceptions,
            i.backtest.zone,
            i.backtest.multiplier,
            i.plat.zone,
            fmt_thousands(i.ses, 0),
            fmt_thousands(i.plat_surcharge, 0),
            fmt_thousands(i.capital, 0)
        );
        let sp = i.plat.spearman.map_or("n/a".to_string(), |v| format!("{v:.4}"));
        let ks = i.plat.ks.map_or("n/a".to_string(), |v| format!("{v:.4}"));
        println!("        PLAT metrics: spearman = {sp}, KS = {ks}");
    }

    // ---- validation ------------------------------------------------------
    let val = &res.validation;
    println!("\n--- Independent validation {}", "-".repeat(48));
    println!(
        "benchmark BS vs binomial(501): max diff = {} (tol 0.05)",
        frtb::validation::fmt_sci3(val.benchmark_max_diff)
    );
    println!(
        "delta vs finite difference:    max diff = {} (tol 1e-06)",
        frtb::validation::fmt_sci3(val.sensitivity_max_diff)
    );
    println!(
        "stability: capital {} -> {} under +10% GIRR RW ({:.2}% max move)",
        fmt_thousands(val.stability_base_capital, 0),
        fmt_thousands(val.stability_capital_rw_up10, 0),
        val.stability_rel_change * 100.0
    );
    for d in ["desk1", "desk2"] {
        let rules: Vec<&str> = val.findings[d].iter().map(|f| f.rule_id.as_str()).collect();
        let rules = if rules.is_empty() { "none".to_string() } else { rules.join(", ") };
        println!("{d}: findings = {rules}  ->  verdict: {}", val.verdicts[d].to_uppercase());
    }

    let report_path = root.join("validation_report.md");
    std::fs::write(&report_path, &val.report_md)
        .map_err(|e| FrtbError::Io(format!("cannot write {}: {e}", report_path.display())))?;
    println!("\nvalidation_report.md written to {}", report_path.display());
    let verdicts: Vec<String> =
        ["desk1", "desk2"].iter().map(|d| format!("{d}={}", val.verdicts[*d])).collect();
    println!("Overall verdicts: {}", verdicts.join(", "));
    Ok(())
}

fn main() -> ExitCode {
    match run() {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("demo failed: {e}");
            ExitCode::FAILURE
        }
    }
}
