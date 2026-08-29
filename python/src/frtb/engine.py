"""End-to-end orchestration: load the bundled data set, compute SA (SBM +
DRC + RRAO), the IMA sketch (ES/IMCC, backtesting, PLAT, SES) and the
independent validation results for every desk and for the firm.

Fully deterministic: pure revaluation and closed-form statistics, no RNG.
"""
from __future__ import annotations

import csv
import json
import math
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Mapping, Optional, Sequence, Tuple

from .ima import backtest, es_base_10d, es_lh_scaled, ima_capital, imcc, ses
from .instruments import Desk, Instrument, load_portfolio
from .market import Market, load_market
from .params import SbmParams, load_params
from .plat import plat_surcharge, plat_test
from .sa import (SbmResult, drc_charge, drc_positions_from_instruments,
                 rrao_charge, sbm_capital)
from .sensitivities import compute_sensitivities
from .validation import (DeskCheckInputs, benchmark_max_diff, classify_findings,
                         data_quality, overall_verdict, render_report,
                         sensitivity_max_diff)


def load_pnl_csv(path: Path) -> Dict[str, List[float]]:
    """Load a P&L CSV (date + numeric columns) -> {column: series}.

    Empty cells become NaN (picked up by the data-quality check).
    """
    with open(path, newline="") as f:
        reader = csv.DictReader(f)
        if reader.fieldnames is None or "date" not in reader.fieldnames:
            raise ValueError(f"load_pnl_csv: {path} must have a 'date' column")
        cols = [c for c in reader.fieldnames if c != "date"]
        out: Dict[str, List[float]] = {c: [] for c in cols}
        for row in reader:
            for c in cols:
                cell = row[c].strip()
                out[c].append(float(cell) if cell else math.nan)
    if not out or not next(iter(out.values())):
        raise ValueError(f"load_pnl_csv: {path} contains no data rows")
    return out


def desk_categories(desk: str, hypo: Mapping[str, List[float]]) -> Dict[str, List[float]]:
    """Extract the per-category P&L columns '<desk>_<cat>' for one desk."""
    prefix = desk + "_"
    return {c[len(prefix):]: v for c, v in hypo.items() if c.startswith(prefix)}


@dataclass(frozen=True)
class SaScope:
    """SA results for one scope (a desk or the whole firm)."""

    sbm: SbmResult
    drc: float
    drc_hbr: float
    rrao: float

    @property
    def capital(self) -> float:
        return self.sbm.capital + self.drc + self.rrao


def compute_sa(instruments: Sequence[Instrument], market: Market,
               params: SbmParams) -> SaScope:
    """SA capital for one instrument scope: SBM + DRC-lite + RRAO."""
    sens = compute_sensitivities(instruments, market, params)
    sbm = sbm_capital(sens, market, params)
    drc = drc_charge(drc_positions_from_instruments(instruments, market), params)
    return SaScope(sbm=sbm, drc=drc.charge, drc_hbr=drc.hbr,
                   rrao=rrao_charge(instruments, params))


def compute_results(data_dir: Path) -> Dict[str, object]:
    """Compute the full result tree from the bundled data directory.

    Returns a nested dict with keys 'sa' (per scope), 'sens', 'ima' (per desk),
    'validation' (checks, findings, verdicts, rendered report markdown).
    """
    data_dir = Path(data_dir)
    params = load_params(data_dir / "sbm_params.json")
    market = load_market(data_dir / "curves.csv", data_dir / "spots.csv")
    desks = load_portfolio(data_dir / "portfolio.json")
    hypo = load_pnl_csv(data_dir / "pnl_hypo.csv")
    rtpl = load_pnl_csv(data_dir / "pnl_rtpl.csv")
    var99 = load_pnl_csv(data_dir / "pnl_var.csv")
    with open(data_dir / "nmrf.json") as f:
        nmrf = json.load(f)["factors"]

    desk_names = sorted(desks)
    all_instruments: List[Instrument] = [i for d in desk_names
                                         for i in desks[d].instruments]

    # ---- SA per desk + firm ----------------------------------------------
    sa: Dict[str, SaScope] = {d: compute_sa(desks[d].instruments, market, params)
                              for d in desk_names}
    sa["firm"] = compute_sa(all_instruments, market, params)
    sens_firm = compute_sensitivities(all_instruments, market, params)

    # ---- IMA per desk -----------------------------------------------------
    ima: Dict[str, Dict[str, object]] = {}
    for d in desk_names:
        cats = desk_categories(d, hypo)
        if not cats:
            raise ValueError(f"compute_results: no category P&L columns for desk '{d}'")
        full = hypo[d]
        es_b = es_base_10d(full, params.ima_alpha)
        es_lh = es_lh_scaled(full, cats, params.category_lh, params.lh_ladder,
                             params.ima_alpha)
        imcc_d = imcc(full, cats, params)
        bt = backtest(full, var99[d], params)
        pl = plat_test(full, rtpl[d], params)
        ses_d = ses([e for e in nmrf if e["desk"] == d])
        core = ima_capital(imcc_d, bt.multiplier, ses_d)
        surcharge = plat_surcharge(pl.zone, sa[d].capital, core, params)
        ima[d] = {
            "es_base": es_b, "es_lh": es_lh, "imcc": imcc_d,
            "backtest": bt, "plat": pl, "ses": ses_d,
            "capital_core": core, "plat_surcharge": surcharge,
            "capital": core + surcharge,
        }

    # ---- validation checks ------------------------------------------------
    bench = benchmark_max_diff()
    sens_diff = sensitivity_max_diff()
    base_cap = sa["firm"].sbm.capital
    cap_up = sbm_capital(sens_firm, market, params.with_girr_delta_rw_scaled(1.1)).capital
    cap_dn = sbm_capital(sens_firm, market, params.with_girr_delta_rw_scaled(0.9)).capital
    stability_rel = (max(abs(cap_up - base_cap), abs(cap_dn - base_cap)) / base_cap
                     if base_cap > 0.0 else 0.0)
    dq = {d: data_quality(hypo[d]) for d in desk_names}

    findings = {}
    verdicts = {}
    for d in desk_names:
        inputs = DeskCheckInputs(
            benchmark_max_diff=bench,
            sensitivity_max_diff=sens_diff,
            stability_rel_change=stability_rel,
            backtest_zone=ima[d]["backtest"].zone,   # type: ignore[union-attr]
            plat_zone=ima[d]["plat"].zone,           # type: ignore[union-attr]
            stale_days=dq[d]["stale_days"],
            gaps=dq[d]["gaps"],
        )
        findings[d] = classify_findings(inputs)
        verdicts[d] = overall_verdict(findings[d])

    results: Dict[str, object] = {
        "params": params,
        "market": market,
        "desks": desks,
        "sa": sa,
        "sens_firm": sens_firm,
        "ima": ima,
        "validation": {
            "benchmark_max_diff": bench,
            "sensitivity_max_diff": sens_diff,
            "stability_base_capital": base_cap,
            "stability_capital_rw_up10": cap_up,
            "stability_capital_rw_dn10": cap_dn,
            "stability_rel_change": stability_rel,
            "data_quality": dq,
            "findings": findings,
            "verdicts": verdicts,
        },
    }
    results["validation"]["report_md"] = render_report(results)  # type: ignore[index]
    return results
