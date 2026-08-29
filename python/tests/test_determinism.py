"""Determinism: no RNG anywhere at runtime, bit-identical repeated runs."""
from pathlib import Path

import frtb

SRC_DIR = Path(frtb.__file__).resolve().parent


def test_no_rng_in_package_source():
    """The frtb package must not import or use any RNG (spec requirement)."""
    banned = ("import random", "from random", "np.random", "numpy.random",
              "default_rng", "RandomState")
    for py in sorted(SRC_DIR.glob("*.py")):
        text = py.read_text()
        for token in banned:
            assert token not in text, f"{py.name} contains '{token}'"


def test_repeated_runs_bit_identical(data_dir, results):
    """A second full engine run must reproduce every number exactly."""
    again = frtb.compute_results(data_dir)
    for scope in ("desk1", "desk2", "firm"):
        assert again["sa"][scope].sbm.capital == results["sa"][scope].sbm.capital
        assert again["sa"][scope].sbm.scenario_totals == results["sa"][scope].sbm.scenario_totals
        assert again["sa"][scope].drc == results["sa"][scope].drc
        assert again["sa"][scope].rrao == results["sa"][scope].rrao
    for desk in ("desk1", "desk2"):
        for key in ("es_base", "es_lh", "imcc", "ses", "capital"):
            assert again["ima"][desk][key] == results["ima"][desk][key]
        assert again["ima"][desk]["plat"] == results["ima"][desk]["plat"]
        assert again["ima"][desk]["backtest"] == results["ima"][desk]["backtest"]
    assert again["validation"]["report_md"] == results["validation"]["report_md"]
