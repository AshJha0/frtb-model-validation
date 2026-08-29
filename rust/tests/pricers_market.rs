//! Pricer and market tests: put-call parity, BS edge cases, finite
//! difference Greek checks, binomial convergence, curve interpolation and
//! error paths.

mod common;

use frtb::instruments::{Bond, FxForward, PayerSwap};
use frtb::{binomial_price, bs_delta, bs_price, bs_vega, norm_cdf, Curve};

#[test]
fn put_call_parity_grid() {
    // Property-style loop: C - P = S e^{-qT} - K e^{-rT} over a grid.
    let (s, r, q, sigma) = (100.0, 0.03, 0.01, 0.2);
    for k in [70.0, 85.0, 100.0, 115.0, 130.0] {
        for t in [0.25, 0.5, 1.0, 2.0] {
            let c = bs_price(s, k, t, r, q, sigma, true).unwrap();
            let p = bs_price(s, k, t, r, q, sigma, false).unwrap();
            let fwd = s * (-q * t).exp() - k * (-r * t).exp();
            assert_close!(c - p, fwd, 1e-10, format!("parity K={k} T={t}"));
        }
    }
}

#[test]
fn bs_edge_cases() {
    // T = 0 -> intrinsic value.
    assert_eq!(bs_price(110.0, 100.0, 0.0, 0.05, 0.0, 0.2, true).unwrap(), 10.0);
    assert_eq!(bs_price(90.0, 100.0, 0.0, 0.05, 0.0, 0.2, true).unwrap(), 0.0);
    assert_eq!(bs_price(90.0, 100.0, 0.0, 0.05, 0.0, 0.2, false).unwrap(), 10.0);
    // sigma = 0 -> discounted deterministic payoff.
    let v = bs_price(100.0, 90.0, 1.0, 0.05, 0.01, 0.0, true).unwrap();
    let want = 100.0 * (-0.01f64).exp() - 90.0 * (-0.05f64).exp();
    assert_close!(v, want, 1e-12);
    // Deep OTM zero-vol put is worthless.
    assert_eq!(bs_price(100.0, 50.0, 1.0, 0.05, 0.0, 0.0, false).unwrap(), 0.0);
}

#[test]
fn bs_delta_matches_finite_difference() {
    let (s, r, q, sigma) = (100.0, 0.03, 0.01, 0.25);
    let h = 1e-4 * s;
    for k in [80.0, 100.0, 120.0] {
        for t in [0.5, 1.0, 2.0] {
            for call in [true, false] {
                let analytic = bs_delta(s, k, t, r, q, sigma, call).unwrap();
                let up = bs_price(s + h, k, t, r, q, sigma, call).unwrap();
                let dn = bs_price(s - h, k, t, r, q, sigma, call).unwrap();
                assert_close!(analytic, (up - dn) / (2.0 * h), 1e-6);
            }
        }
    }
}

#[test]
fn bs_vega_matches_finite_difference() {
    let (s, k, t, r, q, sigma) = (100.0, 105.0, 1.0, 0.03, 0.01, 0.25);
    let h = 1e-5;
    let analytic = bs_vega(s, k, t, r, q, sigma).unwrap();
    let up = bs_price(s, k, t, r, q, sigma + h, true).unwrap();
    let dn = bs_price(s, k, t, r, q, sigma - h, true).unwrap();
    assert_close!(analytic, (up - dn) / (2.0 * h), 1e-6);
    // Vega is identical for call and put and zero at expiry.
    assert_eq!(bs_vega(s, k, 0.0, r, q, sigma).unwrap(), 0.0);
}

#[test]
fn binomial_converges_to_black_scholes() {
    let (s, r, q, sigma) = (100.0, 0.03, 0.01, 0.2);
    for k in [85.0, 100.0, 115.0] {
        for call in [true, false] {
            let bs = bs_price(s, k, 1.0, r, q, sigma, call).unwrap();
            let bin = binomial_price(s, k, 1.0, r, q, sigma, call, 501).unwrap();
            assert_close!(bs, bin, 0.05, format!("binomial K={k} call={call}"));
        }
    }
    // Edge cases delegate to BS.
    assert_eq!(
        binomial_price(110.0, 100.0, 0.0, 0.05, 0.0, 0.2, true, 100).unwrap(),
        10.0
    );
}

#[test]
fn pricer_invalid_inputs() {
    assert!(bs_price(-1.0, 100.0, 1.0, 0.0, 0.0, 0.2, true).is_err());
    assert!(bs_price(100.0, 0.0, 1.0, 0.0, 0.0, 0.2, true).is_err());
    assert!(bs_price(100.0, 100.0, -1.0, 0.0, 0.0, 0.2, true).is_err());
    assert!(bs_price(100.0, 100.0, 1.0, 0.0, 0.0, -0.2, true).is_err());
    assert!(bs_price(f64::NAN, 100.0, 1.0, 0.0, 0.0, 0.2, true).is_err());
    assert!(binomial_price(100.0, 100.0, 1.0, 0.0, 0.0, 0.2, true, 0).is_err());
}

