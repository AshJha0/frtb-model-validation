"""Internal Models Approach sketch: ES 97.5% with liquidity-horizon scaling,
IMCC, backtesting zones/multipliers, NMRF stress capital (SES).

Pinned conventions (see API_SPEC.md):

* ES 97.5 (daily): losses L = -PnL sorted descending; k = ceil((1-alpha)*n);
  ES_daily = mean of the k worst losses.  Base 10d ES = sqrt(10) * ES_daily.
* Liquidity-horizon ladder (Basel-style):
      ES_LH = sqrt( ES_1(P)^2 + sum_{j>=2} ( ES_1(P_j) * sqrt((LH_j - LH_{j-1})/10) )^2 )
  where LH ladder = (10, 20, 40, 60, 120), P_j = P&L of the risk-factor
  categories whose pinned liquidity horizon is >= LH_j, and ES_1 is the base
  10d ES operator above.  The ladder is monotone: ES_LH >= base ES.
* IMCC = rho * ES_LH(full) + (1-rho) * sum_c ES_LH(category c), rho = 0.5.
* Capital = max(IMCC_{t-1}, multiplier * avg60(IMCC)); with the bundled static
  portfolio IMCC is constant so avg60(IMCC) = IMCC and the max resolves to
  multiplier * IMCC (multiplier >= 1.5) — documented simplification.
* Backtesting (99% VaR, 260 days): exception when PnL_t < -VaR_t.
  Zones: Green 0-4, Amber 5-9, Red >= 10.  Multiplier: 1.5 (green),
  pinned amber table {5:1.70, 6:1.75, 7:1.83, 8:1.88, 9:1.92}, red 2.0
  (cap — also applies for any count > 12).
* SES = sum of pinned NMRF stressed losses, zero diversification benefit.
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Dict, Mapping, Sequence

from .params import SbmParams


def expected_shortfall_daily(pnl: Sequence[float], alpha: float = 0.975) -> float:
    """Daily ES at level alpha: mean of the k = ceil((1-alpha)*n) worst losses."""
    n = len(pnl)
    if n == 0:
        raise ValueError("expected_shortfall_daily: empty P&L series")
    if not (0.0 < alpha < 1.0):
        raise ValueError(f"expected_shortfall_daily: alpha must be in (0,1), got {alpha}")
    for v in pnl:
        if not math.isfinite(v):
            raise ValueError("expected_shortfall_daily: P&L contains non-finite values")
    # tiny epsilon guards against binary-float artefacts like 0.025*40 -> 1.0000000000000009
    k = max(1, math.ceil((1.0 - alpha) * n - 1e-9))
    losses = sorted((-v for v in pnl), reverse=True)
    return sum(losses[:k]) / k


def es_base_10d(pnl: Sequence[float], alpha: float = 0.975) -> float:
    """Base 10-day ES: sqrt(10) * daily ES (pinned square-root-of-time scaling)."""
    return math.sqrt(10.0) * expected_shortfall_daily(pnl, alpha)


def es_lh_scaled(
    full_pnl: Sequence[float],
    category_pnl: Mapping[str, Sequence[float]],
    category_lh: Mapping[str, int],
    lh_ladder: Sequence[int],
    alpha: float = 0.975,
) -> float:
    """Liquidity-horizon-scaled ES (Basel ladder formula, see module docstring).

    ``category_pnl`` series must sum to the full P&L (validated to 1e-6) and
    every category must have a pinned liquidity horizon in ``category_lh``.
    """
    if len(lh_ladder) < 1 or list(lh_ladder) != sorted(set(lh_ladder)):
        raise ValueError("es_lh_scaled: lh_ladder must be strictly increasing")
    if lh_ladder[0] != 10:
        raise ValueError("es_lh_scaled: lh_ladder must start at the 10d base horizon")
    n = len(full_pnl)
    for cat, series in category_pnl.items():
        if cat not in category_lh:
            raise ValueError(f"es_lh_scaled: no pinned liquidity horizon for category '{cat}'")
        if len(series) != n:
            raise ValueError(f"es_lh_scaled: category '{cat}' length mismatch")
    for i in range(n):
        s = sum(category_pnl[c][i] for c in category_pnl)
        if abs(s - full_pnl[i]) > 1e-6:
            raise ValueError("es_lh_scaled: category P&L does not sum to the full P&L "
                             f"on day {i} ({s} vs {full_pnl[i]})")

    total_sq = es_base_10d(full_pnl, alpha) ** 2
    for j in range(1, len(lh_ladder)):
        lh_j, lh_prev = lh_ladder[j], lh_ladder[j - 1]
        cats = [c for c in category_pnl if category_lh[c] >= lh_j]
        if not cats:
            continue
        subset = [sum(category_pnl[c][i] for c in cats) for i in range(n)]
        if all(v == 0.0 for v in subset):
            continue
        term = es_base_10d(subset, alpha) * math.sqrt((lh_j - lh_prev) / 10.0)
        total_sq += term * term
    return math.sqrt(total_sq)


def imcc(
    full_pnl: Sequence[float],
    category_pnl: Mapping[str, Sequence[float]],
    params: SbmParams,
) -> float:
    """IMCC = rho * ES_LH(full) + (1-rho) * sum over categories of ES_LH(category)."""
    rho = params.ima_rho
    es_full = es_lh_scaled(full_pnl, category_pnl, params.category_lh,
                           params.lh_ladder, params.ima_alpha)
    es_partials = 0.0
    for cat, series in category_pnl.items():
        es_partials += es_lh_scaled(series, {cat: series}, params.category_lh,
                                    params.lh_ladder, params.ima_alpha)
    return rho * es_full + (1.0 - rho) * es_partials


# --------------------------------------------------------------------------
# Backtesting
# --------------------------------------------------------------------------

@dataclass(frozen=True)
class BacktestResult:
    """VaR backtest outcome: exception count, Basel zone, capital multiplier."""

    exceptions: int
    zone: str  # 'green' | 'amber' | 'red'
    multiplier: float


def backtest(pnl: Sequence[float], var99: Sequence[float], params: SbmParams) -> BacktestResult:
    """Count 99% VaR exceptions (PnL_t < -VaR_t) and map to zone/multiplier."""
    if len(pnl) != len(var99):
        raise ValueError(f"backtest: P&L and VaR length mismatch ({len(pnl)} vs {len(var99)})")
    if len(pnl) == 0:
        raise ValueError("backtest: empty series")
    for v in var99:
        if not math.isfinite(v) or v < 0.0:
            raise ValueError("backtest: VaR values must be non-negative and finite")
    exceptions = sum(1 for p, v in zip(pnl, var99) if p < -v)
    return BacktestResult(exceptions, backtest_zone(exceptions),
                          backtest_multiplier(exceptions, params))


def backtest_zone(exceptions: int) -> str:
    """Basel traffic-light zone: green 0-4, amber 5-9, red >= 10."""
    if exceptions < 0:
        raise ValueError("backtest_zone: exception count cannot be negative")
    if exceptions <= 4:
        return "green"
    if exceptions <= 9:
        return "amber"
    return "red"


def backtest_multiplier(exceptions: int, params: SbmParams) -> float:
    """Pinned multiplier: 1.5 green; amber table 5..9; 2.0 red (cap, also > 12)."""
    zone = backtest_zone(exceptions)
    if zone == "green":
        return params.backtest_base_multiplier
    if zone == "amber":
        if exceptions not in params.backtest_amber_multipliers:
            raise ValueError(f"backtest_multiplier: no amber multiplier pinned for {exceptions}")
        return params.backtest_amber_multipliers[exceptions]
    return params.backtest_red_multiplier


# --------------------------------------------------------------------------
# NMRF / SES
# --------------------------------------------------------------------------

def ses(nmrf_entries: Sequence[Mapping[str, object]]) -> float:
    """Stress scenario capital: sum of stressed losses, zero diversification.

    Each entry: {"factor": str, "desk": str, "stressed_loss": float >= 0}.
    """
    total = 0.0
    for e in nmrf_entries:
        loss = float(e["stressed_loss"])  # type: ignore[arg-type]
        if not math.isfinite(loss) or loss < 0.0:
            raise ValueError(f"ses: stressed_loss must be >= 0 and finite "
                             f"(factor '{e.get('factor')}')")
        total += loss
    return total


def ima_capital(imcc_value: float, multiplier: float, ses_value: float,
                plat_surcharge: float = 0.0) -> float:
    """IMA capital = multiplier * IMCC + SES + PLAT surcharge.

    Simplification (documented): avg60(IMCC) = IMCC for the static bundled
    portfolio, so max(IMCC, m*avg60(IMCC)) = m*IMCC since m >= 1.5.
    """
    for name, v in (("imcc", imcc_value), ("multiplier", multiplier),
                    ("ses", ses_value), ("plat_surcharge", plat_surcharge)):
        if not math.isfinite(v) or v < 0.0:
            raise ValueError(f"ima_capital: {name} must be >= 0 and finite, got {v}")
    return multiplier * imcc_value + ses_value + plat_surcharge
