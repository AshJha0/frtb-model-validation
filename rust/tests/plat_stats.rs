//! Native statistics (ranks, Pearson/Spearman, KS) and PLAT zone /
//! surcharge tests, including the pinned threshold boundaries.

mod common;

use frtb::stats::{average_ranks, ks_statistic, pearson, spearman};
use frtb::{plat_surcharge, plat_test, plat_zone_from_metrics};

fn params() -> &'static frtb::SbmParams {
    &common::results().params
}

#[test]
fn average_ranks_handle_ties() {
    assert_eq!(average_ranks(&[10.0, 20.0, 30.0]), vec![1.0, 2.0, 3.0]);
    // Tied block gets the average rank: values {1, 2, 2, 3} -> {1, 2.5, 2.5, 4}.
    assert_eq!(average_ranks(&[1.0, 2.0, 2.0, 3.0]), vec![1.0, 2.5, 2.5, 4.0]);
    assert_eq!(average_ranks(&[5.0, 5.0]), vec![1.5, 1.5]);
}

#[test]
fn spearman_known_values() {
    let x = [1.0, 2.0, 3.0, 4.0, 5.0];
    let up = [2.0, 4.0, 6.0, 8.0, 10.0];
    let dn = [10.0, 8.0, 6.0, 4.0, 2.0];
    assert_close!(spearman(&x, &up).unwrap(), 1.0, 1e-15);
    assert_close!(spearman(&x, &dn).unwrap(), -1.0, 1e-15);
    // Monotone transforms preserve rank correlation exactly.
    let exp: Vec<f64> = x.iter().map(|v| v.exp()).collect();
    assert_close!(spearman(&x, &exp).unwrap(), 1.0, 1e-15);
}

#[test]
fn pearson_and_spearman_errors() {
    assert!(pearson(&[1.0, 1.0, 1.0], &[1.0, 2.0, 3.0]).is_err()); // constant
    assert!(pearson(&[1.0, 2.0], &[1.0]).is_err()); // length mismatch
    assert!(spearman(&[1.0, 2.0], &[1.0, 2.0]).is_err()); // < 3 observations
    assert!(spearman(&[1.0, f64::NAN, 2.0], &[1.0, 2.0, 3.0]).is_err()); // non-finite
}

#[test]
fn ks_statistic_known_values() {
    // Identical samples -> 0; disjoint samples -> 1.
    assert_eq!(ks_statistic(&[1.0, 2.0, 3.0], &[1.0, 2.0, 3.0]).unwrap(), 0.0);
    assert_eq!(ks_statistic(&[1.0, 2.0], &[10.0, 20.0]).unwrap(), 1.0);
    // Hand case: x = {1,2,3,4}, y = {3,4,5,6}: sup gap = 1/2 at t in [2,3).
    assert_close!(
        ks_statistic(&[1.0, 2.0, 3.0, 4.0], &[3.0, 4.0, 5.0, 6.0]).unwrap(),
        0.5,
        1e-15
    );
    assert!(ks_statistic(&[], &[1.0]).is_err());
    assert!(ks_statistic(&[1.0], &[f64::NAN]).is_err());
}

#[test]
fn plat_zone_threshold_boundaries() {
    let p = params();
    // Green requires spearman >= 0.85 AND ks <= 0.09 (inclusive bounds).
    assert_eq!(plat_zone_from_metrics(0.85, 0.09, p).unwrap(), "green");
    assert_eq!(plat_zone_from_metrics(0.9, 0.05, p).unwrap(), "green");
    // Just inside amber on each metric.
    assert_eq!(plat_zone_from_metrics(0.8499999, 0.09, p).unwrap(), "amber");
    assert_eq!(plat_zone_from_metrics(0.85, 0.0900001, p).unwrap(), "amber");
    // Red boundaries are strict: spearman < 0.80 OR ks > 0.12.
    assert_eq!(plat_zone_from_metrics(0.80, 0.12, p).unwrap(), "amber");
    assert_eq!(plat_zone_from_metrics(0.7999999, 0.05, p).unwrap(), "red");
    assert_eq!(plat_zone_from_metrics(0.95, 0.1200001, p).unwrap(), "red");
    assert!(plat_zone_from_metrics(f64::NAN, 0.05, p).is_err());
}

#[test]
fn plat_constant_series_is_red_with_null_metrics() {
    let p = params();
    let constant = vec![5.0; 10];
    let moving: Vec<f64> = (0..10).map(|i| i as f64).collect();
    let res = plat_test(&constant, &moving, p).unwrap();
    assert_eq!(res.zone, "red");
    assert!(res.spearman.is_none() && res.ks.is_none());
    // Constant on the other side too.
    let res2 = plat_test(&moving, &constant, p).unwrap();
    assert_eq!(res2.zone, "red");
    // Validation errors.
    assert!(plat_test(&moving, &moving[..5], p).is_err());
    assert!(plat_test(&[1.0, 2.0], &[1.0, 2.0], p).is_err());
}

#[test]
fn plat_test_green_case() {
    let p = params();
    let hypo: Vec<f64> = (0..260).map(|i| ((i * 17 % 97) as f64) - 48.0).collect();
    let res = plat_test(&hypo, &hypo, p).unwrap();
    assert_eq!(res.zone, "green");
    assert_close!(res.spearman.unwrap(), 1.0, 1e-12);
    assert_eq!(res.ks.unwrap(), 0.0);
}

#[test]
fn plat_surcharge_rules() {
    let p = params();
    // Amber: k * max(0, SA - IMA core) with k = 0.5.
    assert_close!(plat_surcharge("amber", 1000.0, 400.0, p).unwrap(), 300.0, 1e-12);
    // IMA above SA -> floored at 0.
    assert_eq!(plat_surcharge("amber", 400.0, 1000.0, p).unwrap(), 0.0);
    // Green and red pay no surcharge.
    assert_eq!(plat_surcharge("green", 1000.0, 400.0, p).unwrap(), 0.0);
    assert_eq!(plat_surcharge("red", 1000.0, 400.0, p).unwrap(), 0.0);
    // Unknown zone / invalid capitals are errors.
    assert!(plat_surcharge("blue", 1.0, 1.0, p).is_err());
    assert!(plat_surcharge("amber", -1.0, 1.0, p).is_err());
}