#[test]
fn bond_and_swap_pricing_hand_values() {
    // Flat 3% curve: a 1y 5% bullet bond is worth 105 * exp(-0.03).
    let curve = Curve::new(vec![1.0, 5.0], vec![0.03, 0.03]).unwrap();
    let bond = Bond {
        inst_id: "B".into(),
        notional: 100.0,
        coupon: 0.05,
        maturity: 1.0,
        currency: "USD".into(),
        issuer: "X".into(),
        rating: "AAA".into(),
        lgd: 0.75,
        rrao: None,
    };
    let pv = frtb::price_bond(&bond, &curve).unwrap();
    assert_close!(pv, 105.0 * (-0.03f64).exp(), 1e-12);

    // Payer swap: V = N*(1 - DF(2)) - c*N*(DF(1) + DF(2)) on the flat curve.
    let swap = PayerSwap {
        inst_id: "S".into(),
        notional: 100.0,
        fixed_rate: 0.03,
        maturity: 2.0,
        currency: "USD".into(),
        rrao: None,
    };
    let df1 = (-0.03f64 * 1.0).exp();
    let df2 = (-0.03f64 * 2.0).exp();
    let want = 100.0 * (1.0 - df2) - 0.03 * 100.0 * (df1 + df2);
    assert_close!(frtb::price_payer_swap(&swap, &curve).unwrap(), want, 1e-12);
}

#[test]
fn fx_forward_hand_value() {
    let dom = Curve::new(vec![1.0], vec![0.03]).unwrap();
    let fore = Curve::new(vec![1.0], vec![0.02]).unwrap();
    let fwd = FxForward {
        inst_id: "F".into(),
        pair: "EURUSD".into(),
        notional: 1_000_000.0,
        strike: 1.10,
        maturity: 1.0,
        rrao: None,
    };
    let want = 1_000_000.0 * (1.08 * (-0.02f64).exp() - 1.10 * (-0.03f64).exp());
    assert_close!(frtb::price_fx_forward(&fwd, 1.08, &dom, &fore).unwrap(), want, 1e-9);
    assert!(frtb::price_fx_forward(&fwd, -1.0, &dom, &fore).is_err());
    assert_eq!(fwd.foreign(), "EUR");
    assert_eq!(fwd.domestic(), "USD");
}

#[test]
fn curve_interpolation_and_errors() {
    let curve = Curve::new(vec![1.0, 2.0], vec![0.02, 0.04]).unwrap();
    // Linear midpoint, flat extrapolation on both sides, DF(0) = 1.
    assert_close!(curve.rate(1.5).unwrap(), 0.03, 1e-15);
    assert_eq!(curve.rate(0.5).unwrap(), 0.02);
    assert_eq!(curve.rate(10.0).unwrap(), 0.04);
    assert_eq!(curve.df(0.0).unwrap(), 1.0);
    assert!(curve.rate(-1.0).is_err());
    // Non-increasing tenors and bad values are rejected.
    assert!(Curve::new(vec![2.0, 1.0], vec![0.02, 0.04]).is_err());
    assert!(Curve::new(vec![1.0, 1.0], vec![0.02, 0.04]).is_err());
    assert!(Curve::new(vec![], vec![]).is_err());
    assert!(Curve::new(vec![-1.0], vec![0.02]).is_err());
    // Bumping a non-node tenor is an error; a node bump shifts one rate.
    assert!(curve.bumped_node(1.5, 1e-4).is_err());
    let bumped = curve.bumped_node(1.0, 1e-4).unwrap();
    assert_close!(bumped.rate(1.0).unwrap(), 0.0201, 1e-15);
    assert_eq!(bumped.rate(2.0).unwrap(), 0.04);
    let par = curve.bumped_parallel(0.01).unwrap();
    assert_close!(par.rate(2.0).unwrap(), 0.05, 1e-15);
}

#[test]
fn market_lookups_error_on_missing_keys() {
    let res = common::results();
    assert!(res.market.curve("JPY").is_err());
    assert!(res.market.equity("NO_SUCH_NAME").is_err());
    assert!(res.market.fx_spot("GBPUSD").is_err());
    assert!(res.market.bump_curve_node("USD", 7.0, 1e-4).is_err()); // not a node
}

#[test]
fn norm_cdf_reference_values() {
    assert_eq!(norm_cdf(0.0), 0.5);
    // High-precision reference values (Abramowitz & Stegun grade).
    assert_close!(norm_cdf(1.0), 0.8413447460685429, 1e-15);
    assert_close!(norm_cdf(-1.0), 0.15865525393145705, 1e-15);
    assert_close!(norm_cdf(1.959963984540054), 0.975, 1e-12);
}
