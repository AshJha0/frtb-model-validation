//! P&L attribution test (PLAT): Spearman + KS metrics, traffic-light zone,
//! Amber capital surcharge.
//!
//! Pinned thresholds:
//!
//! * Green: `spearman >= 0.85` AND `KS <= 0.09`
//! * Red:   `spearman <  0.80` OR  `KS >  0.12`
//! * Amber: everything in between.
//!
//! Constant P&L on either side leaves the Spearman correlation undefined;
//! the desk is assigned RED in that case (documented conservative
//! convention).
//!
//! Amber surcharge (pinned k = 0.5 interpolation between IMA and SA):
//! `surcharge = k * max(0, SA_desk - IMA_desk_core)`.

use crate::error::{invalid, Result};
use crate::params::SbmParams;
use crate::stats::{ks_statistic, spearman};

/// PLAT outcome; metrics are `None` when undefined (constant series ->
/// Red).
#[derive(Debug, Clone, PartialEq)]
pub struct PlatResult {
    /// Spearman rank correlation (None when undefined).
    pub spearman: Option<f64>,
    /// Two-sample KS statistic (None when undefined).
    pub ks: Option<f64>,
    /// `"green"`, `"amber"` or `"red"`.
    pub zone: String,
}

/// Map (Spearman, KS) to a PLAT zone using the pinned thresholds.
pub fn plat_zone_from_metrics(
    spearman_rho: f64,
    ks_stat: f64,
    params: &SbmParams,
) -> Result<&'static str> {
    if !spearman_rho.is_finite() || !ks_stat.is_finite() {
        return invalid("plat_zone_from_metrics: metrics must be finite");
    }
    if spearman_rho < params.plat_spearman_amber || ks_stat > params.plat_ks_amber {
        return Ok("red");
    }
    if spearman_rho >= params.plat_spearman_green && ks_stat <= params.plat_ks_green {
        return Ok("green");
    }
    Ok("amber")
}

/// Run the PLAT on hypothetical vs risk-theoretical P&L.
///
/// A constant series on either side makes the rank correlation undefined:
/// the result is Red with metrics = None (documented edge case).
pub fn plat_test(hypo: &[f64], rtpl: &[f64], params: &SbmParams) -> Result<PlatResult> {
    if hypo.len() != rtpl.len() {
        return invalid(format!(
            "plat_test: series length mismatch ({} vs {})",
            hypo.len(),
            rtpl.len()
        ));
    }
    if hypo.len() < 3 {
        return invalid("plat_test: need at least 3 observations");
    }
    let rho = match spearman(hypo, rtpl) {
        Ok(r) => r,
        // Constant series -> correlation undefined -> Red (conservative).
        Err(_) => return Ok(PlatResult { spearman: None, ks: None, zone: "red".to_string() }),
    };
    let ks = ks_statistic(hypo, rtpl)?;
    let zone = plat_zone_from_metrics(rho, ks, params)?;
    Ok(PlatResult { spearman: Some(rho), ks: Some(ks), zone: zone.to_string() })
}

/// Amber-zone capital surcharge: `k * max(0, SA - IMA_core)`; 0 otherwise.
///
/// Red-zone desks fall back to SA entirely (handled by the caller/report);
/// the surcharge formula itself applies only to Amber.
pub fn plat_surcharge(
    zone: &str,
    sa_capital: f64,
    ima_capital_core: f64,
    params: &SbmParams,
) -> Result<f64> {
    if zone != "green" && zone != "amber" && zone != "red" {
        return invalid(format!("plat_surcharge: unknown zone '{zone}'"));
    }
    for (name, v) in [("sa_capital", sa_capital), ("ima_capital_core", ima_capital_core)] {
        if !v.is_finite() || v < 0.0 {
            return invalid(format!("plat_surcharge: {name} must be >= 0 and finite"));
        }
    }
    if zone != "amber" {
        return Ok(0.0);
    }
    Ok(params.plat_k_surcharge * (sa_capital - ima_capital_core).max(0.0))
}
