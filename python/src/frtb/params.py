"""Pinned regulatory parameter set (loaded from data/sbm_params.json).

IMPORTANT: the parameter values are an EDUCATIONAL, Basel-2019-flavored set —
simplified bucket structure, pinned correlations, no securitisation buckets.
They are NOT the official Basel text and must not be used for real capital.
"""
from __future__ import annotations

import json
import math
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Sequence, Tuple


@dataclass(frozen=True)
class EquityBucketParams:
    """Per-bucket equity parameters: delta RW, vega RW, intra-bucket rho."""

    delta_rw: float
    vega_rw: float
    rho: float


@dataclass(frozen=True)
class SbmParams:
    """Full pinned parameter set for SBM + DRC + RRAO + IMA + PLAT.

    Loaded from ``data/sbm_params.json``; all lookups raise ValueError with a
    clear message when a bucket / rating / tenor is missing (spec edge case).
    """

    # GIRR
    girr_tenors: Tuple[float, ...]
    girr_delta_rw: Dict[float, float]
    girr_rho: Tuple[Tuple[float, ...], ...]   # tenor x tenor correlation matrix
    girr_vega_rw: float
    girr_curvature_rw: float
    girr_gamma: float                          # cross-currency gamma
    # Equity
    equity_buckets: Dict[str, EquityBucketParams]
    equity_gamma: float
    # FX
    fx_delta_rw: float
    fx_rho: float
    fx_gamma: float
    # scenario scalers
    scenario_high: float
    scenario_low: float
    # DRC
    drc_rw_by_rating: Dict[str, float]
    # RRAO
    rrao_rates: Dict[str, float]
    # IMA
    ima_alpha: float
    ima_rho: float
    lh_ladder: Tuple[int, ...]
    category_lh: Dict[str, int]
    backtest_amber_multipliers: Dict[int, float]  # exceptions 5..9 -> multiplier
    backtest_base_multiplier: float
    backtest_red_multiplier: float
    # PLAT
    plat_spearman_green: float
    plat_spearman_amber: float
    plat_ks_green: float
    plat_ks_amber: float
    plat_k_surcharge: float

    # ---------------------------------------------------------------- lookups
    def girr_rw(self, tenor: float) -> float:
        if tenor not in self.girr_delta_rw:
            raise ValueError(f"SbmParams: no GIRR delta risk weight for tenor {tenor}")
        return self.girr_delta_rw[tenor]

    def girr_rho_kl(self, i: int, j: int) -> float:
        return self.girr_rho[i][j]

    def equity_bucket(self, bucket: str) -> EquityBucketParams:
        if bucket not in self.equity_buckets:
            raise ValueError(f"SbmParams: unknown equity bucket '{bucket}' "
                             f"(known: {sorted(self.equity_buckets)})")
        return self.equity_buckets[bucket]

    def drc_rw(self, rating: str) -> float:
        if rating not in self.drc_rw_by_rating:
            raise ValueError(f"SbmParams: no DRC risk weight for rating '{rating}' "
                             f"(known: {sorted(self.drc_rw_by_rating)})")
        return self.drc_rw_by_rating[rating]

    def rrao_rate(self, category: str) -> float:
        if category not in self.rrao_rates:
            raise ValueError(f"SbmParams: unknown RRAO category '{category}'")
        return self.rrao_rates[category]

    def with_girr_delta_rw_scaled(self, factor: float) -> "SbmParams":
        """Copy with every GIRR delta RW scaled by `factor` (stability check ±10%)."""
        if factor <= 0.0 or not math.isfinite(factor):
            raise ValueError(f"with_girr_delta_rw_scaled: bad factor {factor}")
        d = {k: v * factor for k, v in self.girr_delta_rw.items()}
        return SbmParams(**{**self.__dict__, "girr_delta_rw": d})


def _require(d: dict, key: str, ctx: str):
    if key not in d:
        raise ValueError(f"sbm_params.json: missing '{key}' in {ctx}")
    return d[key]


