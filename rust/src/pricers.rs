//! Self-contained pricer kit: Black-Scholes (with edge cases), CRR binomial
//! benchmark, bond / swap-proxy / FX-forward pricing off zero curves.
//!
//! All prices are deterministic closed-form / lattice computations — no RNG
//! anywhere.
//!
//! ## Cross-language precision note
//!
//! The golden values were produced by the Python reference, whose
//! `math.erf` is the C library's `erf`. The golden tolerances (1e-8
//! absolute on capital numbers of magnitude 1e6, further amplified by
//! bump-and-revalue divided differences) require the normal CDF here to be
//! *bit-identical* to the reference, so [`norm_cdf`] calls the C library
//! `erf` directly instead of re-deriving its own approximation.

use crate::error::{invalid, Result};
use crate::instruments::{Bond, EquityOption, FxForward, Instrument, PayerSwap};
use crate::market::{Curve, Market};

extern "C" {
    /// C99 error function from the system math library — the same function
    /// CPython's `math.erf` wraps, giving bit-identical normal CDFs.
    fn erf(x: f64) -> f64;
}

/// Standard normal CDF via the C library `erf` (see the module docs for why
/// FFI is used instead of a local approximation).
pub fn norm_cdf(x: f64) -> f64 {
    // SAFETY: `erf` is a pure C99 libm function with no preconditions; it is
    // total on all finite and non-finite doubles.
    0.5 * (1.0 + unsafe { erf(x / std::f64::consts::SQRT_2) })
}

/// Standard normal PDF.
pub fn norm_pdf(x: f64) -> f64 {
    (-0.5 * x * x).exp() / (2.0 * std::f64::consts::PI).sqrt()
}

fn validate_bs(s: f64, k: f64, t: f64, sigma: f64) -> Result<()> {
    for (name, v) in [("spot", s), ("strike", k), ("maturity", t), ("sigma", sigma)] {
        if !v.is_finite() {
            return invalid(format!("black_scholes: {name} must be finite, got {v}"));
        }
    }
    if s <= 0.0 || k <= 0.0 {
        return invalid(format!("black_scholes: spot/strike must be positive (s={s}, k={k})"));
    }
    if t < 0.0 {
        return invalid(format!("black_scholes: maturity must be >= 0, got {t}"));
    }
    if sigma < 0.0 {
        return invalid(format!("black_scholes: sigma must be >= 0, got {sigma}"));
    }
    Ok(())
}

/// Black-Scholes price with continuous dividend yield `q`.
///
/// Edge cases: `t == 0` returns the intrinsic value; `sigma == 0` returns
/// the discounted deterministic payoff
/// `max(+/-(S e^{-qT} - K e^{-rT}), 0)`.
pub fn bs_price(s: f64, k: f64, t: f64, r: f64, q: f64, sigma: f64, call: bool) -> Result<f64> {
    validate_bs(s, k, t, sigma)?;
    let sign = if call { 1.0 } else { -1.0 };
    if t == 0.0 {
        return Ok((sign * (s - k)).max(0.0));
    }
    if sigma == 0.0 {
        return Ok((sign * (s * (-q * t).exp() - k * (-r * t).exp())).max(0.0));
    }
    let sq = sigma * t.sqrt();
    let d1 = ((s / k).ln() + (r - q + 0.5 * sigma * sigma) * t) / sq;
    let d2 = d1 - sq;
    Ok(sign
        * (s * (-q * t).exp() * norm_cdf(sign * d1) - k * (-r * t).exp() * norm_cdf(sign * d2)))
}

/// Analytic Black-Scholes spot delta `dV/dS` (used by the validation FD
/// check). For the deterministic edge cases (`t == 0` or `sigma == 0`) the
/// payoff delta is a step function; the almost-everywhere value is returned.
pub fn bs_delta(s: f64, k: f64, t: f64, r: f64, q: f64, sigma: f64, call: bool) -> Result<f64> {
    validate_bs(s, k, t, sigma)?;
    let sign = if call { 1.0 } else { -1.0 };
    if t == 0.0 || sigma == 0.0 {
        let fwd_itm =
            if t > 0.0 { s * (-q * t).exp() - k * (-r * t).exp() } else { s - k };
        let step = if sign * fwd_itm > 0.0 { 1.0 } else { 0.0 };
        return Ok((-q * t).exp() * step * sign);
    }
    let sq = sigma * t.sqrt();
    let d1 = ((s / k).ln() + (r - q + 0.5 * sigma * sigma) * t) / sq;
    Ok(sign * (-q * t).exp() * norm_cdf(sign * d1))
}

/// Analytic Black-Scholes vega `dV/dsigma` (same for call and put).
pub fn bs_vega(s: f64, k: f64, t: f64, r: f64, q: f64, sigma: f64) -> Result<f64> {
    validate_bs(s, k, t, sigma)?;
    if t == 0.0 || sigma == 0.0 {
        return Ok(0.0);
    }
    let sq = sigma * t.sqrt();
    let d1 = ((s / k).ln() + (r - q + 0.5 * sigma * sigma) * t) / sq;
    Ok(s * (-q * t).exp() * norm_pdf(d1) * t.sqrt())
}

