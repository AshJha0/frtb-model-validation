"""Bump-and-revalue sensitivities with pinned bump sizes.

Pinned bumps (documented in API_SPEC.md):

* GIRR delta:   +1bp absolute bump of one curve node;  s = (V+ - V) / 1e-4
                (i.e. sensitivity is expressed per unit of rate, dV/dr).
* Equity delta: +1%% relative spot bump;               s = (V+ - V) / 0.01
                (i.e. S * dV/dS, the FRTB relative-shift convention).
* Equity vega:  +1 vol point absolute bump;            raw = (V+ - V) / 0.01,
                WS uses s = raw * sigma (FRTB vega = vega * implied vol).
* FX delta:     +1%% relative spot bump;               s = (V+ - V) / 0.01.
* Curvature:    full risk-weight shock up/down (parallel for GIRR curves,
                relative for equity/FX spots) with the delta term stripped:
                CVR^+ = -(V_up - V - RW*s),  CVR^- = -(V_dn - V + RW*s).
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Dict, List, Mapping, Sequence, Tuple

from .instruments import Instrument
from .market import Market
from .params import SbmParams
from .pricers import price_portfolio

GIRR_BUMP = 1e-4       # 1bp absolute zero-rate bump
EQ_SPOT_BUMP = 0.01    # 1% relative spot bump
VOL_BUMP = 0.01        # 1 vol point absolute bump
FX_BUMP = 0.01         # 1% relative FX spot bump
_ZERO_TOL = 1e-9       # sensitivities below this (absolute) are treated as zero


@dataclass(frozen=True)
class Sensitivities:
    """All raw (unweighted) sensitivities of one instrument scope.

    girr:   {currency: {tenor: dV/dr}}          (only non-zero currencies kept)
    equity_delta: {name: S * dV/dS}
    equity_vega:  {name: vega * sigma}
    fx_delta:     {pair: S * dV/dS}
    curvature CVRs per risk class, aligned with the factor key lists.
    """

    girr: Dict[str, Dict[float, float]]
    equity_delta: Dict[str, float]
    equity_vega: Dict[str, float]
    fx_delta: Dict[str, float]
    girr_cvr: Dict[str, Tuple[float, float]]     # {ccy: (CVR+, CVR-)}
    equity_cvr: Dict[str, Tuple[float, float]]   # {name: (CVR+, CVR-)}
    fx_cvr: Dict[str, Tuple[float, float]]       # {pair: (CVR+, CVR-)}


def compute_sensitivities(
    instruments: Sequence[Instrument], market: Market, params: SbmParams
) -> Sensitivities:
    """Full bump-and-revalue pass over one instrument scope (desk or firm).

    Deterministic: pure revaluation under bumped market snapshots, no RNG.
    An empty scope returns all-empty maps (capital 0 downstream).
    """
    base = price_portfolio(instruments, market)

    # ---- GIRR delta: bump each curve node of each currency by 1bp ----------
    girr: Dict[str, Dict[float, float]] = {}
    for ccy in sorted(market.curves):
        per_tenor: Dict[float, float] = {}
        any_nonzero = False
        for tenor in params.girr_tenors:
            bumped = market.bump_curve_node(ccy, tenor, GIRR_BUMP)
            s = (price_portfolio(instruments, bumped) - base) / GIRR_BUMP
            if abs(s) > _ZERO_TOL:
                any_nonzero = True
            per_tenor[tenor] = s
        if any_nonzero:
            girr[ccy] = per_tenor

    # ---- Equity delta & vega ----------------------------------------------
    equity_delta: Dict[str, float] = {}
    equity_vega: Dict[str, float] = {}
    names = sorted({i.underlier for i in instruments if hasattr(i, "underlier")})
    for name in names:
        q = market.equity(name)
        s_d = (price_portfolio(instruments, market.bump_equity_spot(name, EQ_SPOT_BUMP)) - base) / EQ_SPOT_BUMP
        raw_vega = (price_portfolio(instruments, market.bump_equity_vol(name, VOL_BUMP)) - base) / VOL_BUMP
        s_v = raw_vega * q.vol
        if abs(s_d) > _ZERO_TOL:
            equity_delta[name] = s_d
        if abs(s_v) > _ZERO_TOL:
            equity_vega[name] = s_v

    # ---- FX delta ----------------------------------------------------------
    fx_delta: Dict[str, float] = {}
    pairs = sorted({i.pair for i in instruments if hasattr(i, "pair")})
    for pair in pairs:
        s = (price_portfolio(instruments, market.bump_fx(pair, FX_BUMP)) - base) / FX_BUMP
        if abs(s) > _ZERO_TOL:
            fx_delta[pair] = s

    # ---- Curvature ---------------------------------------------------------
    girr_cvr: Dict[str, Tuple[float, float]] = {}
    rw_c = params.girr_curvature_rw
    for ccy in sorted(girr):
        slope = sum(girr[ccy].values())  # sum of delta sensitivities over tenors
        v_up = price_portfolio(instruments, market.bump_curve_parallel(ccy, rw_c))
        v_dn = price_portfolio(instruments, market.bump_curve_parallel(ccy, -rw_c))
        girr_cvr[ccy] = (-(v_up - base - rw_c * slope), -(v_dn - base + rw_c * slope))

    equity_cvr: Dict[str, Tuple[float, float]] = {}
    for name in names:
        if name not in equity_delta:
            continue
        rw = params.equity_bucket(market.equity(name).bucket).delta_rw
        s = equity_delta[name]
        v_up = price_portfolio(instruments, market.bump_equity_spot(name, rw))
        v_dn = price_portfolio(instruments, market.bump_equity_spot(name, -rw))
        equity_cvr[name] = (-(v_up - base - rw * s), -(v_dn - base + rw * s))

    fx_cvr: Dict[str, Tuple[float, float]] = {}
    for pair in pairs:
        if pair not in fx_delta:
            continue
        rw = params.fx_delta_rw
        s = fx_delta[pair]
        v_up = price_portfolio(instruments, market.bump_fx(pair, rw))
        v_dn = price_portfolio(instruments, market.bump_fx(pair, -rw))
        fx_cvr[pair] = (-(v_up - base - rw * s), -(v_dn - base + rw * s))

    return Sensitivities(
        girr=girr, equity_delta=equity_delta, equity_vega=equity_vega,
        fx_delta=fx_delta, girr_cvr=girr_cvr, equity_cvr=equity_cvr, fx_cvr=fx_cvr,
    )
