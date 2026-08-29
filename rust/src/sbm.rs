//! Sensitivities-Based Method (SBM) aggregation.
//!
//! Formulas (FRTB structure, educational parameter set):
//!
//! ```text
//! Weighted sensitivity   WS_k = RW_k * s_k
//! Within-bucket          K_b  = sqrt(max(0, sum_k WS_k^2
//!                               + sum_{k != l} rho_kl WS_k WS_l))
//! Across buckets         Charge = sqrt(max(0, sum_b K_b^2
//!                               + sum_{b != c} gamma_bc S_b S_c))
//! ```
//!
//! with `S_b = sum_k WS_k`; if the argument of the outer sqrt is negative
//! the S_b FALLBACK applies: `S_b = max(min(sum_k WS_k, K_b), -K_b)` and
//! the aggregate is recomputed (the `max(0, .)` guard is kept as
//! belt-and-braces against negative rounding).
//!
//! Correlation scenarios: for every rho and gamma,
//! high `rho -> min(1.25*rho, 1)`, medium `rho`, low `0.75*rho` (pinned
//! simplification of Basel's `max(2*rho - 1, 0.75*rho)`). The risk-class
//! charges are computed under each scenario, summed across risk classes,
//! and the SBM capital is the MAX of the three scenario totals.
//!
//! Curvature: per curvature risk factor `k` with delta sensitivity `s_k`,
//!
//! ```text
//! CVR_k+ = -( V(x_k up)   - V - RW_k^curv * s_k )
//! CVR_k- = -( V(x_k down) - V + RW_k^curv * s_k )
//! K_b+/- = sqrt(max(0, sum_k max(CVR_k,0)^2
//!               + sum_{k!=l} rho_kl CVR_k CVR_l psi(CVR_k, CVR_l)))
//! psi(a, b) = 0 if a < 0 and b < 0 else 1
//! K_b    = max(K_b+, K_b-), S_b = CVR sum of the winning side (tie -> up)
//! ```
//!
//! Across buckets curvature uses `gamma^2`, `psi(S_b, S_c)` and
//! `max(0, .)` inside the sqrt; there is no S_b fallback for curvature.
//! Curvature correlations are the squares of the (scenario-scaled) delta
//! correlations (pinned simplification, documented in `API_SPEC.md`).

use std::collections::BTreeMap;

use crate::error::{invalid, Result};

/// Correlation scenario applied to every rho and gamma.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Scenario {
    /// `min(1.25 * rho, 1.0)` — capped at 1.
    High,
    /// `rho` unchanged.
    Medium,
    /// `0.75 * rho` (pinned simplification).
    Low,
}

/// The three scenarios in the pinned evaluation order (high, medium, low).
pub const SCENARIOS: [Scenario; 3] = [Scenario::High, Scenario::Medium, Scenario::Low];

impl Scenario {
    /// Parse a scenario name; errors on anything but high/medium/low.
    pub fn parse(name: &str) -> Result<Scenario> {
        match name {
            "high" => Ok(Scenario::High),
            "medium" => Ok(Scenario::Medium),
            "low" => Ok(Scenario::Low),
            other => invalid(format!("Scenario: unknown scenario '{other}'")),
        }
    }

    /// Canonical lowercase name.
    pub fn name(self) -> &'static str {
        match self {
            Scenario::High => "high",
            Scenario::Medium => "medium",
            Scenario::Low => "low",
        }
    }
}

/// Apply the correlation scenario scaler; the high scenario is capped at 1.
pub fn scale_rho(rho: f64, scenario: Scenario, high: f64, low: f64) -> f64 {
    match scenario {
        Scenario::Medium => rho,
        Scenario::High => (high * rho).min(1.0),
        Scenario::Low => low * rho,
    }
}

/// Within-bucket charge
/// `K_b = sqrt(max(0, sum WS^2 + sum_{k!=l} rho WS_k WS_l))`.
///
/// `rho(k, l)` supplies the pairwise correlation for `k != l`. The
/// `max(0, .)` guard protects against negative rounding of the quadratic
/// form.
pub fn bucket_kb<R>(ws: &[f64], rho: R) -> Result<f64>
where
    R: Fn(usize, usize) -> Result<f64>,
{
    for &w in ws {
        if !w.is_finite() {
            return invalid("bucket_kb: weighted sensitivities must be finite");
        }
    }
    let mut total = 0.0;
    for &w in ws {
        total += w * w;
    }
    let n = ws.len();
    for k in 0..n {
        for l in 0..n {
            if k != l {
                total += rho(k, l)? * ws[k] * ws[l];
            }
        }
    }
    Ok(total.max(0.0).sqrt())
}

