"""Sensitivities-Based Method (SBM) aggregation.

Formulas (FRTB structure, educational parameter set):

Weighted sensitivity            WS_k = RW_k * s_k
Within-bucket                   K_b  = sqrt(max(0, sum_k WS_k^2
                                        + sum_{k != l} rho_kl WS_k WS_l))
Across buckets                  Charge = sqrt(max(0, sum_b K_b^2
                                        + sum_{b != c} gamma_bc S_b S_c))
  with S_b = sum_k WS_k; if the argument of the outer sqrt is negative the
  S_b FALLBACK applies:  S_b = max(min(sum_k WS_k, K_b), -K_b)  and the
  aggregate is recomputed (the max(0, .) guard is kept as a belt-and-braces
  guard against negative rounding).

Correlation scenarios: for every rho and gamma,
  high   rho -> min(1.25 * rho, 1.0)
  medium rho -> rho
  low    rho -> 0.75 * rho          (pinned simplification of Basel's
                                     max(2*rho - 1, 0.75*rho))
The risk-class charges are computed under each scenario, summed across risk
classes and the SBM capital is the MAX of the three scenario totals.

Curvature: per curvature risk factor k with delta sensitivity s_k,
  CVR_k^+ = -( V(x_k up)   - V - RW_k^curv * s_k )
  CVR_k^- = -( V(x_k down) - V + RW_k^curv * s_k )
  K_b^+/- = sqrt(max(0, max(sum-of-squares form with psi) ))  where
            psi(a, b) = 0 if a < 0 and b < 0 else 1
  K_b     = max(K_b^+, K_b^-), select S_b as the CVR sum of the winning side
            (ties -> up side; pinned simplification, documented in API_SPEC).
Across buckets curvature uses gamma^2, psi(S_b, S_c) and max(0, .) inside the
sqrt; there is no S_b fallback for curvature. Curvature correlations are the
squares of the (scenario-scaled) delta correlations (pinned simplification).
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Callable, Dict, List, Mapping, Sequence, Tuple

SCENARIOS = ("high", "medium", "low")


def scale_rho(rho: float, scenario: str, high: float = 1.25, low: float = 0.75) -> float:
    """Apply the correlation scenario scaler; 'high' is capped at 1.0."""
    if scenario == "medium":
        return rho
    if scenario == "high":
        return min(high * rho, 1.0)
    if scenario == "low":
        return low * rho
    raise ValueError(f"scale_rho: unknown scenario '{scenario}'")


def bucket_kb(ws: Sequence[float], rho: Callable[[int, int], float]) -> float:
    """Within-bucket charge K_b = sqrt(max(0, sum WS^2 + sum_{k!=l} rho WS_k WS_l)).

    ``rho(k, l)`` supplies the pairwise correlation for k != l.
    The max(0, .) guard protects against negative rounding of the quadratic form.
    """
    for w in ws:
        if not math.isfinite(w):
            raise ValueError("bucket_kb: weighted sensitivities must be finite")
    total = sum(w * w for w in ws)
    n = len(ws)
    for k in range(n):
        for l in range(n):
            if k != l:
                total += rho(k, l) * ws[k] * ws[l]
    return math.sqrt(max(0.0, total))


@dataclass(frozen=True)
class AggregateResult:
    """Across-bucket aggregation output (with the S_b fallback bookkeeping)."""

    charge: float
    used_fallback: bool
    sb: Dict[str, float]  # the S_b actually used (post-fallback if triggered)


def aggregate_buckets(
    kb: Mapping[str, float],
    ws_sum: Mapping[str, float],
    gamma: Callable[[str, str], float],
) -> AggregateResult:
    """Across-bucket aggregation with the FRTB S_b fallback rule.

    First tries S_b = sum_k WS_k.  If sum_b K_b^2 + sum_{b!=c} gamma S_b S_c
    < 0, recomputes with S_b = max(min(sum_k WS_k, K_b), -K_b).
    """
    if set(kb) != set(ws_sum):
        raise ValueError("aggregate_buckets: kb and ws_sum must cover the same buckets")
    buckets = sorted(kb)

    def _inner(sb: Mapping[str, float]) -> float:
        total = sum(kb[b] ** 2 for b in buckets)
        for b in buckets:
            for c in buckets:
                if b != c:
                    total += gamma(b, c) * sb[b] * sb[c]
        return total

    sb0 = {b: ws_sum[b] for b in buckets}
    inner = _inner(sb0)
    if inner >= 0.0:
        return AggregateResult(math.sqrt(inner), False, sb0)
    sb1 = {b: max(min(ws_sum[b], kb[b]), -kb[b]) for b in buckets}
    inner = _inner(sb1)
    return AggregateResult(math.sqrt(max(0.0, inner)), True, sb1)


# --------------------------------------------------------------------------
# Delta / vega charges
# --------------------------------------------------------------------------

@dataclass(frozen=True)
class RiskClassCharge:
    """Per-scenario charge with per-bucket K_b detail (for reporting/golden)."""

    charge: float
    kb: Dict[str, float]
    used_fallback: bool


def delta_vega_charge(
    bucket_ws: Mapping[str, Mapping[str, float]],
    intra_rho: Callable[[str, str, str], float],
    gamma: Callable[[str, str], float],
    scenario: str,
    scenario_high: float = 1.25,
    scenario_low: float = 0.75,
) -> RiskClassCharge:
    """Generic delta or vega charge for one risk class under one scenario.

    bucket_ws:  {bucket: {factor_key: WS}}
    intra_rho:  (bucket, factor_k, factor_l) -> medium-scenario correlation
    gamma:      (bucket_b, bucket_c) -> medium-scenario cross-bucket gamma
    """
    kb: Dict[str, float] = {}
    ws_sum: Dict[str, float] = {}
    for b, factors in bucket_ws.items():
        keys = sorted(factors)
        ws = [factors[k] for k in keys]

        def rho_fn(i: int, j: int, _b: str = b, _keys: List[str] = keys) -> float:
            return scale_rho(intra_rho(_b, _keys[i], _keys[j]), scenario,
                             scenario_high, scenario_low)

        kb[b] = bucket_kb(ws, rho_fn)
        ws_sum[b] = sum(ws)

    if not kb:
        return RiskClassCharge(0.0, {}, False)

    def gamma_fn(b: str, c: str) -> float:
        return scale_rho(gamma(b, c), scenario, scenario_high, scenario_low)

    agg = aggregate_buckets(kb, ws_sum, gamma_fn)
    return RiskClassCharge(agg.charge, kb, agg.used_fallback)


# --------------------------------------------------------------------------
# Curvature
# --------------------------------------------------------------------------

def psi(a: float, b: float) -> float:
    """FRTB psi: 0 when both CVR terms are negative, else 1."""
    return 0.0 if (a < 0.0 and b < 0.0) else 1.0


def curvature_bucket_kb(
    cvr_up: Sequence[float],
    cvr_dn: Sequence[float],
    rho: Callable[[int, int], float],
) -> Tuple[float, float]:
    """Within-bucket curvature charge.

    Returns (K_b, S_b) where K_b = max(K_b+, K_b-) with
      K_b+/- = sqrt(max(0, sum_k max(CVR_k,0)^2
                         + sum_{k!=l} rho_kl CVR_k CVR_l psi(CVR_k, CVR_l)))
    and S_b is the sum of CVRs on the winning side (up on ties).
    ``rho`` must already be the CURVATURE correlation (delta rho squared).
    """
    if len(cvr_up) != len(cvr_dn):
        raise ValueError("curvature_bucket_kb: up/down CVR lists must match")

    def side(cvr: Sequence[float]) -> float:
        total = sum(max(c, 0.0) ** 2 for c in cvr)
        n = len(cvr)
        for k in range(n):
            for l in range(n):
                if k != l:
                    total += rho(k, l) * cvr[k] * cvr[l] * psi(cvr[k], cvr[l])
        return math.sqrt(max(0.0, total))

    k_up, k_dn = side(cvr_up), side(cvr_dn)
    if k_up >= k_dn:
        return k_up, sum(cvr_up)
    return k_dn, sum(cvr_dn)


def curvature_charge(
    bucket_cvr: Mapping[str, Tuple[Sequence[float], Sequence[float]]],
    intra_rho: Callable[[str, str, str], float],
    gamma: Callable[[str, str], float],
    scenario: str,
    factor_keys: Mapping[str, Sequence[str]],
    scenario_high: float = 1.25,
    scenario_low: float = 0.75,
) -> RiskClassCharge:
    """Curvature charge for one risk class under one scenario.

    bucket_cvr: {bucket: (list CVR+, list CVR-)} aligned with factor_keys[bucket].
    intra_rho / gamma supply medium DELTA correlations; they are scenario-scaled
    then SQUARED for curvature (pinned simplification, documented).
    """
    kb: Dict[str, float] = {}
    sb: Dict[str, float] = {}
    for b, (up, dn) in bucket_cvr.items():
        keys = list(factor_keys[b])
        if len(keys) != len(up):
            raise ValueError(f"curvature_charge: factor keys mismatch in bucket '{b}'")

        def rho_fn(i: int, j: int, _b: str = b, _keys: List[str] = keys) -> float:
            r = scale_rho(intra_rho(_b, _keys[i], _keys[j]), scenario,
                          scenario_high, scenario_low)
            return r * r

        kb[b], sb[b] = curvature_bucket_kb(up, dn, rho_fn)

    if not kb:
        return RiskClassCharge(0.0, {}, False)

    buckets = sorted(kb)
    total = sum(kb[b] ** 2 for b in buckets)
    for b in buckets:
        for c in buckets:
            if b != c:
                g = scale_rho(gamma(b, c), scenario, scenario_high, scenario_low)
                total += (g * g) * sb[b] * sb[c] * psi(sb[b], sb[c])
    return RiskClassCharge(math.sqrt(max(0.0, total)), kb, False)
