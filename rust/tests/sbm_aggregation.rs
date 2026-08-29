//! SBM aggregation tests: hand-computable examples, scenario scaling caps,
//! the S_b fallback branch, curvature psi behavior and error paths.

mod common;

use std::collections::BTreeMap;

use frtb::sbm::{
    aggregate_buckets, bucket_kb, curvature_bucket_kb, delta_vega_charge, psi, scale_rho,
    Scenario,
};
use frtb::{compute_sensitivities, sbm_capital, Sensitivities};

fn map2(a: (&str, f64), b: (&str, f64)) -> BTreeMap<String, f64> {
    BTreeMap::from([(a.0.to_string(), a.1), (b.0.to_string(), b.1)])
}

#[test]
fn hand_two_bucket_aggregation() {
    // Bucket A: WS = {10, -5}, rho = 0.5 -> K_A = sqrt(100 + 25 - 50).
    let k_a = bucket_kb(&[10.0, -5.0], |_, _| Ok(0.5)).unwrap();
    assert_close!(k_a, 75.0f64.sqrt(), 1e-12);
    // Bucket B: single factor -> K_B = |WS|.
    let k_b = bucket_kb(&[8.0], |_, _| Ok(0.0)).unwrap();
    assert_close!(k_b, 8.0, 1e-12);
    // Across: sqrt(K_A^2 + K_B^2 + 2*gamma*S_A*S_B), S_A = 5, S_B = 8.
    let agg = aggregate_buckets(&map2(("A", k_a), ("B", k_b)), &map2(("A", 5.0), ("B", 8.0)), |_, _| {
        Ok(0.25)
    })
    .unwrap();
    let want = (75.0 + 64.0 + 2.0 * 0.25 * 5.0 * 8.0f64).sqrt();
    assert_close!(agg.charge, want, 1e-12);
    assert!(!agg.used_fallback);
}

#[test]
fn scenario_scaling_and_cap() {
    // High is capped at 1: 1.25 * 0.9 > 1.
    assert_eq!(scale_rho(0.9, Scenario::High, 1.25, 0.75), 1.0);
    assert_close!(scale_rho(0.5, Scenario::High, 1.25, 0.75), 0.625, 1e-15);
    assert_eq!(scale_rho(0.5, Scenario::Medium, 1.25, 0.75), 0.5);
    assert_close!(scale_rho(0.5, Scenario::Low, 1.25, 0.75), 0.375, 1e-15);
    // Unknown scenario names are rejected at parse time.
    assert!(Scenario::parse("weird").is_err());
    assert_eq!(Scenario::parse("high").unwrap(), Scenario::High);
}

#[test]
fn high_scenario_cap_inside_bucket() {
    // rho = 0.9 scaled high -> capped at 1: K = sqrt(1 + 1 + 2*1*1*1) = 2.
    let ws = BTreeMap::from([(
        "B".to_string(),
        BTreeMap::from([("x".to_string(), 1.0), ("y".to_string(), 1.0)]),
    )]);
    let res = delta_vega_charge(&ws, |_, _, _| Ok(0.9), |_, _| Ok(0.0), Scenario::High, 1.25, 0.75)
        .unwrap();
    assert_close!(res.charge, 2.0, 1e-12);
}

#[test]
fn negative_rounding_guard_yields_zero() {
    // Perfectly offsetting factors with rho = 1: the quadratic form is 0
    // up to rounding; the max(0, .) guard keeps K_b at exactly 0.
    let kb = bucket_kb(&[3.0, -3.0], |_, _| Ok(1.0)).unwrap();
    assert_eq!(kb, 0.0);
    let kb2 = bucket_kb(&[3.0, -4.0], |_, _| Ok(1.0)).unwrap();
    assert_close!(kb2, 1.0, 1e-12);
    assert!(bucket_kb(&[f64::NAN], |_, _| Ok(0.0)).is_err());
}

#[test]
fn sb_fallback_branch_triggers() {
    // Same-sign factors with rho = 0 give |S_b| > K_b; opposite buckets with
    // a strong gamma push the aggregate quadratic form negative, forcing the
    // fallback S_b = max(min(sum WS, K_b), -K_b).
    let k = bucket_kb(&[10.0, 10.0], |_, _| Ok(0.0)).unwrap(); // sqrt(200)
    let kb = map2(("A", k), ("B", k));
    let ws_sum = map2(("A", 20.0), ("B", -20.0));
    // Plain S_b: 200 + 200 + 2*0.9*20*(-20) = -320 < 0 -> fallback.
    let agg = aggregate_buckets(&kb, &ws_sum, |_, _| Ok(0.9)).unwrap();
    assert!(agg.used_fallback);
    assert_close!(agg.sb["A"], 200.0f64.sqrt(), 1e-12);
    assert_close!(agg.sb["B"], -(200.0f64.sqrt()), 1e-12);
    // Fallback inner: 400 + 2*0.9*(-200) = 40.
    assert_close!(agg.charge, 40.0f64.sqrt(), 1e-12);
}

