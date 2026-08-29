"""P&L attribution test (PLAT): Spearman + KS metrics, traffic-light zone,
Amber capital surcharge.

Pinned thresholds:
  Green:  spearman >= 0.85  AND  KS <= 0.09
  Red:    spearman <  0.80  OR   KS >  0.12
  Amber:  everything in between.
Constant P&L on either side leaves the Spearman correlation undefined; the
desk is assigned RED in that case (documented conservative convention).

Amber surcharge (pinned k = 0.5 interpolation between IMA and SA):
  surcharge = k * max(0, SA_desk - IMA_desk_core)
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Optional, Sequence

from .params import SbmParams
from .stats import ks_statistic, spearman


@dataclass(frozen=True)
class PlatResult:
    """PLAT outcome; metrics are None when undefined (constant series -> Red)."""

    spearman: Optional[float]
    ks: Optional[float]
    zone: str  # 'green' | 'amber' | 'red'


def plat_zone_from_metrics(spearman_rho: float, ks_stat: float, params: SbmParams) -> str:
    """Map (Spearman, KS) to a PLAT zone using the pinned thresholds."""
    if not (math.isfinite(spearman_rho) and math.isfinite(ks_stat)):
        raise ValueError("plat_zone_from_metrics: metrics must be finite")
    if spearman_rho < params.plat_spearman_amber or ks_stat > params.plat_ks_amber:
        return "red"
    if spearman_rho >= params.plat_spearman_green and ks_stat <= params.plat_ks_green:
        return "green"
    return "amber"


def plat_test(hypo: Sequence[float], rtpl: Sequence[float], params: SbmParams) -> PlatResult:
    """Run the PLAT on hypothetical vs risk-theoretical P&L.

    A constant series on either side makes the rank correlation undefined:
    the result is Red with metrics = None (documented edge case).
    """
    if len(hypo) != len(rtpl):
        raise ValueError(f"plat_test: series length mismatch ({len(hypo)} vs {len(rtpl)})")
    if len(hypo) < 3:
        raise ValueError("plat_test: need at least 3 observations")
    try:
        rho = spearman(hypo, rtpl)
    except ValueError:
        # constant series -> correlation undefined -> Red (conservative)
        return PlatResult(spearman=None, ks=None, zone="red")
    ks = ks_statistic(hypo, rtpl)
    return PlatResult(spearman=rho, ks=ks, zone=plat_zone_from_metrics(rho, ks, params))


def plat_surcharge(zone: str, sa_capital: float, ima_capital_core: float,
                   params: SbmParams) -> float:
    """Amber-zone capital surcharge: k * max(0, SA - IMA_core); 0 otherwise.

    Red-zone desks fall back to SA entirely (handled by the caller/report);
    the surcharge formula itself applies only to Amber.
    """
    if zone not in ("green", "amber", "red"):
        raise ValueError(f"plat_surcharge: unknown zone '{zone}'")
    for name, v in (("sa_capital", sa_capital), ("ima_capital_core", ima_capital_core)):
        if not math.isfinite(v) or v < 0.0:
            raise ValueError(f"plat_surcharge: {name} must be >= 0 and finite")
    if zone != "amber":
        return 0.0
    return params.plat_k_surcharge * max(0.0, sa_capital - ima_capital_core)