/// European option price on a CRR (Cox-Ross-Rubinstein) lattice.
///
/// Used only as the independent benchmark pricer in the validation
/// framework (converges to Black-Scholes as `steps -> inf`).
#[allow(clippy::too_many_arguments)] // pinned cross-language signature
pub fn binomial_price(
    s: f64,
    k: f64,
    t: f64,
    r: f64,
    q: f64,
    sigma: f64,
    call: bool,
    steps: usize,
) -> Result<f64> {
    validate_bs(s, k, t, sigma)?;
    if steps < 1 {
        return invalid(format!("binomial_price: steps must be >= 1, got {steps}"));
    }
    if t == 0.0 || sigma == 0.0 {
        return bs_price(s, k, t, r, q, sigma, call);
    }
    let dt = t / steps as f64;
    let u = (sigma * dt.sqrt()).exp();
    let d = 1.0 / u;
    let disc = (-r * dt).exp();
    let p = (((r - q) * dt).exp() - d) / (u - d);
    if !(0.0 < p && p < 1.0) {
        return invalid("binomial_price: risk-neutral probability outside (0,1); increase steps");
    }
    // Terminal payoffs on the lattice: S_j = s * u^(2j - steps).
    let mut v: Vec<f64> = (0..=steps)
        .map(|j| {
            let st = s * u.powf(2.0 * j as f64 - steps as f64);
            if call { (st - k).max(0.0) } else { (k - st).max(0.0) }
        })
        .collect();
    // Backward induction, mirroring the reference's vectorised recursion
    // v = disc * (p * v[1:] + (1 - p) * v[:-1]) term by term.
    for step in 0..steps {
        let m = steps - step; // v currently has m + 1 live entries
        for i in 0..m {
            v[i] = disc * (p * v[i + 1] + (1.0 - p) * v[i]);
        }
    }
    Ok(v[0])
}

/// Dirty PV of an annual-pay bullet bond: `sum c*N*DF(t_i) + N*DF(T)`.
pub fn price_bond(bond: &Bond, curve: &Curve) -> Result<f64> {
    let mut pv = bond.notional * curve.df(bond.maturity)?;
    for ti in bond.coupon_times() {
        pv += bond.coupon * bond.notional * curve.df(ti)?;
    }
    Ok(pv)
}

/// Payer swap proxy: `V = N*(1 - DF(T)) - c*N*sum_i DF(t_i)`.
pub fn price_payer_swap(swap: &PayerSwap, curve: &Curve) -> Result<f64> {
    let mut annuity = 0.0;
    for ti in swap.fixed_times() {
        annuity += curve.df(ti)?;
    }
    Ok(swap.notional * (1.0 - curve.df(swap.maturity)?)
        - swap.fixed_rate * swap.notional * annuity)
}

/// FX forward value in domestic ccy: `N * (S*DF_for(T) - K*DF_dom(T))`.
pub fn price_fx_forward(
    fwd: &FxForward,
    spot: f64,
    curve_dom: &Curve,
    curve_for: &Curve,
) -> Result<f64> {
    if spot <= 0.0 || !spot.is_finite() {
        return invalid(format!("price_fx_forward: spot must be positive, got {spot}"));
    }
    Ok(fwd.notional
        * (spot * curve_for.df(fwd.maturity)? - fwd.strike * curve_dom.df(fwd.maturity)?))
}

/// Position value of a European equity option (BS with dividend yield).
pub fn price_equity_option(opt: &EquityOption, market: &Market) -> Result<f64> {
    let quote = market.equity(&opt.underlier)?;
    let r = if opt.maturity > 0.0 { market.curve(&opt.currency)?.rate(opt.maturity)? } else { 0.0 };
    let px = bs_price(
        quote.spot,
        opt.strike,
        opt.maturity,
        r,
        quote.div_yield,
        quote.vol,
        opt.option_type == "call",
    )?;
    Ok(f64::from(opt.position) * opt.contracts * px)
}

/// Dispatch: PV of a single instrument under the given market snapshot.
pub fn price_instrument(inst: &Instrument, market: &Market) -> Result<f64> {
    match inst {
        Instrument::Bond(b) => price_bond(b, market.curve(&b.currency)?),
        Instrument::PayerSwap(s) => price_payer_swap(s, market.curve(&s.currency)?),
        Instrument::EquityOption(o) => price_equity_option(o, market),
        Instrument::FxForward(f) => price_fx_forward(
            f,
            market.fx_spot(&f.pair)?,
            market.curve(f.domestic())?,
            market.curve(f.foreign())?,
        ),
    }
}

/// Sum of instrument PVs in book order (0.0 for an empty portfolio).
pub fn price_portfolio(instruments: &[Instrument], market: &Market) -> Result<f64> {
    let mut total = 0.0;
    for inst in instruments {
        total += price_instrument(inst, market)?;
    }
    Ok(total)
}
