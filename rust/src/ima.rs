//! Internal Models Approach sketch: ES 97.5% with liquidity-horizon
//! scaling, IMCC, backtesting zones/multipliers, NMRF stress capital (SES).
//!
//! Pinned conventions (see `API_SPEC.md`):
//!
//! * ES 97.5 (daily): losses `L = -PnL` sorted descending;
//!   `k = ceil((1-alpha)*n)` (with a tiny epsilon guard);
//!   `ES_daily = mean of the k worst losses`. Base 10d ES =
//!   `sqrt(10) * ES_daily`.
//! * Liquidity-horizon ladder (Basel-style):
//!   `ES_LH = sqrt(ES_1(P)^2 + sum_{j>=2} (ES_1(P_j) *
//!   sqrt((LH_j - LH_{j-1})/10))^2)` where the ladder is (10, 20, 40, 60,
//!   120), `P_j` = P&L of the categories whose pinned horizon is `>= LH_j`,
//!   and `ES_1` is the base 10d ES operator. The ladder is monotone:
//!   `ES_LH >= ES_1`.
//! * `IMCC = rho * ES_LH(full) + (1-rho) * sum_c ES_LH(category c)`,
//!   rho = 0.5 pinned.
//! * Capital = `max(IMCC_{t-1}, multiplier * avg60(IMCC))`; the bundled
//!   portfolio is static so `avg60(IMCC) = IMCC` and the max resolves to
//!   `multiplier * IMCC` (multiplier >= 1.5) — documented simplification.
//! * Backtesting (99% VaR, 260 days): exception when `PnL_t < -VaR_t`.
//!   Zones: Green 0-4, Amber 5-9, Red >= 10. Multiplier: 1.5 (green),
//!   pinned amber table {5:1.70, 6:1.75, 7:1.83, 8:1.88, 9:1.92}, red 2.0
//!   (cap — also applies for any count > 12).
//! * SES = sum of pinned NMRF stressed losses, zero diversification
//!   benefit.

use crate::error::{invalid, Result};
use crate::params::SbmParams;
use crate::plat::PlatResult;

/// Daily ES at level `alpha`: mean of the `k = ceil((1-alpha)*n)` worst
/// losses (`L = -PnL`), with a tiny epsilon guarding binary-float ceil
/// artefacts.
pub fn expected_shortfall_daily(pnl: &[f64], alpha: f64) -> Result<f64> {
    let n = pnl.len();
    if n == 0 {
        return invalid("expected_shortfall_daily: empty P&L series");
    }
    if !(0.0 < alpha && alpha < 1.0) {
        return invalid(format!("expected_shortfall_daily: alpha must be in (0,1), got {alpha}"));
    }
    for &v in pnl {
        if !v.is_finite() {
            return invalid("expected_shortfall_daily: P&L contains non-finite values");
        }
    }
    // Tiny epsilon guards against artefacts like 0.025*40 -> 1.0000000000000009.
    let k = (((1.0 - alpha) * n as f64 - 1e-9).ceil() as usize).max(1);
    let mut losses: Vec<f64> = pnl.iter().map(|&v| -v).collect();
    losses.sort_by(|a, b| b.partial_cmp(a).expect("finite losses")); // descending
    let mut sum = 0.0;
    for &l in &losses[..k] {
        sum += l;
    }
    Ok(sum / k as f64)
}

/// Base 10-day ES: `sqrt(10) * daily ES` (pinned square-root-of-time
/// scaling).
pub fn es_base_10d(pnl: &[f64], alpha: f64) -> Result<f64> {
    Ok(10.0_f64.sqrt() * expected_shortfall_daily(pnl, alpha)?)
}

