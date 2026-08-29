//! IMA sketch tests (ES, LH ladder, backtesting, SES) and DRC/RRAO tests.

mod common;

use std::collections::BTreeMap;

use frtb::sa::{drc_charge, rrao_charge, DrcPosition};
use frtb::{
    backtest, backtest_multiplier, backtest_zone, es_base_10d, es_lh_scaled,
    expected_shortfall_daily, ima_capital, imcc, ses, NmrfEntry,
};

fn params() -> &'static frtb::SbmParams {
    &common::results().params
}

#[test]
fn es_daily_hand_case() {
    // n = 40, alpha = 0.975 -> k = 1: ES = the single worst loss.
    let mut pnl = vec![1.0; 39];
    pnl.push(-25.0);
    assert_close!(expected_shortfall_daily(&pnl, 0.975).unwrap(), 25.0, 1e-12);
    // n = 260 -> k = 7 (the Basel case used by the bundled data).
    let mut pnl260: Vec<f64> = (0..260).map(|i| -(i as f64)).collect();
    pnl260.reverse();
    // Worst 7 losses are 259..253 -> mean = 256.
    assert_close!(expected_shortfall_daily(&pnl260, 0.975).unwrap(), 256.0, 1e-12);
    assert_close!(es_base_10d(&pnl260, 0.975).unwrap(), 256.0 * 10.0f64.sqrt(), 1e-9);
}

#[test]
fn es_errors() {
    assert!(expected_shortfall_daily(&[], 0.975).is_err());
    assert!(expected_shortfall_daily(&[1.0], 1.5).is_err());
    assert!(expected_shortfall_daily(&[1.0, f64::NAN], 0.975).is_err());
}

#[test]
fn lh_ladder_monotone_and_single_category_collapse() {
    let p = params();
    let pnl: Vec<f64> = (0..260).map(|i| ((i * 37 % 101) as f64) - 50.0).collect();
    // Single category with LH 40 (fx): ES_LH = ES1 * sqrt(40/10) = 2 * ES1.
    let cats = vec![("fx".to_string(), pnl.clone())];
    let es1 = es_base_10d(&pnl, p.ima_alpha).unwrap();
    let es_lh = es_lh_scaled(&pnl, &cats, &p.category_lh, &p.lh_ladder, p.ima_alpha).unwrap();
    assert_close!(es_lh, 2.0 * es1, 1e-9);
    // Ladder is monotone: ES_LH >= base ES (also for the bundled desks).
    assert!(es_lh >= es1);
    let res = common::results();
    for d in ["desk1", "desk2"] {
        assert!(res.ima[d].es_lh >= res.ima[d].es_base, "{d} ladder not monotone");
    }
}

#[test]
fn lh_ladder_validation_errors() {
    let p = params();
    let pnl = vec![1.0, -2.0, 3.0];
    let cats = vec![("fx".to_string(), pnl.clone())];
    // Ladder must be strictly increasing and start at 10.
    assert!(es_lh_scaled(&pnl, &cats, &p.category_lh, &[10, 10, 20], p.ima_alpha).is_err());
    assert!(es_lh_scaled(&pnl, &cats, &p.category_lh, &[20, 40], p.ima_alpha).is_err());
    // Unknown category horizon.
    let bad_cat = vec![("mystery".to_string(), pnl.clone())];
    assert!(es_lh_scaled(&pnl, &bad_cat, &p.category_lh, &p.lh_ladder, p.ima_alpha).is_err());
    // Length mismatch.
    let short = vec![("fx".to_string(), vec![1.0, -2.0])];
    assert!(es_lh_scaled(&pnl, &short, &p.category_lh, &p.lh_ladder, p.ima_alpha).is_err());
    // Category columns must sum to the desk column.
    let off = vec![("fx".to_string(), vec![1.0, -2.0, 3.5])];
    let err = es_lh_scaled(&pnl, &off, &p.category_lh, &p.lh_ladder, p.ima_alpha).unwrap_err();
    assert!(format!("{err}").contains("does not sum"), "{err}");
}