/// Across-bucket aggregation output (with the S_b fallback bookkeeping).
#[derive(Debug, Clone, PartialEq)]
pub struct AggregateResult {
    /// The across-bucket charge.
    pub charge: f64,
    /// Whether the S_b fallback branch was taken.
    pub used_fallback: bool,
    /// The S_b actually used (post-fallback if triggered).
    pub sb: BTreeMap<String, f64>,
}

/// Across-bucket aggregation with the FRTB S_b fallback rule.
///
/// First tries `S_b = sum_k WS_k`. If
/// `sum_b K_b^2 + sum_{b!=c} gamma S_b S_c < 0`, recomputes once with
/// `S_b = max(min(sum_k WS_k, K_b), -K_b)`.
pub fn aggregate_buckets<G>(
    kb: &BTreeMap<String, f64>,
    ws_sum: &BTreeMap<String, f64>,
    gamma: G,
) -> Result<AggregateResult>
where
    G: Fn(&str, &str) -> Result<f64>,
{
    if kb.len() != ws_sum.len() || kb.keys().any(|b| !ws_sum.contains_key(b)) {
        return invalid("aggregate_buckets: kb and ws_sum must cover the same buckets");
    }
    let buckets: Vec<&String> = kb.keys().collect();

    let inner = |sb: &BTreeMap<String, f64>| -> Result<f64> {
        let mut total = 0.0;
        for b in &buckets {
            total += kb[*b] * kb[*b];
        }
        for b in &buckets {
            for c in &buckets {
                if b != c {
                    total += gamma(b, c)? * sb[*b] * sb[*c];
                }
            }
        }
        Ok(total)
    };

    let sb0: BTreeMap<String, f64> = ws_sum.clone();
    let inner0 = inner(&sb0)?;
    if inner0 >= 0.0 {
        return Ok(AggregateResult { charge: inner0.sqrt(), used_fallback: false, sb: sb0 });
    }
    let sb1: BTreeMap<String, f64> = kb
        .iter()
        .map(|(b, &k)| (b.clone(), ws_sum[b].min(k).max(-k)))
        .collect();
    let inner1 = inner(&sb1)?;
    Ok(AggregateResult { charge: inner1.max(0.0).sqrt(), used_fallback: true, sb: sb1 })
}

// --------------------------------------------------------------------------
// Delta / vega charges
// --------------------------------------------------------------------------

/// Per-scenario charge with per-bucket K_b detail (for reporting/golden).
#[derive(Debug, Clone, PartialEq)]
pub struct RiskClassCharge {
    /// The risk-class charge under the requested scenario.
    pub charge: f64,
    /// Per-bucket within-bucket charges.
    pub kb: BTreeMap<String, f64>,
    /// Whether the delta/vega S_b fallback was triggered.
    pub used_fallback: bool,
}

/// Generic delta or vega charge for one risk class under one scenario.
///
/// * `bucket_ws`: `{bucket -> {factor_key -> WS}}` (factor keys iterate in
///   sorted order, matching the reference).
/// * `intra_rho`: `(bucket, factor_k, factor_l) -> medium correlation`.
/// * `gamma`: `(bucket_b, bucket_c) -> medium cross-bucket gamma`.
pub fn delta_vega_charge<R, G>(
    bucket_ws: &BTreeMap<String, BTreeMap<String, f64>>,
    intra_rho: R,
    gamma: G,
    scenario: Scenario,
    scenario_high: f64,
    scenario_low: f64,
) -> Result<RiskClassCharge>
where
    R: Fn(&str, &str, &str) -> Result<f64>,
    G: Fn(&str, &str) -> Result<f64>,
{
    let mut kb = BTreeMap::new();
    let mut ws_sum = BTreeMap::new();
    for (b, factors) in bucket_ws {
        let keys: Vec<&String> = factors.keys().collect();
        let ws: Vec<f64> = keys.iter().map(|k| factors[*k]).collect();
        let rho_fn = |i: usize, j: usize| -> Result<f64> {
            Ok(scale_rho(intra_rho(b, keys[i], keys[j])?, scenario, scenario_high, scenario_low))
        };
        kb.insert(b.clone(), bucket_kb(&ws, rho_fn)?);
        let mut s = 0.0;
        for w in &ws {
            s += w;
        }
        ws_sum.insert(b.clone(), s);
    }

    if kb.is_empty() {
        return Ok(RiskClassCharge { charge: 0.0, kb, used_fallback: false });
    }

    let gamma_fn = |b: &str, c: &str| -> Result<f64> {
        Ok(scale_rho(gamma(b, c)?, scenario, scenario_high, scenario_low))
    };
    let agg = aggregate_buckets(&kb, &ws_sum, gamma_fn)?;
    Ok(RiskClassCharge { charge: agg.charge, kb, used_fallback: agg.used_fallback })
}

