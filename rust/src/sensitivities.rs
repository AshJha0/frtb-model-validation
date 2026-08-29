//! Bump-and-revalue sensitivities with pinned bump sizes.
//!
//! Pinned bumps (documented in `API_SPEC.md`):
//!
//! * GIRR delta:   +1bp absolute bump of one curve node; `s = (V+ - V) / 1e-4`
//!   (sensitivity is expressed per unit of rate, `dV/dr`).
//! * Equity delta: +1% relative spot bump; `s = (V+ - V) / 0.01`
//!   (i.e. `S * dV/dS`, the FRTB relative-shift convention).
//! * Equity vega:  +1 vol point absolute bump; `raw = (V+ - V) / 0.01`,
//!   WS uses `s = raw * sigma` (FRTB vega = vega * implied vol).
//! * FX delta:     +1% relative spot bump; `s = (V+ - V) / 0.01`.
//! * Curvature:    full risk-weight shock up/down (parallel for GIRR curves,
//!   relative for equity/FX spots) with the delta term stripped:
//!   `CVR+ = -(V_up - V - RW*s)`, `CVR- = -(V_dn - V + RW*s)`.

use std::collections::{BTreeMap, BTreeSet};

use crate::error::Result;
use crate::instruments::Instrument;
use crate::market::Market;
use crate::params::SbmParams;
use crate::pricers::price_portfolio;

/// 1bp absolute zero-rate bump.
pub const GIRR_BUMP: f64 = 1e-4;
/// 1% relative equity-spot bump.
pub const EQ_SPOT_BUMP: f64 = 0.01;
/// 1 vol point absolute bump.
pub const VOL_BUMP: f64 = 0.01;
/// 1% relative FX-spot bump.
pub const FX_BUMP: f64 = 0.01;
/// Sensitivities below this (absolute) are treated as zero.
const ZERO_TOL: f64 = 1e-9;

/// All raw (unweighted) sensitivities of one instrument scope.
///
/// * `girr`: per currency, the `dV/dr` ladder aligned with the pinned GIRR
///   tenors (currencies whose whole ladder is ~zero are excluded, but a
///   kept currency retains its zero entries).
/// * `equity_delta` / `equity_vega` / `fx_delta`: per factor, with
///   near-zero factors dropped.
/// * `*_cvr`: curvature `(CVR+, CVR-)` pairs per curvature factor.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct Sensitivities {
    /// {currency -> per-tenor dV/dr, aligned with `params.girr_tenors`}.
    pub girr: BTreeMap<String, Vec<f64>>,
    /// {equity name -> S * dV/dS}.
    pub equity_delta: BTreeMap<String, f64>,
    /// {equity name -> vega * sigma}.
    pub equity_vega: BTreeMap<String, f64>,
    /// {FX pair -> S * dV/dS}.
    pub fx_delta: BTreeMap<String, f64>,
    /// {currency -> (CVR+, CVR-)} for the parallel GIRR shock.
    pub girr_cvr: BTreeMap<String, (f64, f64)>,
    /// {equity name -> (CVR+, CVR-)} for the relative spot shock.
    pub equity_cvr: BTreeMap<String, (f64, f64)>,
    /// {FX pair -> (CVR+, CVR-)} for the relative spot shock.
    pub fx_cvr: BTreeMap<String, (f64, f64)>,
}