#[test]
fn aggregate_bucket_key_mismatch_errors() {
    let kb = map2(("A", 1.0), ("B", 1.0));
    let ws = BTreeMap::from([("A".to_string(), 1.0)]);
    assert!(aggregate_buckets(&kb, &ws, |_, _| Ok(0.0)).is_err());
}

#[test]
fn curvature_psi_and_side_selection() {
    assert_eq!(psi(-1.0, -1.0), 0.0);
    assert_eq!(psi(-1.0, 1.0), 1.0);
    assert_eq!(psi(1.0, -1.0), 1.0);
    assert_eq!(psi(1.0, 1.0), 1.0);
    assert_eq!(psi(0.0, -1.0), 1.0);

    // Up side: CVRs {3, -1}, rho 0.25 -> 9 + 2*0.25*3*(-1) = 7.5 (psi = 1);
    // down side: both negative -> psi = 0 and max(c,0)^2 = 0 -> K- = 0.
    let (k, s) = curvature_bucket_kb(&[3.0, -1.0], &[-2.0, -2.0], |_, _| Ok(0.25)).unwrap();
    assert_close!(k, 7.5f64.sqrt(), 1e-12);
    assert_close!(s, 2.0, 1e-12); // up-side CVR sum wins

    // Tie between sides -> the up side is selected (pinned convention).
    let (k_tie, s_tie) = curvature_bucket_kb(&[2.0], &[2.0], |_, _| Ok(0.0)).unwrap();
    assert_close!(k_tie, 2.0, 1e-12);
    assert_close!(s_tie, 2.0, 1e-12);

    // Mismatched up/down lengths are rejected.
    assert!(curvature_bucket_kb(&[1.0], &[1.0, 2.0], |_, _| Ok(0.0)).is_err());
}

#[test]
fn negative_gamma_exposure_curvature() {
    // A short-gamma book produces positive CVRs on both sides; a long-gamma
    // book produces negative CVRs on both sides -> both-negative psi kills
    // the cross term and max(CVR, 0) kills the own term.
    let (k_long, _) = curvature_bucket_kb(&[-5.0, -7.0], &[-6.0, -8.0], |_, _| Ok(0.5)).unwrap();
    assert_eq!(k_long, 0.0);
    let (k_short, s_short) =
        curvature_bucket_kb(&[5.0, 7.0], &[4.0, 6.0], |_, _| Ok(0.0)).unwrap();
    assert_close!(k_short, (25.0f64 + 49.0).sqrt(), 1e-12);
    assert_close!(s_short, 12.0, 1e-12);
}

#[test]
fn zero_sensitivities_give_zero_capital() {
    let res = common::results();
    // An empty scope (no instruments) has all-zero sensitivities and zero
    // SBM capital in every scenario.
    let sens = compute_sensitivities(&[], &res.market, &res.params).unwrap();
    assert_eq!(sens, Sensitivities::default());
    let sbm = sbm_capital(&sens, &res.market, &res.params).unwrap();
    assert_eq!(sbm.capital, 0.0);
    for scen in ["high", "medium", "low"] {
        assert_eq!(sbm.scenario_totals[scen], 0.0);
    }
}

#[test]
fn missing_bucket_param_is_an_error() {
    let res = common::results();
    let err = res.params.equity_bucket("99").unwrap_err();
    assert!(format!("{err}").contains("unknown equity bucket"), "{err}");
    // A market quote pointing at an unpinned bucket makes the SBM assembly
    // fail (spec edge case: missing bucket param -> error).
    let mut market = res.market.clone();
    let mut q = market.equities["AAA_TECH"].clone();
    q.bucket = "99".to_string();
    market.equities.insert("AAA_TECH".to_string(), q);
    let sens = compute_sensitivities(&res.desks["desk2"].instruments, &market, &res.params);
    assert!(sens.is_err());
}

#[test]
fn scenario_ordering_capital_is_max() {
    let res = common::results();
    let sbm = &res.sa["firm"].sbm;
    let max = sbm
        .scenario_totals
        .values()
        .fold(f64::NEG_INFINITY, |a, &b| if b > a { b } else { a });
    assert_eq!(sbm.capital, max);
    // For the bundled book the low scenario dominates (documented in the
    // golden data facts).
    assert_eq!(sbm.capital, sbm.scenario_totals["low"]);
}
