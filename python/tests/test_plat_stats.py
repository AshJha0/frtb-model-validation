"""PLAT zones/surcharge and the native Spearman/KS implementations
(cross-checked against scipy — scipy is used ONLY here, in tests)."""
import math

import pytest
from scipy import stats as sps

from frtb.plat import plat_surcharge, plat_test, plat_zone_from_metrics
from frtb.stats import average_ranks, ks_statistic, pearson, spearman


def series(n: int, f1: float, f2: float, scale: float = 1.0):
    """Deterministic pseudo-noisy series (no RNG in the test suite)."""
    return [scale * (math.sin(f1 * i) + 0.5 * math.cos(f2 * i * i)) for i in range(n)]


class TestNativeStats:
    def test_spearman_vs_scipy_grid(self):
        # property-style loop over several deterministic series pairs
        for n in (25, 60, 120):
            for (a1, a2), (b1, b2) in [((1.1, 0.3), (0.9, 0.7)),
                                       ((2.3, 0.11), (2.3, 0.13)),
                                       ((0.5, 1.7), (3.1, 0.2))]:
                x = series(n, a1, a2)
                y = [u + v for u, v in zip(series(n, b1, b2), x)]
                want = sps.spearmanr(x, y).statistic
                assert spearman(x, y) == pytest.approx(want, abs=1e-12)

    def test_spearman_with_ties_vs_scipy(self):
        x = [1.0, 2.0, 2.0, 3.0, 3.0, 3.0, 4.0, 0.0]
        y = [5.0, 5.0, 6.0, 7.0, 8.0, 8.0, 9.0, 4.0]
        assert spearman(x, y) == pytest.approx(sps.spearmanr(x, y).statistic, abs=1e-12)

    def test_average_ranks_ties(self):
        assert average_ranks([10.0, 20.0, 20.0, 30.0]) == [1.0, 2.5, 2.5, 4.0]

    def test_perfect_monotone(self):
        x = [1.0, 2.0, 3.0, 4.0]
        assert spearman(x, [10.0, 20.0, 30.0, 40.0]) == pytest.approx(1.0, abs=1e-15)
        assert spearman(x, [4.0, 3.0, 2.0, 1.0]) == pytest.approx(-1.0, abs=1e-15)

    def test_ks_vs_scipy_grid(self):
        for n, m in ((30, 30), (50, 80), (120, 40)):
            x = series(n, 1.3, 0.21)
            y = [v + 0.3 for v in series(m, 0.7, 0.4)]
            want = sps.ks_2samp(x, y).statistic
            assert ks_statistic(x, y) == pytest.approx(want, abs=1e-12)

    def test_ks_with_ties_vs_scipy(self):
        x = [1.0, 1.0, 2.0, 3.0]
        y = [1.0, 2.0, 2.0, 4.0]
        assert ks_statistic(x, y) == pytest.approx(sps.ks_2samp(x, y).statistic, abs=1e-12)

    def test_ks_identical_zero(self):
        x = series(40, 1.0, 0.5)
        assert ks_statistic(x, list(x)) == 0.0

    def test_errors(self):
        with pytest.raises(ValueError, match="equal length"):
            spearman([1.0, 2.0, 3.0], [1.0, 2.0])
        with pytest.raises(ValueError, match="constant"):
            pearson([1.0, 1.0, 1.0], [1.0, 2.0, 3.0])
        with pytest.raises(ValueError, match="non-empty"):
            ks_statistic([], [1.0])
        with pytest.raises(ValueError, match="finite"):
            ks_statistic([float("nan")], [1.0])


class TestPlatZones:
    def test_threshold_boundaries(self, params):
        z = lambda sp, ks: plat_zone_from_metrics(sp, ks, params)
        assert z(0.85, 0.09) == "green"          # exactly on both green edges
        assert z(0.8499999, 0.09) == "amber"     # spearman just below green
        assert z(0.85, 0.0900001) == "amber"     # ks just above green
        assert z(0.80, 0.12) == "amber"          # exactly on both amber edges
        assert z(0.7999999, 0.05) == "red"       # spearman below amber
        assert z(0.99, 0.1200001) == "red"       # ks above amber
        assert z(0.82, 0.05) == "amber"

    def test_constant_pnl_is_red(self, params):
        res = plat_test([5.0] * 20, series(20, 1.1, 0.3), params)
        assert res.zone == "red"
        assert res.spearman is None and res.ks is None
        # constant on the RTPL side too
        assert plat_test(series(20, 1.1, 0.3), [0.0] * 20, params).zone == "red"

    def test_identical_series_green(self, params):
        x = series(50, 1.3, 0.7)
        res = plat_test(x, list(x), params)
        assert res.zone == "green"
        assert res.spearman == pytest.approx(1.0, abs=1e-12)
        assert res.ks == 0.0

    def test_length_errors(self, params):
        with pytest.raises(ValueError, match="mismatch"):
            plat_test([1.0, 2.0], [1.0], params)
        with pytest.raises(ValueError, match="at least 3"):
            plat_test([1.0, 2.0], [1.0, 2.0], params)


class TestPlatSurcharge:
    def test_amber_interpolation(self, params):
        # k = 0.5 pinned: surcharge = 0.5 * max(0, SA - IMA)
        assert plat_surcharge("amber", 100.0, 60.0, params) == pytest.approx(20.0, abs=1e-12)
        assert plat_surcharge("amber", 50.0, 60.0, params) == 0.0  # SA < IMA

    def test_green_red_no_surcharge(self, params):
        assert plat_surcharge("green", 100.0, 60.0, params) == 0.0
        assert plat_surcharge("red", 100.0, 60.0, params) == 0.0

    def test_bad_zone_raises(self, params):
        with pytest.raises(ValueError, match="zone"):
            plat_surcharge("blue", 1.0, 1.0, params)
