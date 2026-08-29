"""Market data containers: zero curves, equity quotes, FX spots.

All bump operations return *new* objects (the engine treats markets as
immutable snapshots) so bump-and-revalue sensitivities cannot leak state.
"""
from __future__ import annotations

import csv
import math
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Dict, List, Sequence


@dataclass(frozen=True)
class Curve:
    """Continuously-compounded zero curve with linear interpolation in tenor.

    Rates are interpolated linearly between nodes and extrapolated flat
    beyond the first/last node.  Discount factor: DF(t) = exp(-z(t) * t).
    """

    tenors: tuple
    rates: tuple

    def __post_init__(self) -> None:
        if len(self.tenors) == 0 or len(self.tenors) != len(self.rates):
            raise ValueError("Curve: tenors and rates must be non-empty and equal length")
        for i in range(1, len(self.tenors)):
            if self.tenors[i] <= self.tenors[i - 1]:
                raise ValueError("Curve: tenors must be strictly increasing")
        for t, r in zip(self.tenors, self.rates):
            if not (math.isfinite(t) and math.isfinite(r)) or t <= 0.0:
                raise ValueError("Curve: tenors must be positive finite, rates finite")

    def rate(self, t: float) -> float:
        """Interpolated zero rate at time t (flat extrapolation)."""
        if not math.isfinite(t) or t < 0.0:
            raise ValueError(f"Curve.rate: invalid time {t}")
        ts, rs = self.tenors, self.rates
        if t <= ts[0]:
            return rs[0]
        if t >= ts[-1]:
            return rs[-1]
        for i in range(1, len(ts)):
            if t <= ts[i]:
                w = (t - ts[i - 1]) / (ts[i] - ts[i - 1])
                return rs[i - 1] * (1.0 - w) + rs[i] * w
        return rs[-1]  # pragma: no cover - unreachable

    def df(self, t: float) -> float:
        """Discount factor exp(-z(t)*t); DF(0) = 1."""
        if t == 0.0:
            return 1.0
        return math.exp(-self.rate(t) * t)

    def bumped_node(self, tenor: float, size: float) -> "Curve":
        """Return a curve with the zero rate at one node shifted by `size` (absolute)."""
        if tenor not in self.tenors:
            raise ValueError(f"Curve.bumped_node: tenor {tenor} is not a curve node")
        rates = tuple(r + size if tt == tenor else r for tt, r in zip(self.tenors, self.rates))
        return Curve(self.tenors, rates)

    def bumped_parallel(self, size: float) -> "Curve":
        """Return a curve with every node shifted by `size` (absolute)."""
        return Curve(self.tenors, tuple(r + size for r in self.rates))


@dataclass(frozen=True)
class EquityQuote:
    """Equity market data: spot, flat lognormal vol, dividend yield, SBM bucket."""

    spot: float
    vol: float
    div_yield: float
    bucket: str

    def __post_init__(self) -> None:
        if not math.isfinite(self.spot) or self.spot <= 0.0:
            raise ValueError(f"EquityQuote: spot must be positive finite, got {self.spot}")
        if not math.isfinite(self.vol) or self.vol < 0.0:
            raise ValueError(f"EquityQuote: vol must be >= 0, got {self.vol}")
        if not math.isfinite(self.div_yield):
            raise ValueError("EquityQuote: div_yield must be finite")


@dataclass(frozen=True)
class Market:
    """Immutable market snapshot: curves per currency, equities per name, FX spots per pair."""

    curves: Dict[str, Curve]
    equities: Dict[str, EquityQuote]
    fx: Dict[str, float]

    def curve(self, ccy: str) -> Curve:
        if ccy not in self.curves:
            raise ValueError(f"Market: no curve for currency '{ccy}'")
        return self.curves[ccy]

    def equity(self, name: str) -> EquityQuote:
        if name not in self.equities:
            raise ValueError(f"Market: no equity quote for '{name}'")
        return self.equities[name]

    def fx_spot(self, pair: str) -> float:
        if pair not in self.fx:
            raise ValueError(f"Market: no FX spot for pair '{pair}'")
        return self.fx[pair]

    # --- bump helpers (all return new Market objects) -------------------
    def with_curve(self, ccy: str, curve: Curve) -> "Market":
        curves = dict(self.curves)
        curves[ccy] = curve
        return Market(curves, self.equities, self.fx)

    def bump_curve_node(self, ccy: str, tenor: float, size: float) -> "Market":
        return self.with_curve(ccy, self.curve(ccy).bumped_node(tenor, size))

    def bump_curve_parallel(self, ccy: str, size: float) -> "Market":
        return self.with_curve(ccy, self.curve(ccy).bumped_parallel(size))

    def bump_equity_spot(self, name: str, rel: float) -> "Market":
        """Relative spot bump: S -> S * (1 + rel)."""
        q = self.equity(name)
        eqs = dict(self.equities)
        eqs[name] = replace(q, spot=q.spot * (1.0 + rel))
        return Market(self.curves, eqs, self.fx)

    def bump_equity_vol(self, name: str, size: float) -> "Market":
        """Absolute vol bump: sigma -> sigma + size."""
        q = self.equity(name)
        eqs = dict(self.equities)
        eqs[name] = replace(q, vol=q.vol + size)
        return Market(self.curves, eqs, self.fx)

    def bump_fx(self, pair: str, rel: float) -> "Market":
        """Relative FX spot bump: S -> S * (1 + rel)."""
        s = self.fx_spot(pair)
        fx = dict(self.fx)
        fx[pair] = s * (1.0 + rel)
        return Market(self.curves, self.equities, fx)


def load_market(curves_csv: Path, spots_csv: Path) -> Market:
    """Load a Market from ``curves.csv`` (currency,tenor,zero_rate) and
    ``spots.csv`` (kind,name,spot,vol,div_yield,eq_bucket)."""
    by_ccy: Dict[str, List] = {}
    with open(curves_csv, newline="") as f:
        for row in csv.DictReader(f):
            by_ccy.setdefault(row["currency"], []).append(
                (float(row["tenor"]), float(row["zero_rate"]))
            )
    curves = {}
    for ccy, pts in by_ccy.items():
        pts.sort()
        curves[ccy] = Curve(tuple(p[0] for p in pts), tuple(p[1] for p in pts))
    if not curves:
        raise ValueError(f"load_market: no curves in {curves_csv}")

    equities: Dict[str, EquityQuote] = {}
    fx: Dict[str, float] = {}
    with open(spots_csv, newline="") as f:
        for row in csv.DictReader(f):
            kind = row["kind"]
            if kind == "equity":
                equities[row["name"]] = EquityQuote(
                    spot=float(row["spot"]),
                    vol=float(row["vol"]),
                    div_yield=float(row["div_yield"]),
                    bucket=row["eq_bucket"],
                )
            elif kind == "fx":
                fx[row["name"]] = float(row["spot"])
            else:
                raise ValueError(f"load_market: unknown kind '{kind}' in {spots_csv}")
    return Market(curves, equities, fx)
