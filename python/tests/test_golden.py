"""Golden-value suite: every case in data/golden/golden.json is recomputed
from the bundled inputs and must agree within its tolerance (cross-language
contract — the same file is asserted by the C++/Rust/Java suites)."""
import json
import math

import pytest

import frtb
from frtb.sbm import aggregate_buckets, bucket_kb


@pytest.fixture(scope="module")
def golden(data_dir):
    with open(data_dir / "golden" / "golden.json") as f:
        doc = json.load(f)
    assert len(doc["cases"]) >= 20
    return doc["cases"]


def computed_values(name: str, inputs: dict, results) -> dict:
    """Recompute the expectation dict for one golden case."""
    sa = results["sa"]
    ima = results["ima"]
    val = results["validation"]
    if name == "sbm_agg_hand_2bucket":
        k_a = bucket_kb([inputs["ws_a1"], inputs["ws_a2"]],
                        lambda i, j: inputs["rho_a"])
        k_b = bucket_kb([inputs["ws_b1"]], lambda i, j: 0.0)
        agg = aggregate_buckets(
            {"A": k_a, "B": k_b},
            {"A": inputs["ws_a1"] + inputs["ws_a2"], "B": inputs["ws_b1"]},
            lambda b, c: inputs["gamma"])
        return {"k_a": k_a, "k_b": k_b, "total": agg.charge}
    if name == "girr_ws_rates_desk":
        params = results["params"]
        desk = results["desks"][inputs["desk"]]
        sens = frtb.compute_sensitivities(desk.instruments, results["market"], params)
        return {f"ws_{t:g}": params.girr_rw(t) * s
                for t, s in sens.girr[inputs["currency"]].items()}
    if name == "girr_kb_usd":
        return {"kb": sa["firm"].sbm.kb_medium["girr"]["delta"]["USD"]}
    if name == "equity_kb_bucket1":
        return {"kb": sa["firm"].sbm.kb_medium["equity"]["delta"]["1"]}
    if name == "sbm_firm_scenarios":
        return {**sa["firm"].sbm.scenario_totals, "capital": sa["firm"].sbm.capital}
    if name == "sbm_desk_capitals":
        return {"desk1": sa["desk1"].sbm.capital, "desk2": sa["desk2"].sbm.capital}
    if name == "curvature_equity_desk2":
        return {"charge": sa["desk2"].sbm.charges["equity"]["curvature"]["medium"]}
    if name == "drc_firm":
        return {"charge": sa["firm"].drc, "hbr": sa["firm"].drc_hbr}
    if name == "rrao_firm":
        return {"charge": sa["firm"].rrao}
    if name in ("es_desk1", "es_desk2"):
        d = inputs["desk"]
        return {"es_base": ima[d]["es_base"], "es_lh": ima[d]["es_lh"]}
    if name == "imcc_desks":
        return {"desk1": ima["desk1"]["imcc"], "desk2": ima["desk2"]["imcc"]}
    if name in ("plat_desk1", "plat_desk2"):
        pl = ima[inputs["desk"]]["plat"]
        return {"spearman": pl.spearman, "ks": pl.ks, "zone": pl.zone}
    if name in ("backtest_desk1", "backtest_desk2"):
        bt = ima[inputs["desk"]]["backtest"]
        return {"exceptions": bt.exceptions, "multiplier": bt.multiplier}
    if name == "ses_firm":
        return {"charge": ima["desk1"]["ses"] + ima["desk2"]["ses"]}
    if name == "benchmark_max_diff":
        return {"value": val["benchmark_max_diff"]}
    if name == "stability_girr_rw_up10":
        return {"delta_capital": val["stability_capital_rw_up10"]
                - val["stability_base_capital"]}
    if name in ("verdict_desk1", "verdict_desk2"):
        return {"verdict": val["verdicts"][inputs["desk"]]}
    raise AssertionError(f"golden case '{name}' has no recompute mapping")


def test_all_golden_cases(golden, results):
    for case in golden:
        got = computed_values(case["name"], case["inputs"], results)
        expect = case["expect"]
        tol = case["tol"]
        assert set(got) == set(expect), case["name"]
        for key, want in expect.items():
            g = got[key]
            if isinstance(want, str):
                assert g == want, f"{case['name']}.{key}: {g!r} != {want!r}"
            elif isinstance(want, int) and isinstance(g, int):
                assert g == want, f"{case['name']}.{key}: {g} != {want}"
            else:
                assert math.isfinite(g), f"{case['name']}.{key} not finite"
                assert abs(g - want) <= tol, (
                    f"{case['name']}.{key}: {g!r} vs {want!r} (tol {tol})")


def test_golden_schema_flat_scalars(golden):
    """Cross-language contract: inputs/expect values are flat scalars only."""
    for case in golden:
        for section in ("inputs", "expect"):
            for k, v in case[section].items():
                assert isinstance(v, (int, float, str)), (case["name"], k)
