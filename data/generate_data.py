"""Regenerate the bundled FRTB data set (deterministic, fixed seeds).

Writes, into this directory:
  sbm_params.json  — EDUCATIONAL Basel-2019-flavored parameter set (pinned)
  portfolio.json   — 2 desks (rates; equity/FX) per the P15 spec
  curves.csv       — USD + EUR zero curves
  spots.csv        — equity spots/vols/buckets + EURUSD spot
  pnl_hypo.csv     — 260d hypothetical desk P&L + per-category columns
  pnl_rtpl.csv     — 260d risk-theoretical desk P&L
  pnl_var.csv      — 260d 99% VaR forecasts (positive numbers)
  nmrf.json        — pinned non-modellable risk factors (stressed losses)
  golden/golden.json — ~20 cross-language golden cases

The P&L series are TUNED (deterministic searches on top of a fixed-seed RNG)
so that desk1 lands PLAT Green with a green backtest, desk2 lands PLAT Amber
with EXACTLY 5 VaR exceptions.  The hand-computable 2-bucket SBM aggregation
example is validated before any golden value is written.

This is the ONLY place in the project where an RNG is used.
"""
from __future__ import annotations

import csv
import datetime as dt
import json
import math
import sys
from pathlib import Path

import numpy as np

DATA_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(DATA_DIR.parent / "python" / "src"))

import frtb  # noqa: E402
from frtb.sbm import aggregate_buckets, bucket_kb  # noqa: E402

SEED = 20250815
N_DAYS = 260
TENORS = [0.25, 0.5, 1.0, 2.0, 3.0, 5.0, 10.0, 15.0, 20.0, 30.0]


# --------------------------------------------------------------------------
# Parameter file
# --------------------------------------------------------------------------

def girr_rho_matrix() -> list:
    """rho_kl = max(exp(-theta*|Tk-Tl|/min(Tk,Tl)), 0.40), theta = 3% (pinned)."""
    n = len(TENORS)
    m = [[0.0] * n for _ in range(n)]
    for i in range(n):
        for j in range(n):
            if i == j:
                m[i][j] = 1.0
            else:
                tk, tl = TENORS[i], TENORS[j]
                m[i][j] = round(max(math.exp(-0.03 * abs(tk - tl) / min(tk, tl)), 0.40), 6)
    return m


def write_params() -> None:
    params = {
        "_note": ("EDUCATIONAL parameter set, Basel-2019-FLAVORED. Simplified bucket "
                  "structure and pinned values for teaching/cross-language testing. "
                  "NOT the official Basel text; never use for real capital."),
        "girr": {
            "tenors": TENORS,
            "delta_rw": {"0.25": 0.017, "0.5": 0.017, "1": 0.016, "2": 0.013,
                         "3": 0.012, "5": 0.011, "10": 0.011, "15": 0.011,
                         "20": 0.011, "30": 0.011},
            "delta_rho": girr_rho_matrix(),
            "_rho_note": "rho_kl = max(exp(-0.03*|Tk-Tl|/min(Tk,Tl)), 0.40), rounded 6dp",
            "vega_rw": 1.0,
            "curvature_rw": 0.017,
            "gamma": 0.5,
        },
        "equity": {
            "_note": "Basel buckets 1-4 (large cap) + 11 (indices, educational RW)",
            "buckets": {
                "1": {"delta_rw": 0.55, "vega_rw": 0.78, "rho": 0.15},
                "2": {"delta_rw": 0.60, "vega_rw": 0.78, "rho": 0.15},
                "3": {"delta_rw": 0.45, "vega_rw": 0.78, "rho": 0.15},
                "4": {"delta_rw": 0.55, "vega_rw": 0.78, "rho": 0.15},
                "11": {"delta_rw": 0.15, "vega_rw": 0.78, "rho": 0.15},
            },
            "gamma": 0.15,
        },
        "fx": {"_note": "single pinned bucket, simplification of the current rules",
               "delta_rw": 0.15, "rho": 0.6, "gamma": 0.6},
        "scenarios": {"high": 1.25, "low": 0.75},
        "drc": {"rw_by_rating": {"AAA": 0.005, "AA": 0.02, "A": 0.03, "BBB": 0.06,
                                 "BB": 0.15, "B": 0.30, "CCC": 0.50, "NR": 0.15,
                                 "D": 1.0}},
        "rrao": {"exotic": 0.01, "other": 0.001},
        "ima": {
            "alpha": 0.975,
            "rho": 0.5,
            "lh_ladder": [10, 20, 40, 60, 120],
            "category_lh": {"ir": 20, "eq": 20, "fx": 40, "cr": 60},
            "backtest_multiplier": {
                "base": 1.5,
                "amber": {"5": 1.70, "6": 1.75, "7": 1.83, "8": 1.88, "9": 1.92},
                "red": 2.0,
            },
            "plat": {"spearman_green": 0.85, "spearman_amber": 0.80,
                     "ks_green": 0.09, "ks_amber": 0.12, "k_surcharge": 0.5},
        },
    }
    with open(DATA_DIR / "sbm_params.json", "w") as f:
        json.dump(params, f, indent=2)