/// Liquidity-horizon-scaled ES (Basel ladder formula, see module docs).
///
/// `category_pnl` series (in column order) must sum to the full P&L
/// (validated to 1e-6) and every category must have a pinned liquidity
/// horizon in `category_lh`.
pub fn es_lh_scaled(
    full_pnl: &[f64],
    category_pnl: &[(String, Vec<f64>)],
    category_lh: &std::collections::BTreeMap<String, i64>,
    lh_ladder: &[i64],
    alpha: f64,
) -> Result<f64> {
    let mut sorted_unique: Vec<i64> = lh_ladder.to_vec();
    sorted_unique.sort_unstable();
    sorted_unique.dedup();
    if lh_ladder.is_empty() || lh_ladder != sorted_unique.as_slice() {
        return invalid("es_lh_scaled: lh_ladder must be strictly increasing");
    }
    if lh_ladder[0] != 10 {
        return invalid("es_lh_scaled: lh_ladder must start at the 10d base horizon");
    }
    let n = full_pnl.len();
    for (cat, series) in category_pnl {
        if !category_lh.contains_key(cat) {
            return invalid(format!("es_lh_scaled: no pinned liquidity horizon for category '{cat}'"));
        }
        if series.len() != n {
            return invalid(format!("es_lh_scaled: category '{cat}' length mismatch"));
        }
    }
    for i in 0..n {
        let mut s = 0.0;
        for (_, series) in category_pnl {
            s += series[i];
        }
        if (s - full_pnl[i]).abs() > 1e-6 {
            return invalid(format!(
                "es_lh_scaled: category P&L does not sum to the full P&L on day {i} ({s} vs {})",
                full_pnl[i]
            ));
        }
    }

    let es_full = es_base_10d(full_pnl, alpha)?;
    let mut total_sq = es_full * es_full;
    for j in 1..lh_ladder.len() {
        let (lh_j, lh_prev) = (lh_ladder[j], lh_ladder[j - 1]);
        let cats: Vec<&(String, Vec<f64>)> =
            category_pnl.iter().filter(|(c, _)| category_lh[c] >= lh_j).collect();
        if cats.is_empty() {
            continue;
        }
        let subset: Vec<f64> = (0..n)
            .map(|i| {
                let mut s = 0.0;
                for (_, series) in &cats {
                    s += series[i];
                }
                s
            })
            .collect();
        if subset.iter().all(|&v| v == 0.0) {
            continue;
        }
        let term = es_base_10d(&subset, alpha)? * ((lh_j - lh_prev) as f64 / 10.0).sqrt();
        total_sq += term * term;
    }
    Ok(total_sq.sqrt())
}

/// `IMCC = rho * ES_LH(full) + (1-rho) * sum over categories of
/// ES_LH(category)`.
pub fn imcc(
    full_pnl: &[f64],
    category_pnl: &[(String, Vec<f64>)],
    params: &SbmParams,
) -> Result<f64> {
    let rho = params.ima_rho;
    let es_full =
        es_lh_scaled(full_pnl, category_pnl, &params.category_lh, &params.lh_ladder, params.ima_alpha)?;
    let mut es_partials = 0.0;
    for (cat, series) in category_pnl {
        let single = [(cat.clone(), series.clone())];
        es_partials +=
            es_lh_scaled(series, &single, &params.category_lh, &params.lh_ladder, params.ima_alpha)?;
    }
    Ok(rho * es_full + (1.0 - rho) * es_partials)
}

// --------------------------------------------------------------------------
// Backtesting
// --------------------------------------------------------------------------

/// VaR backtest outcome: exception count, Basel zone, capital multiplier.
#[derive(Debug, Clone, PartialEq)]
pub struct BacktestResult {
    /// Number of days with `PnL_t < -VaR_t` (strict).
    pub exceptions: usize,
    /// `"green"`, `"amber"` or `"red"`.
    pub zone: String,
    /// Capital multiplier from the pinned mapping.
    pub multiplier: f64,
}

/// Count 99% VaR exceptions (`PnL_t < -VaR_t`) and map to zone/multiplier.
pub fn backtest(pnl: &[f64], var99: &[f64], params: &SbmParams) -> Result<BacktestResult> {
    if pnl.len() != var99.len() {
        return invalid(format!(
            "backtest: P&L and VaR length mismatch ({} vs {})",
            pnl.len(),
            var99.len()
        ));
    }
    if pnl.is_empty() {
        return invalid("backtest: empty series");
    }
    for &v in var99 {
        if !v.is_finite() || v < 0.0 {
            return invalid("backtest: VaR values must be non-negative and finite");
        }
    }
    let exceptions = pnl.iter().zip(var99.iter()).filter(|(&p, &v)| p < -v).count();
    Ok(BacktestResult {
        exceptions,
        zone: backtest_zone(exceptions).to_string(),
        multiplier: backtest_multiplier(exceptions, params)?,
    })
}