/// Full bump-and-revalue pass over one instrument scope (desk or firm).
///
/// Deterministic: pure revaluation under bumped market snapshots, no RNG.
/// An empty scope returns all-empty maps (capital 0 downstream).
pub fn compute_sensitivities(
    instruments: &[Instrument],
    market: &Market,
    params: &SbmParams,
) -> Result<Sensitivities> {
    let base = price_portfolio(instruments, market)?;

    // ---- GIRR delta: bump each curve node of each currency by 1bp -------
    let mut girr: BTreeMap<String, Vec<f64>> = BTreeMap::new();
    for ccy in market.curves.keys() {
        let mut per_tenor = Vec::with_capacity(params.girr_tenors.len());
        let mut any_nonzero = false;
        for &tenor in &params.girr_tenors {
            let bumped = market.bump_curve_node(ccy, tenor, GIRR_BUMP)?;
            let s = (price_portfolio(instruments, &bumped)? - base) / GIRR_BUMP;
            if s.abs() > ZERO_TOL {
                any_nonzero = true;
            }
            per_tenor.push(s);
        }
        if any_nonzero {
            girr.insert(ccy.clone(), per_tenor);
        }
    }

    // ---- Equity delta & vega --------------------------------------------
    let names: BTreeSet<String> = instruments
        .iter()
        .filter_map(|i| i.underlier().map(str::to_string))
        .collect();
    let mut equity_delta = BTreeMap::new();
    let mut equity_vega = BTreeMap::new();
    for name in &names {
        let vol = market.equity(name)?.vol;
        let up_spot = market.bump_equity_spot(name, EQ_SPOT_BUMP)?;
        let s_d = (price_portfolio(instruments, &up_spot)? - base) / EQ_SPOT_BUMP;
        let up_vol = market.bump_equity_vol(name, VOL_BUMP)?;
        let raw_vega = (price_portfolio(instruments, &up_vol)? - base) / VOL_BUMP;
        let s_v = raw_vega * vol;
        if s_d.abs() > ZERO_TOL {
            equity_delta.insert(name.clone(), s_d);
        }
        if s_v.abs() > ZERO_TOL {
            equity_vega.insert(name.clone(), s_v);
        }
    }

    // ---- FX delta --------------------------------------------------------
    let pairs: BTreeSet<String> = instruments
        .iter()
        .filter_map(|i| i.fx_pair().map(str::to_string))
        .collect();
    let mut fx_delta = BTreeMap::new();
    for pair in &pairs {
        let bumped = market.bump_fx(pair, FX_BUMP)?;
        let s = (price_portfolio(instruments, &bumped)? - base) / FX_BUMP;
        if s.abs() > ZERO_TOL {
            fx_delta.insert(pair.clone(), s);
        }
    }

    // ---- Curvature -------------------------------------------------------
    let mut girr_cvr = BTreeMap::new();
    let rw_c = params.girr_curvature_rw;
    for (ccy, ladder) in &girr {
        // Sum of delta sensitivities over tenors = the linear term stripped
        // from the full-RW parallel shock.
        let mut slope = 0.0;
        for &s in ladder {
            slope += s;
        }
        let v_up = price_portfolio(instruments, &market.bump_curve_parallel(ccy, rw_c)?)?;
        let v_dn = price_portfolio(instruments, &market.bump_curve_parallel(ccy, -rw_c)?)?;
        girr_cvr.insert(
            ccy.clone(),
            (-(v_up - base - rw_c * slope), -(v_dn - base + rw_c * slope)),
        );
    }

    let mut equity_cvr = BTreeMap::new();
    for name in &names {
        let Some(&s) = equity_delta.get(name) else { continue };
        let rw = params.equity_bucket(&market.equity(name)?.bucket)?.delta_rw;
        let v_up = price_portfolio(instruments, &market.bump_equity_spot(name, rw)?)?;
        let v_dn = price_portfolio(instruments, &market.bump_equity_spot(name, -rw)?)?;
        equity_cvr.insert(name.clone(), (-(v_up - base - rw * s), -(v_dn - base + rw * s)));
    }

    let mut fx_cvr = BTreeMap::new();
    for pair in &pairs {
        let Some(&s) = fx_delta.get(pair) else { continue };
        let rw = params.fx_delta_rw;
        let v_up = price_portfolio(instruments, &market.bump_fx(pair, rw)?)?;
        let v_dn = price_portfolio(instruments, &market.bump_fx(pair, -rw)?)?;
        fx_cvr.insert(pair.clone(), (-(v_up - base - rw * s), -(v_dn - base + rw * s)));
    }

    Ok(Sensitivities { girr, equity_delta, equity_vega, fx_delta, girr_cvr, equity_cvr, fx_cvr })
}