#[test]
fn imcc_two_category_hand_case() {
    let p = params();
    // Two categories, both LH 20 (ir + eq): IMCC combines the full ladder
    // with the per-category ladders at rho = 0.5.
    let a: Vec<f64> = (0..260).map(|i| ((i * 13 % 29) as f64) - 14.0).collect();
    let b: Vec<f64> = (0..260).map(|i| ((i * 7 % 31) as f64) - 15.0).collect();
    let full: Vec<f64> = a.iter().zip(&b).map(|(x, y)| x + y).collect();
    let cats = vec![("ir".to_string(), a.clone()), ("eq".to_string(), b.clone())];
    let got = imcc(&full, &cats, p).unwrap();
    let es_full = es_lh_scaled(&full, &cats, &p.category_lh, &p.lh_ladder, p.ima_alpha).unwrap();
    let es_a = es_lh_scaled(&a, &cats[..1], &p.category_lh, &p.lh_ladder, p.ima_alpha)
        .unwrap();
    let es_b = es_lh_scaled(&b, &cats[1..], &p.category_lh, &p.lh_ladder, p.ima_alpha)
        .unwrap();
    assert_close!(got, 0.5 * es_full + 0.5 * (es_a + es_b), 1e-9);
}

#[test]
fn backtest_zone_and_multiplier_edges() {
    let p = params();
    // Zone boundaries.
    assert_eq!(backtest_zone(0), "green");
    assert_eq!(backtest_zone(4), "green");
    assert_eq!(backtest_zone(5), "amber");
    assert_eq!(backtest_zone(9), "amber");
    assert_eq!(backtest_zone(10), "red");
    assert_eq!(backtest_zone(13), "red");
    // Pinned multiplier mapping (4/5/9/10 edges + the > 12 cap).
    assert_eq!(backtest_multiplier(4, p).unwrap(), 1.5);
    assert_eq!(backtest_multiplier(5, p).unwrap(), 1.70);
    assert_eq!(backtest_multiplier(6, p).unwrap(), 1.75);
    assert_eq!(backtest_multiplier(7, p).unwrap(), 1.83);
    assert_eq!(backtest_multiplier(8, p).unwrap(), 1.88);
    assert_eq!(backtest_multiplier(9, p).unwrap(), 1.92);
    assert_eq!(backtest_multiplier(10, p).unwrap(), 2.0);
    assert_eq!(backtest_multiplier(42, p).unwrap(), 2.0); // > 12 stays capped
}

#[test]
fn backtest_counts_strict_exceptions() {
    let p = params();
    // PnL == -VaR is NOT an exception (strict <).
    let pnl = [-100.0, -100.1, 50.0, -99.9];
    let var = [100.0, 100.0, 100.0, 100.0];
    let bt = backtest(&pnl, &var, p).unwrap();
    assert_eq!(bt.exceptions, 1);
    assert_eq!(bt.zone, "green");
    assert_eq!(bt.multiplier, 1.5);
    // Errors: length mismatch, empty series, negative VaR.
    assert!(backtest(&pnl, &var[..3], p).is_err());
    assert!(backtest(&[], &[], p).is_err());
    assert!(backtest(&[1.0], &[-1.0], p).is_err());
}

#[test]
fn ses_sums_with_zero_diversification() {
    let entries = vec![
        NmrfEntry { factor: "a".into(), desk: "d".into(), stressed_loss: 35000.0 },
        NmrfEntry { factor: "b".into(), desk: "d".into(), stressed_loss: 40000.0 },
    ];
    assert_eq!(ses(&entries).unwrap(), 75000.0);
    assert_eq!(ses(&[]).unwrap(), 0.0);
    let bad = vec![NmrfEntry { factor: "x".into(), desk: "d".into(), stressed_loss: -1.0 }];
    assert!(ses(&bad).is_err());
}

#[test]
fn ima_capital_formula_and_validation() {
    assert_close!(ima_capital(100.0, 1.5, 20.0, 5.0).unwrap(), 175.0, 1e-12);
    assert!(ima_capital(-1.0, 1.5, 0.0, 0.0).is_err());
    assert!(ima_capital(1.0, 1.5, f64::INFINITY, 0.0).is_err());
}

