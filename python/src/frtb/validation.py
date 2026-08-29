"""Independent model validation framework.

Checks (all pinned):

* Benchmarking     — project BS pricer vs an independent CRR binomial lattice
                     (501 steps) on a pinned strike/maturity/call-put grid;
                     PASS iff max abs price diff <= 0.05.
* Sensitivity      — analytic BS delta vs central finite difference
                     (h = 1e-4 * S) on the same grid; PASS iff max diff <= 1e-6.
* Stability        — SBM capital recomputed with GIRR delta RWs x0.9 / x1.1;
                     finding if |delta capital| / base capital > 0.25.
* Backtesting      — desk VaR backtest zone (from frtb.ima).
* PLAT             — desk PLAT zone (from frtb.plat).
* Data quality     — staleness: # of zero-change days > 15 -> finding;
                     gaps: any missing (NaN) value -> finding.

Findings classification (pinned rule table, see FINDING_RULES):
  High   -> verdict 'reject'
  Medium -> verdict 'approve-with-conditions' (if no High)
  else   -> 'approve'
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Callable, Dict, List, Mapping, Optional, Sequence, Tuple

from .pricers import binomial_price, bs_delta, bs_price

# ---- pinned check parameters ---------------------------------------------
BENCH_STEPS = 501
BENCH_TOL = 0.05
SENS_TOL = 1e-6
STABILITY_THRESHOLD = 0.25   # |delta capital| / capital
STALENESS_THRESHOLD = 15     # zero-change days
BENCH_GRID_STRIKES = (70.0, 85.0, 100.0, 115.0, 130.0)
BENCH_GRID_MATURITIES = (0.25, 0.5, 1.0, 2.0)
BENCH_SPOT, BENCH_RATE, BENCH_DIV, BENCH_VOL = 100.0, 0.03, 0.01, 0.2

SEVERITIES = ("High", "Medium", "Low")
VERDICTS = ("approve", "approve-with-conditions", "reject")


@dataclass(frozen=True)
class Finding:
    """One validation finding: pinned rule id, severity, human description."""

    rule_id: str
    severity: str
    description: str

    def __post_init__(self) -> None:
        if self.severity not in SEVERITIES:
            raise ValueError(f"Finding: severity must be one of {SEVERITIES}")


# --------------------------------------------------------------------------
# Checks
# --------------------------------------------------------------------------

def benchmark_max_diff() -> float:
    """Max abs diff |BS - binomial(501)| over the pinned option grid."""
    worst = 0.0
    for k in BENCH_GRID_STRIKES:
        for t in BENCH_GRID_MATURITIES:
            for call in (True, False):
                a = bs_price(BENCH_SPOT, k, t, BENCH_RATE, BENCH_DIV, BENCH_VOL, call)
                b = binomial_price(BENCH_SPOT, k, t, BENCH_RATE, BENCH_DIV, BENCH_VOL,
                                   call, BENCH_STEPS)
                worst = max(worst, abs(a - b))
    return worst


def sensitivity_max_diff() -> float:
    """Max abs diff between analytic BS delta and a central finite difference."""
    h = 1e-4 * BENCH_SPOT
    worst = 0.0
    for k in BENCH_GRID_STRIKES:
        for t in BENCH_GRID_MATURITIES:
            for call in (True, False):
                analytic = bs_delta(BENCH_SPOT, k, t, BENCH_RATE, BENCH_DIV, BENCH_VOL, call)
                up = bs_price(BENCH_SPOT + h, k, t, BENCH_RATE, BENCH_DIV, BENCH_VOL, call)
                dn = bs_price(BENCH_SPOT - h, k, t, BENCH_RATE, BENCH_DIV, BENCH_VOL, call)
                worst = max(worst, abs(analytic - (up - dn) / (2.0 * h)))
    return worst


def data_quality(series: Sequence[float]) -> Dict[str, int]:
    """Staleness (# zero-change days) and gaps (# NaN values) of one series."""
    if len(series) < 2:
        raise ValueError("data_quality: need at least 2 observations")
    gaps = sum(1 for v in series if isinstance(v, float) and math.isnan(v))
    clean = [v for v in series if not (isinstance(v, float) and math.isnan(v))]
    stale = sum(1 for a, b in zip(clean, clean[1:]) if b - a == 0.0)
    return {"stale_days": stale, "gaps": gaps}


# --------------------------------------------------------------------------
# Findings classification (pinned rule table)
# --------------------------------------------------------------------------

@dataclass(frozen=True)
class DeskCheckInputs:
    """Everything the rule table needs to classify one desk."""

    benchmark_max_diff: float
    sensitivity_max_diff: float
    stability_rel_change: float   # max(|dCap(x1.1)|, |dCap(x0.9)|) / base capital
    backtest_zone: str
    plat_zone: str
    stale_days: int
    gaps: int


# rule table: (rule_id, severity, predicate, description factory)
FINDING_RULES: Tuple[Tuple[str, str, Callable[[DeskCheckInputs], bool],
                           Callable[[DeskCheckInputs], str]], ...] = (
    ("BENCH-01", "High", lambda c: c.benchmark_max_diff > BENCH_TOL,
     lambda c: f"Pricing benchmark max diff {c.benchmark_max_diff:.6g} exceeds tolerance {BENCH_TOL}"),
    ("SENS-01", "High", lambda c: c.sensitivity_max_diff > SENS_TOL,
     lambda c: f"Analytic vs FD delta max diff {c.sensitivity_max_diff:.6g} exceeds {SENS_TOL}"),
    ("BT-01", "High", lambda c: c.backtest_zone == "red",
     lambda c: "VaR backtest in RED zone"),
    ("BT-02", "Medium", lambda c: c.backtest_zone == "amber",
     lambda c: "VaR backtest in AMBER zone"),
    ("PLAT-01", "High", lambda c: c.plat_zone == "red",
     lambda c: "PLAT in RED zone"),
    ("PLAT-02", "Medium", lambda c: c.plat_zone == "amber",
     lambda c: "PLAT in AMBER zone"),
    ("STAB-01", "Medium", lambda c: c.stability_rel_change > STABILITY_THRESHOLD,
     lambda c: f"Capital moves {c.stability_rel_change:.1%} under +/-10% GIRR RW "
               f"(threshold {STABILITY_THRESHOLD:.0%})"),
    ("DQ-01", "Medium", lambda c: c.stale_days > STALENESS_THRESHOLD,
     lambda c: f"{c.stale_days} zero-change days exceed staleness threshold {STALENESS_THRESHOLD}"),
    ("DQ-02", "Low", lambda c: c.gaps > 0,
     lambda c: f"{c.gaps} missing values in the P&L series"),
)


def classify_findings(inputs: DeskCheckInputs) -> List[Finding]:
    """Apply the pinned rule table; returns findings in table order."""
    out: List[Finding] = []
    for rule_id, severity, pred, describe in FINDING_RULES:
        if pred(inputs):
            out.append(Finding(rule_id=rule_id, severity=severity, description=describe(inputs)))
    return out


def overall_verdict(findings: Sequence[Finding]) -> str:
    """Pinned verdict rule: any High -> reject; any Medium -> approve-with-conditions."""
    sev = {f.severity for f in findings}
    if "High" in sev:
        return "reject"
    if "Medium" in sev:
        return "approve-with-conditions"
    return "approve"


# --------------------------------------------------------------------------
# Report generation (structured dict -> markdown)
# --------------------------------------------------------------------------

REPORT_SECTIONS = (
    "1. Scope & Overview",
    "2. Pricing Benchmark",
    "3. Sensitivity Verification",
    "4. Capital Stability",
    "5. VaR Backtesting",
    "6. P&L Attribution (PLAT)",
    "7. Data Quality",
    "8. NMRF / SES",
    "9. Findings",
    "10. Overall Verdict",
)


def _fmt(x: float) -> str:
    return f"{x:,.2f}"


def render_report(results: Mapping[str, object]) -> str:
    """Render the validation report markdown from the engine's results dict.

    Expects the structure produced by ``frtb.engine.compute_results`` and
    always emits every section in REPORT_SECTIONS (tested by string-contains).
    """
    ima: Mapping[str, Mapping[str, object]] = results["ima"]        # type: ignore[assignment]
    val: Mapping[str, object] = results["validation"]               # type: ignore[assignment]
    desks = sorted(ima)
    lines: List[str] = []
    add = lines.append

    add("# Independent Model Validation Report")
    add("")
    add("> Educational FRTB implementation — Basel-2019-flavored pinned parameter set.")
    add("> NOT a compliant capital engine; for teaching and testing only.")
    add("")
    add(f"## {REPORT_SECTIONS[0]}")
    add("")
    add(f"Desks in scope: {', '.join(desks)}. Framework: SBM + DRC + RRAO (SA) and "
        "ES/IMCC + PLAT + backtesting + SES (IMA sketch).")
    add("")

    add(f"## {REPORT_SECTIONS[1]}")
    add("")
    add("| metric | value | threshold | result |")
    add("|---|---|---|---|")
    bmd = float(val["benchmark_max_diff"])  # type: ignore[arg-type]
    add(f"| max abs diff BS vs binomial({BENCH_STEPS}) | {bmd:.3e} | {BENCH_TOL} | "
        f"{'PASS' if bmd <= BENCH_TOL else 'FAIL'} |")
    add("")

    add(f"## {REPORT_SECTIONS[2]}")
    add("")
    smd = float(val["sensitivity_max_diff"])  # type: ignore[arg-type]
    add(f"Analytic BS delta vs central finite difference: max abs diff {smd:.3e} "
        f"(threshold {SENS_TOL}) — {'PASS' if smd <= SENS_TOL else 'FAIL'}.")
    add("")

    add(f"## {REPORT_SECTIONS[3]}")
    add("")
    add("| scenario | SBM capital | change vs base |")
    add("|---|---|---|")
    base_cap = float(val["stability_base_capital"])          # type: ignore[arg-type]
    up_cap = float(val["stability_capital_rw_up10"])         # type: ignore[arg-type]
    dn_cap = float(val["stability_capital_rw_dn10"])         # type: ignore[arg-type]
    add(f"| base | {_fmt(base_cap)} | — |")
    add(f"| GIRR delta RW x1.1 | {_fmt(up_cap)} | {_fmt(up_cap - base_cap)} |")
    add(f"| GIRR delta RW x0.9 | {_fmt(dn_cap)} | {_fmt(dn_cap - base_cap)} |")
    add("")

    add(f"## {REPORT_SECTIONS[4]}")
    add("")
    add("| desk | exceptions | zone | multiplier |")
    add("|---|---|---|---|")
    for d in desks:
        bt = ima[d]["backtest"]  # type: ignore[index]
        add(f"| {d} | {bt.exceptions} | {bt.zone} | {bt.multiplier:.2f} |")
    add("")

    add(f"## {REPORT_SECTIONS[5]}")
    add("")
    add("| desk | spearman | KS | zone | surcharge |")
    add("|---|---|---|---|---|")
    for d in desks:
        pl = ima[d]["plat"]  # type: ignore[index]
        sp = "n/a" if pl.spearman is None else f"{pl.spearman:.4f}"
        ks = "n/a" if pl.ks is None else f"{pl.ks:.4f}"
        add(f"| {d} | {sp} | {ks} | {pl.zone} | {_fmt(float(ima[d]['plat_surcharge']))} |")  # type: ignore[index]
    add("")

    add(f"## {REPORT_SECTIONS[6]}")
    add("")
    add("| desk | zero-change days | gaps |")
    add("|---|---|---|")
    dq: Mapping[str, Mapping[str, int]] = val["data_quality"]  # type: ignore[assignment]
    for d in desks:
        add(f"| {d} | {dq[d]['stale_days']} | {dq[d]['gaps']} |")
    add("")

    add(f"## {REPORT_SECTIONS[7]}")
    add("")
    add("| desk | SES |")
    add("|---|---|")
    for d in desks:
        add(f"| {d} | {_fmt(float(ima[d]['ses']))} |")  # type: ignore[index]
    add("")

    add(f"## {REPORT_SECTIONS[8]}")
    add("")
    findings: Mapping[str, Sequence[Finding]] = val["findings"]  # type: ignore[assignment]
    any_finding = False
    for d in desks:
        for f in findings[d]:
            add(f"- **{f.severity}** [{f.rule_id}] ({d}): {f.description}")
            any_finding = True
    if not any_finding:
        add("- No findings.")
    add("")

    add(f"## {REPORT_SECTIONS[9]}")
    add("")
    verdicts: Mapping[str, str] = val["verdicts"]  # type: ignore[assignment]
    for d in desks:
        add(f"- {d}: **{verdicts[d]}**")
    add("")
    return "\n".join(lines)
