"""Standardised Approach assembly: SBM charges per risk class and scenario,
plus DRC-lite and RRAO.  SA capital = SBM + DRC + RRAO.
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Dict, List, Mapping, Optional, Sequence, Tuple

from .instruments import Bond, Instrument
from .market import Market
from .params import SbmParams
from .pricers import price_bond
from .sbm import SCENARIOS, RiskClassCharge, curvature_charge, delta_vega_charge
from .sensitivities import Sensitivities

RISK_CLASSES = ("girr", "equity", "fx")
MEASURES = ("delta", "vega", "curvature")


@dataclass(frozen=True)
class SbmResult:
    """SBM capital with full drill-down.

    charges[risk_class][measure][scenario] -> charge
    kb_medium[risk_class][measure] -> {bucket: K_b} (medium scenario)
    scenario_totals[scenario] -> sum over risk classes and measures
    capital = max over scenarios of scenario_totals.
    """

    charges: Dict[str, Dict[str, Dict[str, float]]]
    kb_medium: Dict[str, Dict[str, Dict[str, float]]]
    scenario_totals: Dict[str, float]
    capital: float


def _girr_structs(sens: Sensitivities, params: SbmParams):
    """GIRR delta WS per currency bucket + tenor-index correlation lookup."""
    tenor_index = {t: i for i, t in enumerate(params.girr_tenors)}
    bucket_ws = {
        ccy: {f"{t:g}": params.girr_rw(t) * s for t, s in per_tenor.items()}
        for ccy, per_tenor in sens.girr.items()
    }

    def intra_rho(_bucket: str, k: str, l: str) -> float:
        return params.girr_rho_kl(tenor_index[float(k)], tenor_index[float(l)])

    def gamma(_b: str, _c: str) -> float:
        return params.girr_gamma

    return bucket_ws, intra_rho, gamma


def _equity_structs(sens_map: Mapping[str, float], market: Market, params: SbmParams,
                    vega: bool):
    """Equity delta or vega WS per bucket, factor key = underlier name."""
    bucket_ws: Dict[str, Dict[str, float]] = {}
    for name, s in sens_map.items():
        b = market.equity(name).bucket
        p = params.equity_bucket(b)  # ValueError if the bucket is not pinned
        rw = p.vega_rw if vega else p.delta_rw
        bucket_ws.setdefault(b, {})[name] = rw * s

    def intra_rho(bucket: str, _k: str, _l: str) -> float:
        return params.equity_bucket(bucket).rho

    def gamma(_b: str, _c: str) -> float:
        return params.equity_gamma

    return bucket_ws, intra_rho, gamma


def _fx_structs(sens_map: Mapping[str, float], params: SbmParams):
    """FX WS: single pinned bucket 'FX', factor key = currency pair."""
    bucket_ws = {"FX": {pair: params.fx_delta_rw * s for pair, s in sens_map.items()}} \
        if sens_map else {}

    def intra_rho(_b: str, _k: str, _l: str) -> float:
        return params.fx_rho

    def gamma(_b: str, _c: str) -> float:
        return params.fx_gamma

    return bucket_ws, intra_rho, gamma


def sbm_capital(sens: Sensitivities, market: Market, params: SbmParams) -> SbmResult:
    """Assemble the full SBM capital: 3 risk classes x 3 measures x 3 scenarios."""
    hi, lo = params.scenario_high, params.scenario_low
    charges: Dict[str, Dict[str, Dict[str, float]]] = {rc: {m: {} for m in MEASURES}
                                                       for rc in RISK_CLASSES}
    kb_medium: Dict[str, Dict[str, Dict[str, float]]] = {rc: {} for rc in RISK_CLASSES}

    # -- delta / vega -------------------------------------------------------
    girr_ws, girr_rho, girr_gamma = _girr_structs(sens, params)
    eqd_ws, eq_rho, eq_gamma = _equity_structs(sens.equity_delta, market, params, vega=False)
    eqv_ws, _, _ = _equity_structs(sens.equity_vega, market, params, vega=True)
    fx_ws, fx_rho, fx_gamma = _fx_structs(sens.fx_delta, params)

    dv_specs = [
        ("girr", "delta", girr_ws, girr_rho, girr_gamma),
        ("girr", "vega", {}, girr_rho, girr_gamma),  # no IR-vol instruments in scope
        ("equity", "delta", eqd_ws, eq_rho, eq_gamma),
        ("equity", "vega", eqv_ws, eq_rho, eq_gamma),
        ("fx", "delta", fx_ws, fx_rho, fx_gamma),
    ]
    for rc, measure, ws, rho, gamma in dv_specs:
        for scen in SCENARIOS:
            res = delta_vega_charge(ws, rho, gamma, scen, hi, lo)
            charges[rc][measure][scen] = res.charge
            if scen == "medium":
                kb_medium[rc][measure] = dict(res.kb)

    # -- curvature ----------------------------------------------------------
    def one_factor_keys(cvr: Mapping[str, Tuple[float, float]]) -> Dict[str, List[str]]:
        return {b: ["crv"] for b in cvr}

    girr_cvr = {ccy: ([up], [dn]) for ccy, (up, dn) in sens.girr_cvr.items()}
    eq_cvr: Dict[str, Tuple[List[float], List[float]]] = {}
    eq_keys: Dict[str, List[str]] = {}
    for name, (up, dn) in sens.equity_cvr.items():
        b = market.equity(name).bucket
        params.equity_bucket(b)
        if b not in eq_cvr:
            eq_cvr[b] = ([], [])
            eq_keys[b] = []
        eq_cvr[b][0].append(up)
        eq_cvr[b][1].append(dn)
        eq_keys[b].append(name)
    fx_cvr = {"FX": ([u for u, _ in sens.fx_cvr.values()],
                     [d for _, d in sens.fx_cvr.values()])} if sens.fx_cvr else {}
    fx_keys = {"FX": sorted(sens.fx_cvr)}

    crv_specs = [
        ("girr", girr_cvr, girr_rho, girr_gamma, one_factor_keys(sens.girr_cvr)),
        ("equity", eq_cvr, eq_rho, eq_gamma, eq_keys),
        ("fx", fx_cvr, fx_rho, fx_gamma, fx_keys),
    ]
    for rc, cvr, rho, gamma, keys in crv_specs:
        for scen in SCENARIOS:
            res = curvature_charge(cvr, rho, gamma, scen, keys, hi, lo)
            charges[rc]["curvature"][scen] = res.charge
            if scen == "medium":
                kb_medium[rc]["curvature"] = dict(res.kb)

    # fx vega not modelled: pin to zero for all scenarios
    for scen in SCENARIOS:
        charges["fx"]["vega"][scen] = 0.0

    scenario_totals = {
        scen: sum(charges[rc][m].get(scen, 0.0) for rc in RISK_CLASSES for m in MEASURES)
        for scen in SCENARIOS
    }
    capital = max(scenario_totals.values())
    return SbmResult(charges=charges, kb_medium=kb_medium,
                     scenario_totals=scenario_totals, capital=capital)


# --------------------------------------------------------------------------
# DRC-lite
# --------------------------------------------------------------------------

@dataclass(frozen=True)
class DrcPosition:
    """One default-risk position: issuer, rating, notional (signed), market value."""

    issuer: str
    rating: str
    notional: float
    market_value: float
    lgd: float = 0.75

    def jtd(self) -> float:
        """Jump-to-default: JTD = LGD*notional + (MV - notional) (signed)."""
        return self.lgd * self.notional + (self.market_value - self.notional)


@dataclass(frozen=True)
class DrcResult:
    """DRC-lite output: charge + netting/HBR drill-down."""

    charge: float
    hbr: float
    net_jtd: Dict[str, float]
    gross_long: float
    gross_short: float


def drc_charge(positions: Sequence[DrcPosition], params: SbmParams) -> DrcResult:
    """Default Risk Charge (lite).

    1. JTD_i = LGD*notional + (MV - notional) per position (signed; shorts have
       negative notional).
    2. Net JTD per issuer (long/short netting within the same issuer).
    3. HBR = sum(netLong) / (sum(netLong) + sum(|netShort|)); HBR = 1 when
       there are no net shorts (all-long edge case) and when the book is empty.
    4. DRC = max(0, sum RW_i*netLong_i - HBR * sum RW_i*|netShort_i|)
       with RW from the pinned rating table (unknown rating -> ValueError).
    """
    net: Dict[str, float] = {}
    rating_of: Dict[str, str] = {}
    for p in positions:
        net[p.issuer] = net.get(p.issuer, 0.0) + p.jtd()
        prev = rating_of.setdefault(p.issuer, p.rating)
        if prev != p.rating:
            raise ValueError(f"drc_charge: issuer '{p.issuer}' has inconsistent ratings "
                             f"('{prev}' vs '{p.rating}')")
    long_sum = sum(v for v in net.values() if v > 0.0)
    short_sum = sum(-v for v in net.values() if v < 0.0)
    denom = long_sum + short_sum
    hbr = long_sum / denom if denom > 0.0 else 1.0
    weighted_long = sum(params.drc_rw(rating_of[i]) * v for i, v in net.items() if v > 0.0)
    weighted_short = sum(params.drc_rw(rating_of[i]) * (-v) for i, v in net.items() if v < 0.0)
    charge = max(0.0, weighted_long - hbr * weighted_short)
    return DrcResult(charge=charge, hbr=hbr, net_jtd=net,
                     gross_long=long_sum, gross_short=short_sum)


def drc_positions_from_instruments(instruments: Sequence[Instrument],
                                   market: Market) -> List[DrcPosition]:
    """Extract DRC positions (bonds only in this educational kit — documented)."""
    out: List[DrcPosition] = []
    for inst in instruments:
        if isinstance(inst, Bond):
            mv = price_bond(inst, market.curve(inst.currency))
            out.append(DrcPosition(issuer=inst.issuer, rating=inst.rating,
                                   notional=inst.notional, market_value=mv, lgd=inst.lgd))
    return out


# --------------------------------------------------------------------------
# RRAO
# --------------------------------------------------------------------------

def rrao_charge(instruments: Sequence[Instrument], params: SbmParams) -> float:
    """Residual risk add-on: sum over flagged instruments of rate * notional.

    Pinned rates: 'exotic' 1.0%, 'other' 0.1% of the flagged notional.
    """
    total = 0.0
    for inst in instruments:
        flag = getattr(inst, "rrao", None)
        if flag is not None:
            total += params.rrao_rate(flag.category) * flag.notional
    return total
