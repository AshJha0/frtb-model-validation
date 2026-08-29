//! Validation framework tests: pinned findings rule table, verdict rules,
//! data quality, report sections and end-to-end determinism.

mod common;

use frtb::validation::{
    benchmark_max_diff, classify_findings, data_quality, overall_verdict, sensitivity_max_diff,
    DeskCheckInputs, Finding, BENCH_TOL, REPORT_SECTIONS, SENS_TOL,
};

fn clean_inputs() -> DeskCheckInputs {
    DeskCheckInputs {
        benchmark_max_diff: 0.001,
        sensitivity_max_diff: 1e-9,
        stability_rel_change: 0.01,
        backtest_zone: "green".to_string(),
        plat_zone: "green".to_string(),
        stale_days: 0,
        gaps: 0,
    }
}

#[test]
fn no_findings_on_clean_inputs() {
    let f = classify_findings(&clean_inputs());
    assert!(f.is_empty());
    assert_eq!(overall_verdict(&f), "approve");
}

#[test]
fn findings_rules_fire_on_constructed_failures() {
    let inputs = DeskCheckInputs {
        benchmark_max_diff: 0.06,          // > 0.05 -> BENCH-01 High
        sensitivity_max_diff: 2e-6,        // > 1e-6 -> SENS-01 High
        stability_rel_change: 0.30,        // > 0.25 -> STAB-01 Medium
        backtest_zone: "red".to_string(),  // BT-01 High
        plat_zone: "amber".to_string(),    // PLAT-02 Medium
        stale_days: 16,                    // > 15 -> DQ-01 Medium
        gaps: 3,                           // > 0 -> DQ-02 Low
    };
    let f = classify_findings(&inputs);
    let ids: Vec<&str> = f.iter().map(|x| x.rule_id.as_str()).collect();
    assert_eq!(ids, ["BENCH-01", "SENS-01", "BT-01", "PLAT-02", "STAB-01", "DQ-01", "DQ-02"]);
    assert_eq!(overall_verdict(&f), "reject");

    // Amber backtest fires BT-02 (Medium) instead of BT-01.
    let mut amber = clean_inputs();
    amber.backtest_zone = "amber".to_string();
    let f2 = classify_findings(&amber);
    assert_eq!(f2.len(), 1);
    assert_eq!((f2[0].rule_id.as_str(), f2[0].severity.as_str()), ("BT-02", "Medium"));
    assert_eq!(overall_verdict(&f2), "approve-with-conditions");

    // PLAT red fires PLAT-01 High.
    let mut plat_red = clean_inputs();
    plat_red.plat_zone = "red".to_string();
    assert_eq!(overall_verdict(&classify_findings(&plat_red)), "reject");
}

#[test]
fn findings_thresholds_are_strict() {
    // Exactly at the threshold -> no finding (all comparisons strict >).
    let mut at = clean_inputs();
    at.benchmark_max_diff = BENCH_TOL;
    at.sensitivity_max_diff = SENS_TOL;
    at.stability_rel_change = 0.25;
    at.stale_days = 15;
    assert!(classify_findings(&at).is_empty());
}

#[test]
fn low_only_findings_still_approve() {
    let mut gaps_only = clean_inputs();
    gaps_only.gaps = 1;
    let f = classify_findings(&gaps_only);
    assert_eq!(f.len(), 1);
    assert_eq!(f[0].severity, "Low");
    assert_eq!(overall_verdict(&f), "approve");
}

#[test]
fn verdict_precedence() {
    let mk = |sev: &str| Finding {
        rule_id: "X".to_string(),
        severity: sev.to_string(),
        description: String::new(),
    };
    assert_eq!(overall_verdict(&[]), "approve");
    assert_eq!(overall_verdict(&[mk("Low")]), "approve");
    assert_eq!(overall_verdict(&[mk("Low"), mk("Medium")]), "approve-with-conditions");
    assert_eq!(overall_verdict(&[mk("Medium"), mk("High")]), "reject");
}

