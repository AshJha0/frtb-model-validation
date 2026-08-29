"""Instrument definitions and portfolio loading.

Instrument universe (deliberately small — this is an educational FRTB kit):

* ``Bond``        — fixed-coupon annual-pay bullet bond (also the DRC vehicle).
* ``PayerSwap``   — payer-swap proxy: long floating leg / short fixed leg,
                    priced as N*(1 - DF(T)) - c*N*sum DF(t_i).  Its GIRR
                    sensitivity is the "DV01 ladder" of the spec.
* ``EquityOption``— European equity option under Black-Scholes.
* ``FxForward``   — FX forward, valued in the domestic (quote) currency.
"""
from __future__ import annotations

import json
import math
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple, Union


@dataclass(frozen=True)
class RraoFlag:
    """Residual-risk add-on flag: category ('exotic' or 'other') + notional base."""

    category: str
    notional: float

    def __post_init__(self) -> None:
        if self.category not in ("exotic", "other"):
            raise ValueError(f"RraoFlag: category must be 'exotic' or 'other', got '{self.category}'")
        if not math.isfinite(self.notional) or self.notional < 0.0:
            raise ValueError("RraoFlag: notional must be a non-negative finite number")


def _check_finite(name: str, value: float) -> None:
    if not math.isfinite(value):
        raise ValueError(f"{name} must be finite, got {value}")


@dataclass(frozen=True)
class Bond:
    """Fixed-coupon annual-pay bullet bond.

    ``notional`` may be negative (short position, used by DRC netting).
    Coupons are paid at T, T-1, ... (annual, stub-free by construction of the data).
    """

    inst_id: str
    notional: float
    coupon: float
    maturity: float
    currency: str
    issuer: str
    rating: str
    lgd: float = 0.75
    rrao: Optional[RraoFlag] = None

    def __post_init__(self) -> None:
        _check_finite("Bond.notional", self.notional)
        _check_finite("Bond.coupon", self.coupon)
        if self.notional == 0.0:
            raise ValueError("Bond: notional must be non-zero")
        if self.maturity <= 0.0 or not math.isfinite(self.maturity):
            raise ValueError(f"Bond: maturity must be positive, got {self.maturity}")
        if not (0.0 <= self.lgd <= 1.0):
            raise ValueError(f"Bond: LGD must be in [0,1], got {self.lgd}")

    def coupon_times(self) -> List[float]:
        """Annual coupon payment times T, T-1, ... (> 0)."""
        times = []
        t = self.maturity
        while t > 1e-9:
            times.append(t)
            t -= 1.0
        return sorted(times)


@dataclass(frozen=True)
class PayerSwap:
    """Payer interest-rate swap proxy (pay fixed, receive float).

    Value = N*(1 - DF(T)) - fixed_rate*N*sum_i DF(t_i) with annual fixed payments.
    """

    inst_id: str
    notional: float
    fixed_rate: float
    maturity: float
    currency: str
    rrao: Optional[RraoFlag] = None

    def __post_init__(self) -> None:
        _check_finite("PayerSwap.notional", self.notional)
        _check_finite("PayerSwap.fixed_rate", self.fixed_rate)
        if self.notional == 0.0:
            raise ValueError("PayerSwap: notional must be non-zero")
        if self.maturity < 1.0 or not math.isfinite(self.maturity):
            raise ValueError(f"PayerSwap: maturity must be >= 1y, got {self.maturity}")

    def fixed_times(self) -> List[float]:
        times = []
        t = self.maturity
        while t > 1e-9:
            times.append(t)
            t -= 1.0
        return sorted(times)


@dataclass(frozen=True)
class EquityOption:
    """European equity option; value = position * contracts * BS(...)."""

    inst_id: str
    underlier: str
    option_type: str  # 'call' | 'put'
    position: int  # +1 long, -1 short
    contracts: float
    strike: float
    maturity: float
    currency: str
    rrao: Optional[RraoFlag] = None

    def __post_init__(self) -> None:
        if self.option_type not in ("call", "put"):
            raise ValueError(f"EquityOption: option_type must be call/put, got '{self.option_type}'")
        if self.position not in (1, -1):
            raise ValueError(f"EquityOption: position must be +1 or -1, got {self.position}")
        if self.contracts <= 0.0 or not math.isfinite(self.contracts):
            raise ValueError("EquityOption: contracts must be positive")
        if self.strike <= 0.0 or not math.isfinite(self.strike):
            raise ValueError("EquityOption: strike must be positive")
        if self.maturity < 0.0 or not math.isfinite(self.maturity):
            raise ValueError("EquityOption: maturity must be >= 0")


