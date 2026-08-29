"""Shared fixtures: bundled data dir, pinned params, one full engine run."""
from __future__ import annotations

import sys
from pathlib import Path

import pytest

SRC = Path(__file__).resolve().parents[1] / "src"
if str(SRC) not in sys.path:
    sys.path.insert(0, str(SRC))

DATA_DIR = Path(__file__).resolve().parents[2] / "data"


@pytest.fixture(scope="session")
def data_dir() -> Path:
    return DATA_DIR


@pytest.fixture(scope="session")
def params():
    import frtb
    return frtb.load_params(DATA_DIR / "sbm_params.json")


@pytest.fixture(scope="session")
def results():
    """One full deterministic engine run over the bundled data set."""
    import frtb
    return frtb.compute_results(DATA_DIR)