/// Basel traffic-light zone: green 0-4, amber 5-9, red >= 10.
pub fn backtest_zone(exceptions: usize) -> &'static str {
    if exceptions <= 4 {
        "green"
    } else if exceptions <= 9 {
        "amber"
    } else {
        "red"
    }
}

/// Pinned multiplier: 1.5 green; amber table 5..9; 2.0 red (cap, also for
/// any count > 12).
pub fn backtest_multiplier(exceptions: usize, params: &SbmParams) -> Result<f64> {
    match backtest_zone(exceptions) {
        "green" => Ok(params.backtest_base_multiplier),
        "amber" => params
            .backtest_amber_multipliers
            .get(&(exceptions as i64))
            .copied()
            .ok_or_else(|| {
                crate::error::FrtbError::Invalid(format!(
                    "backtest_multiplier: no amber multiplier pinned for {exceptions}"
                ))
            }),
        _ => Ok(params.backtest_red_multiplier),
    }
}

// --------------------------------------------------------------------------
// NMRF / SES
// --------------------------------------------------------------------------

/// One pinned non-modellable risk factor.
#[derive(Debug, Clone, PartialEq)]
pub struct NmrfEntry {
    /// Risk-factor name.
    pub factor: String,
    /// Owning desk.
    pub desk: String,
    /// Stressed loss (>= 0).
    pub stressed_loss: f64,
}

/// Stress scenario capital: sum of stressed losses, zero diversification.
pub fn ses(nmrf_entries: &[NmrfEntry]) -> Result<f64> {
    let mut total = 0.0;
    for e in nmrf_entries {
        if !e.stressed_loss.is_finite() || e.stressed_loss < 0.0 {
            return invalid(format!(
                "ses: stressed_loss must be >= 0 and finite (factor '{}')",
                e.factor
            ));
        }
        total += e.stressed_loss;
    }
    Ok(total)
}

/// IMA capital = `multiplier * IMCC + SES + PLAT surcharge`.
///
/// Simplification (documented): `avg60(IMCC) = IMCC` for the static bundled
/// portfolio, so `max(IMCC, m*avg60(IMCC)) = m*IMCC` since `m >= 1.5`.
pub fn ima_capital(
    imcc_value: f64,
    multiplier: f64,
    ses_value: f64,
    plat_surcharge: f64,
) -> Result<f64> {
    for (name, v) in [
        ("imcc", imcc_value),
        ("multiplier", multiplier),
        ("ses", ses_value),
        ("plat_surcharge", plat_surcharge),
    ] {
        if !v.is_finite() || v < 0.0 {
            return invalid(format!("ima_capital: {name} must be >= 0 and finite, got {v}"));
        }
    }
    Ok(multiplier * imcc_value + ses_value + plat_surcharge)
}

/// Per-desk IMA summary assembled by the engine.
#[derive(Debug, Clone, PartialEq)]
pub struct DeskIma {
    /// Base 10-day ES.
    pub es_base: f64,
    /// Liquidity-horizon-scaled ES.
    pub es_lh: f64,
    /// Internally modelled capital charge (pre-multiplier).
    pub imcc: f64,
    /// VaR backtest outcome.
    pub backtest: BacktestResult,
    /// PLAT outcome.
    pub plat: PlatResult,
    /// NMRF stress capital for the desk.
    pub ses: f64,
    /// Core IMA capital: `multiplier * IMCC + SES`.
    pub capital_core: f64,
    /// PLAT amber surcharge (0 for green/red).
    pub plat_surcharge: f64,
    /// Total IMA capital: core + surcharge.
    pub capital: f64,
}