# --------------------------------------------------------------------------
# Market data + portfolio
# --------------------------------------------------------------------------

def write_curves_spots() -> None:
    usd = [0.0300, 0.0305, 0.0310, 0.0315, 0.0320, 0.0330, 0.0345, 0.0350, 0.0352, 0.0355]
    eur = [0.0220, 0.0224, 0.0230, 0.0238, 0.0245, 0.0255, 0.0268, 0.0273, 0.0276, 0.0280]
    with open(DATA_DIR / "curves.csv", "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["currency", "tenor", "zero_rate"])
        for t, r in zip(TENORS, usd):
            w.writerow(["USD", t, r])
        for t, r in zip(TENORS, eur):
            w.writerow(["EUR", t, r])
    with open(DATA_DIR / "spots.csv", "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["kind", "name", "spot", "vol", "div_yield", "eq_bucket"])
        w.writerow(["equity", "AAA_TECH", 100.0, 0.25, 0.01, "1"])
        w.writerow(["equity", "EURO_BANK", 48.0, 0.30, 0.03, "3"])
        w.writerow(["equity", "GLOBAL_INDEX", 250.0, 0.18, 0.02, "11"])
        w.writerow(["fx", "EURUSD", 1.085, "0.0", "0.0", ""])


def write_portfolio() -> None:
    portfolio = {
        "desks": [
            {"name": "desk1", "display": "Rates Desk", "instruments": [
                {"type": "bond", "id": "B1", "notional": 10000000, "coupon": 0.03,
                 "maturity": 5.0, "currency": "USD", "issuer": "UST-PROXY",
                 "rating": "AAA", "lgd": 0.75},
                {"type": "bond", "id": "B2", "notional": 8000000, "coupon": 0.035,
                 "maturity": 10.0, "currency": "USD", "issuer": "CORP-A",
                 "rating": "A", "lgd": 0.75},
                {"type": "bond", "id": "B3", "notional": 6000000, "coupon": 0.02,
                 "maturity": 2.0, "currency": "USD", "issuer": "CORP-B",
                 "rating": "BBB", "lgd": 0.75},
                {"type": "payer_swap", "id": "S1", "notional": 20000000,
                 "fixed_rate": 0.028, "maturity": 10.0, "currency": "USD",
                 "rrao": {"category": "other", "notional": 20000000}},
            ]},
            {"name": "desk2", "display": "Equity/FX Desk", "instruments": [
                {"type": "equity_option", "id": "O1", "underlier": "AAA_TECH",
                 "option_type": "call", "position": 1, "contracts": 50000,
                 "strike": 105.0, "maturity": 1.0, "currency": "USD"},
                {"type": "equity_option", "id": "O2", "underlier": "EURO_BANK",
                 "option_type": "put", "position": 1, "contracts": 40000,
                 "strike": 45.0, "maturity": 0.5, "currency": "USD"},
                {"type": "equity_option", "id": "O3", "underlier": "GLOBAL_INDEX",
                 "option_type": "call", "position": -1, "contracts": 30000,
                 "strike": 260.0, "maturity": 0.75, "currency": "USD",
                 "rrao": {"category": "exotic", "notional": 7800000}},
                {"type": "fx_forward", "id": "F1", "pair": "EURUSD",
                 "notional": 15000000, "strike": 1.10, "maturity": 1.0},
                {"type": "bond", "id": "HY1", "notional": 5000000, "coupon": 0.07,
                 "maturity": 4.0, "currency": "USD", "issuer": "HY-CORP",
                 "rating": "BB", "lgd": 0.75,
                 "rrao": {"category": "other", "notional": 5000000}},
            ]},
        ]
    }
    with open(DATA_DIR / "portfolio.json", "w") as f:
        json.dump(portfolio, f, indent=2)


def write_nmrf() -> None:
    nmrf = {"_note": "pinned non-modellable risk factors; SES = plain sum, "
                     "zero diversification",
            "factors": [
                {"factor": "USD_IR_basis_20y30y", "desk": "desk1", "stressed_loss": 35000.0},
                {"factor": "EM_equity_repo_rate", "desk": "desk2", "stressed_loss": 80000.0},
                {"factor": "EURUSD_vol_wings", "desk": "desk2", "stressed_loss": 40000.0},
            ]}
    with open(DATA_DIR / "nmrf.json", "w") as f:
        json.dump(nmrf, f, indent=2)


# --------------------------------------------------------------------------
# P&L series (seeded RNG + deterministic tuning)
# --------------------------------------------------------------------------

def business_days(n: int) -> list:
    d = dt.date(2025, 1, 2)
    out = []
    while len(out) < n:
        if d.weekday() < 5:
            out.append(d.isoformat())
        d += dt.timedelta(days=1)
    return out


def round2(a: np.ndarray) -> np.ndarray:
    return np.round(a, 2)


def tune_var(pnl: np.ndarray, shape: np.ndarray, target: int) -> np.ndarray:
    """Scale the VaR shape so that exactly `target` days have pnl < -VaR."""
    ratios = np.sort(-pnl / shape)[::-1]  # descending; exceptions(s) = #{ratio > s}
    hi, lo = ratios[target - 1], ratios[target]
    if not hi > lo:
        raise AssertionError("tune_var: cannot separate the target exception count")
    scale = 0.5 * (hi + lo)
    var = round2(scale * shape)
    got = int(np.sum(pnl < -var))
    if got != target:
        raise AssertionError(f"tune_var: got {got} exceptions, wanted {target}")
    return var


def tune_rtpl(hypo: np.ndarray, noise: np.ndarray, sp_lo: float, sp_hi: float,
              ks_max: float) -> np.ndarray:
    """Scale the noise so Spearman(hypo, hypo+lam*noise) lands in [sp_lo, sp_hi]
    and the KS statistic stays <= ks_max (deterministic grid search)."""
    sigma = float(np.std(hypo))
    for lam in np.arange(0.02, 2.5, 0.01):
        rtpl = round2(hypo + lam * sigma * noise)
        sp = frtb.spearman(hypo.tolist(), rtpl.tolist())
        ks = frtb.ks_statistic(hypo.tolist(), rtpl.tolist())
        if sp_lo <= sp <= sp_hi and ks <= ks_max:
            return rtpl
    raise AssertionError("tune_rtpl: no noise scale found in the search grid")


def write_pnl() -> None:
    rng = np.random.default_rng(SEED)
    dates = business_days(N_DAYS)

    d1_ir = round2(rng.normal(0.0, 42000.0, N_DAYS))
    d2_eq = round2(rng.normal(0.0, 48000.0, N_DAYS))
    d2_fx = round2(rng.normal(0.0, 22000.0, N_DAYS))
    d2_cr = round2(rng.normal(0.0, 14000.0, N_DAYS))
    d1 = d1_ir
    d2 = round2(d2_eq + d2_fx + d2_cr)

    # RTPL: desk1 tuned Green (sp >= 0.855, ks <= 0.085), desk2 tuned Amber
    # (sp in [0.805, 0.845] with ks clear of the red boundary).
    noise1 = rng.normal(0.0, 1.0, N_DAYS)
    noise2 = rng.normal(0.0, 1.0, N_DAYS)
    rtpl1 = tune_rtpl(d1, noise1, 0.870, 0.995, 0.080)
    rtpl2 = tune_rtpl(d2, noise2, 0.805, 0.845, 0.110)

    # VaR: desk1 -> 2 exceptions (green), desk2 -> exactly 5 (amber).
    t = np.arange(N_DAYS)
    shape1 = 1.0 + 0.15 * np.sin(2.0 * np.pi * t / 60.0)
    shape2 = 1.0 + 0.12 * np.cos(2.0 * np.pi * t / 45.0)
    var1 = tune_var(d1, shape1, 2)
    var2 = tune_var(d2, shape2, 5)

    def dump(path: Path, header: list, cols: list) -> None:
        with open(path, "w", newline="") as f:
            w = csv.writer(f)
            w.writerow(header)
            for i in range(N_DAYS):
                w.writerow([dates[i]] + [f"{c[i]:.2f}" for c in cols])

    dump(DATA_DIR / "pnl_hypo.csv",
         ["date", "desk1", "desk2", "desk1_ir", "desk2_eq", "desk2_fx", "desk2_cr"],
         [d1, d2, d1_ir, d2_eq, d2_fx, d2_cr])
    dump(DATA_DIR / "pnl_rtpl.csv", ["date", "desk1", "desk2"], [rtpl1, rtpl2])
    dump(DATA_DIR / "pnl_var.csv", ["date", "desk1", "desk2"], [var1, var2])


# --------------------------------------------------------------------------
# Hand-computable validation + golden cases
# --------------------------------------------------------------------------

def validate_hand_example() -> None:
    """2-bucket SBM aggregation, checkable with pencil and paper:

    bucket A: WS = [10, -5], rho = 0.5 -> K_A = sqrt(100+25-50) = sqrt(75)
    bucket B: WS = [8]                 -> K_B = 8
    gamma = 0.25, S_A = 5, S_B = 8     -> total = sqrt(75+64+2*0.25*5*8)
                                              = sqrt(159)
    """
    k_a = bucket_kb([10.0, -5.0], lambda i, j: 0.5)
    k_b = bucket_kb([8.0], lambda i, j: 0.0)
    agg = aggregate_buckets({"A": k_a, "B": k_b}, {"A": 5.0, "B": 8.0},
                            lambda b, c: 0.25)
    assert abs(k_a - math.sqrt(75.0)) < 1e-12, "hand example: K_A mismatch"
    assert abs(k_b - 8.0) < 1e-12, "hand example: K_B mismatch"
    assert abs(agg.charge - math.sqrt(159.0)) < 1e-12, "hand example: total mismatch"
    assert not agg.used_fallback


def build_golden() -> None:
    res = frtb.compute_results(DATA_DIR)
    params = res["params"]
    market = res["market"]
    desks = res["desks"]
    sa = res["sa"]
    ima = res["ima"]
    val = res["validation"]

    # ---- consistency guards on the tuned data ----------------------------
    assert ima["desk1"]["plat"].zone == "green", "desk1 must be PLAT Green"
    assert ima["desk2"]["plat"].zone == "amber", "desk2 must be PLAT Amber"
    assert ima["desk2"]["backtest"].exceptions == 5, "desk2 must have exactly 5 exceptions"
    assert ima["desk1"]["backtest"].zone == "green", "desk1 backtest must be green"
    assert val["verdicts"]["desk1"] == "approve"
    assert val["verdicts"]["desk2"] == "approve-with-conditions"
    assert val["stability_rel_change"] <= 0.25, "stability finding must not fire"

    sens_d1 = frtb.compute_sensitivities(desks["desk1"].instruments, market, params)
    ws_rates = {f"ws_{t:g}": params.girr_rw(t) * s
                for t, s in sens_d1.girr["USD"].items()}

    cases = [
        {"name": "sbm_agg_hand_2bucket",
         "inputs": {"ws_a1": 10.0, "ws_a2": -5.0, "rho_a": 0.5, "ws_b1": 8.0,
                    "gamma": 0.25},
         "expect": {"k_a": math.sqrt(75.0), "k_b": 8.0, "total": math.sqrt(159.0)},
         "tol": 1e-12},
        {"name": "girr_ws_rates_desk", "inputs": {"desk": "desk1", "currency": "USD"},
         "expect": ws_rates, "tol": 1e-8},
        {"name": "girr_kb_usd", "inputs": {"scope": "firm", "scenario": "medium"},
         "expect": {"kb": sa["firm"].sbm.kb_medium["girr"]["delta"]["USD"]},
         "tol": 1e-8},
        {"name": "equity_kb_bucket1", "inputs": {"scope": "firm", "scenario": "medium"},
         "expect": {"kb": sa["firm"].sbm.kb_medium["equity"]["delta"]["1"]},
         "tol": 1e-8},
        {"name": "sbm_firm_scenarios", "inputs": {"scope": "firm"},
         "expect": {"high": sa["firm"].sbm.scenario_totals["high"],
                    "medium": sa["firm"].sbm.scenario_totals["medium"],
                    "low": sa["firm"].sbm.scenario_totals["low"],
                    "capital": sa["firm"].sbm.capital},
         "tol": 1e-8},
        {"name": "sbm_desk_capitals", "inputs": {},
         "expect": {"desk1": sa["desk1"].sbm.capital, "desk2": sa["desk2"].sbm.capital},
         "tol": 1e-8},
        {"name": "curvature_equity_desk2", "inputs": {"desk": "desk2", "scenario": "medium"},
         "expect": {"charge": sa["desk2"].sbm.charges["equity"]["curvature"]["medium"]},
         "tol": 1e-8},
        {"name": "drc_firm", "inputs": {"scope": "firm"},
         "expect": {"charge": sa["firm"].drc, "hbr": sa["firm"].drc_hbr},
         "tol": 1e-10},
        {"name": "rrao_firm", "inputs": {"scope": "firm"},
         "expect": {"charge": sa["firm"].rrao}, "tol": 1e-12},
        {"name": "es_desk1", "inputs": {"desk": "desk1"},
         "expect": {"es_base": ima["desk1"]["es_base"], "es_lh": ima["desk1"]["es_lh"]},
         "tol": 1e-8},
        {"name": "es_desk2", "inputs": {"desk": "desk2"},
         "expect": {"es_base": ima["desk2"]["es_base"], "es_lh": ima["desk2"]["es_lh"]},
         "tol": 1e-8},
        {"name": "imcc_desks", "inputs": {},
         "expect": {"desk1": ima["desk1"]["imcc"], "desk2": ima["desk2"]["imcc"]},
         "tol": 1e-8},
        {"name": "plat_desk1", "inputs": {"desk": "desk1"},
         "expect": {"spearman": ima["desk1"]["plat"].spearman,
                    "ks": ima["desk1"]["plat"].ks, "zone": "green"},
         "tol": 1e-8},
        {"name": "plat_desk2", "inputs": {"desk": "desk2"},
         "expect": {"spearman": ima["desk2"]["plat"].spearman,
                    "ks": ima["desk2"]["plat"].ks, "zone": "amber"},
         "tol": 1e-8},
        {"name": "backtest_desk1", "inputs": {"desk": "desk1"},
         "expect": {"exceptions": ima["desk1"]["backtest"].exceptions,
                    "multiplier": ima["desk1"]["backtest"].multiplier},
         "tol": 1e-12},
        {"name": "backtest_desk2", "inputs": {"desk": "desk2"},
         "expect": {"exceptions": 5, "multiplier": 1.70}, "tol": 1e-12},
        {"name": "ses_firm", "inputs": {},
         "expect": {"charge": ima["desk1"]["ses"] + ima["desk2"]["ses"]},
         "tol": 1e-10},
        {"name": "benchmark_max_diff", "inputs": {"steps": 501},
         "expect": {"value": val["benchmark_max_diff"]}, "tol": 1e-8},
        {"name": "stability_girr_rw_up10", "inputs": {"scope": "firm"},
         "expect": {"delta_capital": val["stability_capital_rw_up10"]
                    - val["stability_base_capital"]},
         "tol": 1e-8},
        {"name": "verdict_desk1", "inputs": {"desk": "desk1"},
         "expect": {"verdict": "approve"}, "tol": 0.0},
        {"name": "verdict_desk2", "inputs": {"desk": "desk2"},
         "expect": {"verdict": "approve-with-conditions"}, "tol": 0.0},
    ]
    (DATA_DIR / "golden").mkdir(exist_ok=True)
    with open(DATA_DIR / "golden" / "golden.json", "w") as f:
        json.dump({"cases": cases}, f, indent=2)
    print(f"golden.json: {len(cases)} cases written")


def main() -> None:
    validate_hand_example()  # pencil-and-paper check BEFORE anything is written
    write_params()
    write_curves_spots()
    write_portfolio()
    write_nmrf()
    write_pnl()
    build_golden()
    print("data set regenerated (seed", SEED, ")")


if __name__ == "__main__":
    main()