#[test]
fn data_quality_counts_staleness_and_gaps() {
    // Two zero-change transitions among the clean values, two NaN gaps.
    let series = [1.0, 1.0, 2.0, f64::NAN, 2.0, 2.0, 3.0, f64::NAN];
    let dq = data_quality(&series).unwrap();
    assert_eq!(dq.gaps, 2);
    // clean = [1,1,2,2,2,3] -> zero-change transitions: (1,1), (2,2), (2,2).
    assert_eq!(dq.stale_days, 3);
    assert!(data_quality(&[1.0]).is_err());
}

#[test]
fn benchmark_and_sensitivity_checks_pass() {
    let bench = benchmark_max_diff().unwrap();
    assert!(bench > 0.0 && bench <= BENCH_TOL, "benchmark diff {bench}");
    let sens = sensitivity_max_diff().unwrap();
    assert!(sens <= SENS_TOL, "sensitivity diff {sens}");
}

#[test]
fn report_contains_all_sections_and_key_content() {
    let res = common::results();
    let report = &res.validation.report_md;
    assert_eq!(REPORT_SECTIONS.len(), 10);
    for section in REPORT_SECTIONS {
        let heading = format!("## {section}");
        assert!(report.contains(&heading), "missing section '{heading}'");
    }
    // Key content: disclaimer, desks, zones, verdicts and findings.
    assert!(report.contains("# Independent Model Validation Report"));
    assert!(report.contains("NOT a compliant capital engine"));
    assert!(report.contains("desk1") && report.contains("desk2"));
    assert!(report.contains("PASS"));
    assert!(report.contains("| desk1 | 2 | green | 1.50 |"));
    assert!(report.contains("| desk2 | 5 | amber | 1.70 |"));
    assert!(report.contains("[BT-02] (desk2)"));
    assert!(report.contains("[PLAT-02] (desk2)"));
    assert!(report.contains("- desk1: **approve**"));
    assert!(report.contains("- desk2: **approve-with-conditions**"));
}

#[test]
fn bundled_desk_findings_and_verdicts() {
    let res = common::results();
    let v = &res.validation;
    assert!(v.findings["desk1"].is_empty());
    let ids: Vec<&str> = v.findings["desk2"].iter().map(|f| f.rule_id.as_str()).collect();
    assert_eq!(ids, ["BT-02", "PLAT-02"]);
    assert_eq!(v.verdicts["desk1"], "approve");
    assert_eq!(v.verdicts["desk2"], "approve-with-conditions");
    // Stability: firm capital moves but stays well inside the 25% band.
    assert!(v.stability_rel_change > 0.0 && v.stability_rel_change < 0.25);
    assert!(v.stability_capital_rw_up10 > v.stability_base_capital);
    assert!(v.stability_capital_rw_dn10 < v.stability_base_capital);
}

#[test]
fn engine_is_deterministic_no_runtime_rng() {
    // Two independent full runs produce bit-identical capital numbers and
    // byte-identical reports (there is no RNG anywhere at runtime; the
    // crate does not even depend on `rand`).
    let a = frtb::compute_results(&common::data_dir()).unwrap();
    let b = frtb::compute_results(&common::data_dir()).unwrap();
    assert_eq!(a.sa["firm"].sbm.capital.to_bits(), b.sa["firm"].sbm.capital.to_bits());
    assert_eq!(a.ima["desk2"].capital.to_bits(), b.ima["desk2"].capital.to_bits());
    assert_eq!(a.validation.report_md, b.validation.report_md);
    assert_eq!(a, b);
}

#[test]
fn params_error_paths() {
    let res = common::results();
    assert!(res.params.girr_rw(7.0).is_err()); // not a pinned tenor
    assert!(res.params.drc_rw("XX").is_err());
    assert!(res.params.with_girr_delta_rw_scaled(0.0).is_err());
    assert!(res.params.with_girr_delta_rw_scaled(f64::NAN).is_err());
    // Missing params key: loading a truncated file fails with a clear message.
    let dir = std::env::temp_dir().join("frtb_params_test");
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("bad_params.json");
    std::fs::write(&path, "{\"girr\": {}}").unwrap();
    let err = frtb::load_params(&path).unwrap_err();
    assert!(format!("{err}").contains("missing"), "{err}");
}