#[test]
fn drc_netting_and_hbr_hand_case() {
    let p = params();
    // Same-issuer long/short nets first; cross-issuer shorts get HBR
    // weighting. JTD = LGD*N + (MV - N); with MV = N the JTD is LGD*N.
    let positions = vec![
        DrcPosition { issuer: "A".into(), rating: "BBB".into(), notional: 100.0, market_value: 100.0, lgd: 0.75 },
        DrcPosition { issuer: "A".into(), rating: "BBB".into(), notional: -40.0, market_value: -40.0, lgd: 0.75 },
        DrcPosition { issuer: "B".into(), rating: "A".into(), notional: -60.0, market_value: -60.0, lgd: 0.75 },
    ];
    let res = drc_charge(&positions, p).unwrap();
    // net JTD: A = 75 - 30 = 45, B = -45. HBR = 45 / (45 + 45) = 0.5.
    assert_close!(res.hbr, 0.5, 1e-15);
    // DRC = max(0, RW_BBB*45 - HBR * RW_A*45) = 2.7 - 0.675.
    let want = 0.06 * 45.0 - 0.5 * (0.03 * 45.0);
    assert_close!(res.charge, want, 1e-12);
    let net: BTreeMap<_, _> = res.net_jtd.iter().cloned().collect();
    assert_close!(net["A"], 45.0, 1e-12);
    assert_close!(net["B"], -45.0, 1e-12);
}

#[test]
fn drc_all_long_hbr_one_and_floor() {
    let p = params();
    let all_long = vec![
        DrcPosition { issuer: "A".into(), rating: "AAA".into(), notional: 100.0, market_value: 100.0, lgd: 0.75 },
        DrcPosition { issuer: "B".into(), rating: "A".into(), notional: 50.0, market_value: 50.0, lgd: 0.75 },
    ];
    let res = drc_charge(&all_long, p).unwrap();
    assert_eq!(res.hbr, 1.0); // no net shorts -> HBR = 1 (edge case)
    assert_close!(res.charge, 0.005 * 75.0 + 0.03 * 37.5, 1e-12);
    // Empty book: HBR 1, charge 0.
    let empty = drc_charge(&[], p).unwrap();
    assert_eq!((empty.hbr, empty.charge), (1.0, 0.0));
    // Net-short book floors at 0 via max(0, .).
    let all_short = vec![DrcPosition {
        issuer: "A".into(), rating: "B".into(), notional: -100.0, market_value: -100.0, lgd: 0.75,
    }];
    assert_eq!(drc_charge(&all_short, p).unwrap().charge, 0.0);
}

#[test]
fn drc_error_paths() {
    let p = params();
    // Unknown rating.
    let unknown = vec![DrcPosition {
        issuer: "A".into(), rating: "ZZ".into(), notional: 100.0, market_value: 100.0, lgd: 0.75,
    }];
    assert!(drc_charge(&unknown, p).is_err());
    // One issuer with two ratings.
    let inconsistent = vec![
        DrcPosition { issuer: "A".into(), rating: "AAA".into(), notional: 100.0, market_value: 100.0, lgd: 0.75 },
        DrcPosition { issuer: "A".into(), rating: "BB".into(), notional: 50.0, market_value: 50.0, lgd: 0.75 },
    ];
    let err = drc_charge(&inconsistent, p).unwrap_err();
    assert!(format!("{err}").contains("inconsistent ratings"), "{err}");
}

#[test]
fn rrao_rates_and_unknown_category() {
    let res = common::results();
    // Firm RRAO = 0.1% * 20m (swap) + 1% * 7.8m (exotic option) + 0.1% * 5m.
    assert_close!(res.sa["firm"].rrao, 103_000.0, 1e-12);
    // Unknown category errors at lookup.
    assert!(res.params.rrao_rate("weird").is_err());
    // No flags -> zero.
    assert_eq!(rrao_charge(&[], &res.params).unwrap(), 0.0);
}

#[test]
fn empty_desk_full_stack_is_zero() {
    let res = common::results();
    let sa = frtb::compute_sa(&[], &res.market, &res.params).unwrap();
    assert_eq!(sa.sbm.capital, 0.0);
    assert_eq!(sa.drc, 0.0);
    assert_eq!(sa.drc_hbr, 1.0);
    assert_eq!(sa.rrao, 0.0);
    assert_eq!(sa.capital(), 0.0);
}