// --------------------------------------------------------------------------
// Curvature
// --------------------------------------------------------------------------

/// FRTB psi: 0 when both CVR terms are negative, else 1.
pub fn psi(a: f64, b: f64) -> f64 {
    if a < 0.0 && b < 0.0 {
        0.0
    } else {
        1.0
    }
}

/// Within-bucket curvature charge.
///
/// Returns `(K_b, S_b)` where `K_b = max(K_b+, K_b-)` with
/// `K_b+/- = sqrt(max(0, sum_k max(CVR_k,0)^2 + sum_{k!=l} rho_kl CVR_k
/// CVR_l psi(CVR_k, CVR_l)))` and `S_b` is the sum of CVRs on the winning
/// side (up on ties). `rho` must already be the CURVATURE correlation
/// (delta rho squared).
pub fn curvature_bucket_kb<R>(cvr_up: &[f64], cvr_dn: &[f64], rho: R) -> Result<(f64, f64)>
where
    R: Fn(usize, usize) -> Result<f64>,
{
    if cvr_up.len() != cvr_dn.len() {
        return invalid("curvature_bucket_kb: up/down CVR lists must match");
    }
    let side = |cvr: &[f64]| -> Result<f64> {
        let mut total = 0.0;
        for &c in cvr {
            let m = c.max(0.0);
            total += m * m;
        }
        let n = cvr.len();
        for k in 0..n {
            for l in 0..n {
                if k != l {
                    total += rho(k, l)? * cvr[k] * cvr[l] * psi(cvr[k], cvr[l]);
                }
            }
        }
        Ok(total.max(0.0).sqrt())
    };
    let k_up = side(cvr_up)?;
    let k_dn = side(cvr_dn)?;
    let sum = |cvr: &[f64]| {
        let mut s = 0.0;
        for &c in cvr {
            s += c;
        }
        s
    };
    if k_up >= k_dn {
        Ok((k_up, sum(cvr_up)))
    } else {
        Ok((k_dn, sum(cvr_dn)))
    }
}

/// Curvature charge for one risk class under one scenario.
///
/// * `bucket_cvr`: `{bucket -> (CVR+ list, CVR- list)}` aligned with
///   `factor_keys[bucket]`.
/// * `intra_rho` / `gamma` supply medium DELTA correlations; they are
///   scenario-scaled then SQUARED for curvature (pinned simplification).
pub fn curvature_charge<R, G>(
    bucket_cvr: &BTreeMap<String, (Vec<f64>, Vec<f64>)>,
    intra_rho: R,
    gamma: G,
    scenario: Scenario,
    factor_keys: &BTreeMap<String, Vec<String>>,
    scenario_high: f64,
    scenario_low: f64,
) -> Result<RiskClassCharge>
where
    R: Fn(&str, &str, &str) -> Result<f64>,
    G: Fn(&str, &str) -> Result<f64>,
{
    let mut kb = BTreeMap::new();
    let mut sb = BTreeMap::new();
    for (b, (up, dn)) in bucket_cvr {
        let keys = factor_keys
            .get(b)
            .ok_or_else(|| crate::error::FrtbError::Invalid(format!(
                "curvature_charge: factor keys mismatch in bucket '{b}'"
            )))?;
        if keys.len() != up.len() {
            return invalid(format!("curvature_charge: factor keys mismatch in bucket '{b}'"));
        }
        let rho_fn = |i: usize, j: usize| -> Result<f64> {
            let r =
                scale_rho(intra_rho(b, &keys[i], &keys[j])?, scenario, scenario_high, scenario_low);
            Ok(r * r)
        };
        let (k, s) = curvature_bucket_kb(up, dn, rho_fn)?;
        kb.insert(b.clone(), k);
        sb.insert(b.clone(), s);
    }

    if kb.is_empty() {
        return Ok(RiskClassCharge { charge: 0.0, kb, used_fallback: false });
    }

    let buckets: Vec<&String> = kb.keys().collect();
    let mut total = 0.0;
    for b in &buckets {
        total += kb[*b] * kb[*b];
    }
    for b in &buckets {
        for c in &buckets {
            if b != c {
                let g = scale_rho(gamma(b, c)?, scenario, scenario_high, scenario_low);
                total += (g * g) * sb[*b] * sb[*c] * psi(sb[*b], sb[*c]);
            }
        }
    }
    Ok(RiskClassCharge { charge: total.max(0.0).sqrt(), kb, used_fallback: false })
}