def load_params(path: Path) -> SbmParams:
    """Load and validate the pinned parameter file; ValueError on any missing key."""
    with open(path) as f:
        raw = json.load(f)

    girr = _require(raw, "girr", "root")
    tenors = tuple(float(t) for t in _require(girr, "tenors", "girr"))
    delta_rw = {float(k): float(v) for k, v in _require(girr, "delta_rw", "girr").items()}
    for t in tenors:
        if t not in delta_rw:
            raise ValueError(f"sbm_params.json: girr.delta_rw missing tenor {t}")
    rho_raw = _require(girr, "delta_rho", "girr")
    n = len(tenors)
    if len(rho_raw) != n or any(len(row) != n for row in rho_raw):
        raise ValueError("sbm_params.json: girr.delta_rho must be a square tenor x tenor matrix")
    rho = tuple(tuple(float(x) for x in row) for row in rho_raw)
    for i in range(n):
        if abs(rho[i][i] - 1.0) > 1e-12:
            raise ValueError("sbm_params.json: girr.delta_rho diagonal must be 1")

    eq = _require(raw, "equity", "root")
    ebuckets = {}
    for b, p in _require(eq, "buckets", "equity").items():
        ebuckets[str(b)] = EquityBucketParams(
            delta_rw=float(_require(p, "delta_rw", f"equity bucket {b}")),
            vega_rw=float(_require(p, "vega_rw", f"equity bucket {b}")),
            rho=float(_require(p, "rho", f"equity bucket {b}")),
        )

    fx = _require(raw, "fx", "root")
    scen = _require(raw, "scenarios", "root")
    drc = _require(raw, "drc", "root")
    rrao = _require(raw, "rrao", "root")
    ima = _require(raw, "ima", "root")
    bt = _require(ima, "backtest_multiplier", "ima")
    plat = _require(ima, "plat", "ima")

    return SbmParams(
        girr_tenors=tenors,
        girr_delta_rw=delta_rw,
        girr_rho=rho,
        girr_vega_rw=float(_require(girr, "vega_rw", "girr")),
        girr_curvature_rw=float(_require(girr, "curvature_rw", "girr")),
        girr_gamma=float(_require(girr, "gamma", "girr")),
        equity_buckets=ebuckets,
        equity_gamma=float(_require(eq, "gamma", "equity")),
        fx_delta_rw=float(_require(fx, "delta_rw", "fx")),
        fx_rho=float(_require(fx, "rho", "fx")),
        fx_gamma=float(_require(fx, "gamma", "fx")),
        scenario_high=float(_require(scen, "high", "scenarios")),
        scenario_low=float(_require(scen, "low", "scenarios")),
        drc_rw_by_rating={str(k): float(v) for k, v in _require(drc, "rw_by_rating", "drc").items()},
        rrao_rates={str(k): float(v) for k, v in rrao.items()},
        ima_alpha=float(_require(ima, "alpha", "ima")),
        ima_rho=float(_require(ima, "rho", "ima")),
        lh_ladder=tuple(int(x) for x in _require(ima, "lh_ladder", "ima")),
        category_lh={str(k): int(v) for k, v in _require(ima, "category_lh", "ima").items()},
        backtest_amber_multipliers={int(k): float(v) for k, v in _require(bt, "amber", "backtest").items()},
        backtest_base_multiplier=float(_require(bt, "base", "backtest")),
        backtest_red_multiplier=float(_require(bt, "red", "backtest")),
        plat_spearman_green=float(_require(plat, "spearman_green", "plat")),
        plat_spearman_amber=float(_require(plat, "spearman_amber", "plat")),
        plat_ks_green=float(_require(plat, "ks_green", "plat")),
        plat_ks_amber=float(_require(plat, "ks_amber", "plat")),
        plat_k_surcharge=float(_require(plat, "k_surcharge", "plat")),
    )