@dataclass(frozen=True)
class FxForward:
    """FX forward on ``pair`` = FORDOM (e.g. EURUSD): long N foreign at strike K.

    Value in domestic ccy = N * (S * DF_for(T) - K * DF_dom(T)).
    """

    inst_id: str
    pair: str
    notional: float
    strike: float
    maturity: float
    rrao: Optional[RraoFlag] = None

    def __post_init__(self) -> None:
        _check_finite("FxForward.notional", self.notional)
        if self.notional == 0.0:
            raise ValueError("FxForward: notional must be non-zero")
        if self.strike <= 0.0 or not math.isfinite(self.strike):
            raise ValueError("FxForward: strike must be positive")
        if self.maturity <= 0.0 or not math.isfinite(self.maturity):
            raise ValueError("FxForward: maturity must be positive")
        if len(self.pair) != 6:
            raise ValueError(f"FxForward: pair must be 6 chars FORDOM, got '{self.pair}'")

    @property
    def foreign(self) -> str:
        return self.pair[:3]

    @property
    def domestic(self) -> str:
        return self.pair[3:]


Instrument = Union[Bond, PayerSwap, EquityOption, FxForward]


def _parse_rrao(d: dict) -> Optional[RraoFlag]:
    r = d.get("rrao")
    if r is None:
        return None
    return RraoFlag(category=r["category"], notional=float(r["notional"]))


def instrument_from_dict(d: dict) -> Instrument:
    """Parse one instrument dict (portfolio.json schema); raise ValueError on bad input."""
    typ = d.get("type")
    if typ == "bond":
        return Bond(
            inst_id=d["id"], notional=float(d["notional"]), coupon=float(d["coupon"]),
            maturity=float(d["maturity"]), currency=d["currency"], issuer=d["issuer"],
            rating=d["rating"], lgd=float(d.get("lgd", 0.75)), rrao=_parse_rrao(d),
        )
    if typ == "payer_swap":
        return PayerSwap(
            inst_id=d["id"], notional=float(d["notional"]), fixed_rate=float(d["fixed_rate"]),
            maturity=float(d["maturity"]), currency=d["currency"], rrao=_parse_rrao(d),
        )
    if typ == "equity_option":
        return EquityOption(
            inst_id=d["id"], underlier=d["underlier"], option_type=d["option_type"],
            position=int(d["position"]), contracts=float(d["contracts"]),
            strike=float(d["strike"]), maturity=float(d["maturity"]),
            currency=d["currency"], rrao=_parse_rrao(d),
        )
    if typ == "fx_forward":
        return FxForward(
            inst_id=d["id"], pair=d["pair"], notional=float(d["notional"]),
            strike=float(d["strike"]), maturity=float(d["maturity"]), rrao=_parse_rrao(d),
        )
    raise ValueError(f"instrument_from_dict: unknown instrument type '{typ}'")


@dataclass(frozen=True)
class Desk:
    """A trading desk: name + instrument list (may be empty — capital is then zero)."""

    name: str
    display: str
    instruments: Tuple[Instrument, ...]


def load_portfolio(path: Path) -> Dict[str, Desk]:
    """Load portfolio.json -> {desk_name: Desk}; raises ValueError on schema errors."""
    with open(path) as f:
        raw = json.load(f)
    if "desks" not in raw or not isinstance(raw["desks"], list):
        raise ValueError("load_portfolio: portfolio.json must contain a 'desks' list")
    desks: Dict[str, Desk] = {}
    for d in raw["desks"]:
        name = d["name"]
        if name in desks:
            raise ValueError(f"load_portfolio: duplicate desk name '{name}'")
        insts = tuple(instrument_from_dict(x) for x in d.get("instruments", []))
        desks[name] = Desk(name=name, display=d.get("display", name), instruments=insts)
    return desks
