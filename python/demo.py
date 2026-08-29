"""End-to-end FRTB demo: SBM capital breakdown per desk / risk class /
scenario, DRC-lite, RRAO, the IMA sketch (ES, IMCC, PLAT, backtesting, SES)
and the generated independent validation report.

Run:  cd python && PYTHONPATH=src python3 demo.py
"""
from __future__ import annotations

import sys
from pathlib import Path

SRC = Path(__file__).resolve().parent / "src"
sys.path.insert(0, str(SRC))

import frtb  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
DATA_DIR = ROOT / "data"
REPORT_PATH = ROOT / "validation_report.md"


def fmt(x: float) -> str:
    return f"{x:>14,.0f}"


def main() -> int:
    print("=" * 76)
    print("FRTB & Model Validation demo  —  EDUCATIONAL parameter set "
          "(Basel-2019-flavored)")
    print("=" * 76)
    res = frtb.compute_results(DATA_DIR)
    sa = res["sa"]
    ima = res["ima"]
    val = res["validation"]
    desks = res["desks"]

    # ---- SBM breakdown ----------------------------------------------------
    for scope in ("desk1", "desk2", "firm"):
        label = desks[scope].display if scope in desks else "FIRM (all desks)"
        print(f"\n--- SBM: {scope} ({label}) " + "-" * max(0, 44 - len(scope) - len(label)))
        print(f"{'risk class':<10} {'measure':<10} {'high':>14} {'medium':>14} {'low':>14}")
        s = sa[scope].sbm
        for rc in ("girr", "equity", "fx"):
            for m in ("delta", "vega", "curvature"):
                row = s.charges[rc][m]
                print(f"{rc:<10} {m:<10} {fmt(row['high'])} {fmt(row['medium'])} {fmt(row['low'])}")
        st = s.scenario_totals
        print(f"{'TOTAL':<10} {'':<10} {fmt(st['high'])} {fmt(st['medium'])} {fmt(st['low'])}")
        print(f"SBM capital (max over scenarios): {s.capital:,.2f}")
        print(f"DRC-lite: {sa[scope].drc:,.2f}   (HBR = {sa[scope].drc_hbr:.4f})"
              f"   RRAO: {sa[scope].rrao:,.2f}")
        print(f"SA capital (SBM + DRC + RRAO):    {sa[scope].capital:,.2f}")

    # ---- IMA sketch -------------------------------------------------------
    print("\n--- IMA sketch (per desk) " + "-" * 49)
    header = (f"{'desk':<7} {'ES base10d':>12} {'ES LH':>12} {'IMCC':>12} "
              f"{'exc':>4} {'zone':>6} {'mult':>5} {'PLAT':>6} {'SES':>10} "
              f"{'surchg':>10} {'capital':>12}")
    print(header)
    for d in ("desk1", "desk2"):
        i = ima[d]
        bt = i["backtest"]
        pl = i["plat"]
        print(f"{d:<7} {i['es_base']:>12,.0f} {i['es_lh']:>12,.0f} "
              f"{i['imcc']:>12,.0f} {bt.exceptions:>4} {bt.zone:>6} "
              f"{bt.multiplier:>5.2f} {pl.zone:>6} {i['ses']:>10,.0f} "
              f"{i['plat_surcharge']:>10,.0f} {i['capital']:>12,.0f}")
        sp = "n/a" if pl.spearman is None else f"{pl.spearman:.4f}"
        ks = "n/a" if pl.ks is None else f"{pl.ks:.4f}"
        print(f"        PLAT metrics: spearman = {sp}, KS = {ks}")

    # ---- validation -------------------------------------------------------
    print("\n--- Independent validation " + "-" * 48)
    print(f"benchmark BS vs binomial(501): max diff = {val['benchmark_max_diff']:.3e} "
          f"(tol 0.05)")
    print(f"delta vs finite difference:    max diff = {val['sensitivity_max_diff']:.3e} "
          f"(tol 1e-06)")
    print(f"stability: capital {val['stability_base_capital']:,.0f} -> "
          f"{val['stability_capital_rw_up10']:,.0f} under +10% GIRR RW "
          f"({val['stability_rel_change']:.2%} max move)")
    for d in ("desk1", "desk2"):
        rules = [f.rule_id for f in val["findings"][d]] or ["none"]
        print(f"{d}: findings = {', '.join(rules)}  ->  verdict: "
              f"{val['verdicts'][d].upper()}")

    REPORT_PATH.write_text(val["report_md"])
    print(f"\nvalidation_report.md written to {REPORT_PATH}")
    print("Overall verdicts:", ", ".join(f"{d}={val['verdicts'][d]}"
                                         for d in ("desk1", "desk2")))
    return 0


if __name__ == "__main__":
    sys.exit(main())
