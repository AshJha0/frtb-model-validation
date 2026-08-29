"""IMA sketch: ES definition, LH ladder, IMCC, backtest zones/multipliers, SES."""
import math

import pytest

from frtb.ima import (backtest, backtest_multiplier, backtest_zone, es_base_10d,
                      es_lh_scaled, expected_shortfall_daily, ima_capital, imcc,
                      ses)


class TestExpectedShortfall:
    def test_pinned_tail_definition(self):
        # n = 40, alpha = 0.975 -> k = ceil(1) = 1 -> ES = worst loss
        pnl = [float(i) for i in range(-20, 20)]
        assert expected_shortfall_daily(pnl, 0.975) == 20.0
        assert es_base_10d(pnl, 0.975) == pytest.approx(20.0 * math.sqrt(10.0), abs=1e-12)

    def test_k_of_two(self):
        # n = 80 -> k = ceil(2) = 2 -> mean of two worst losses
        pnl = [0.0] * 78 + [-30.0, -10.0]
        assert expected_shortfall_daily(pnl, 0.975) == pytest.approx(20.0, abs=1e-12)

    def test_errors(self):
        with pytest.raises(ValueError, match="empty"):
            expected_shortfall_daily([], 0.975)
        with pytest.raises(ValueError, match="alpha"):
            expected_shortfall_daily([1.0], 1.5)
        with pytest.raises(ValueError, match="finite"):
            expected_shortfall_daily([1.0, float("inf")], 0.975)


class TestLhLadder:
    LADDER = (10, 20, 40, 60, 120)

    def test_single_category_sqrt_scaling(self):
        # one category with LH = 40: ladder collapses to sqrt(40/10) = 2x base
        pnl = [math.sin(3.0 * i) * 100.0 for i in range(100)]
        es_b = es_base_10d(pnl)
        es_l = es_lh_scaled(pnl, {"fx": pnl}, {"fx": 40}, self.LADDER)
        assert es_l == pytest.approx(2.0 * es_b, abs=1e-9)

    def test_lh10_category_equals_base(self):
        pnl = [math.cos(2.0 * i) * 50.0 for i in range(80)]
        es_l = es_lh_scaled(pnl, {"x": pnl}, {"x": 10}, self.LADDER)
        assert es_l == pytest.approx(es_base_10d(pnl), abs=1e-9)

    def test_ladder_monotone(self):
        # property-style loop: LH scaling never decreases the ES, and it is
        # non-decreasing in the category liquidity horizon
        pnl = [math.sin(1.7 * i) * 80.0 + math.cos(0.3 * i) * 40.0 for i in range(120)]
        base = es_base_10d(pnl)
        prev = 0.0
        for lh in (10, 20, 40, 60, 120):
            es_l = es_lh_scaled(pnl, {"c": pnl}, {"c": lh}, self.LADDER)
            assert es_l >= base - 1e-12
            assert es_l >= prev - 1e-12
            prev = es_l

    def test_two_categories_hand(self):
        # cat a (LH 10) + cat b (LH 20):
        # ES_LH^2 = ES(full)^2 + ES(b)^2 * (20-10)/10
        a = [math.sin(2.1 * i) * 30.0 for i in range(100)]
        b = [math.cos(1.3 * i) * 60.0 for i in range(100)]
        full = [x + y for x, y in zip(a, b)]
        got = es_lh_scaled(full, {"a": a, "b": b}, {"a": 10, "b": 20}, self.LADDER)
        want = math.sqrt(es_base_10d(full) ** 2 + es_base_10d(b) ** 2)
        assert got == pytest.approx(want, abs=1e-9)

    def test_category_sum_mismatch_raises(self):
        with pytest.raises(ValueError, match="sum"):
            es_lh_scaled([1.0, -2.0, 3.0], {"a": [1.0, 1.0, 1.0]}, {"a": 20},
                         self.LADDER)

    def test_missing_category_lh_raises(self):
        with pytest.raises(ValueError, match="liquidity horizon"):
            es_lh_scaled([1.0, -1.0], {"a": [1.0, -1.0]}, {}, self.LADDER)

    def test_bad_ladder_raises(self):
        with pytest.raises(ValueError, match="10d base"):
            es_lh_scaled([1.0, -1.0], {"a": [1.0, -1.0]}, {"a": 20}, (20, 40))


class TestImcc:
    def test_imcc_rho_blend(self, params):
        # single category: full ES == partial ES -> IMCC == ES_LH exactly
        pnl = [math.sin(0.9 * i) * 100.0 for i in range(90)]
        want = es_lh_scaled(pnl, {"ir": pnl}, params.category_lh, params.lh_ladder,
                            params.ima_alpha)
        assert imcc(pnl, {"ir": pnl}, params) == pytest.approx(want, abs=1e-9)

    def test_imcc_between_full_and_sum(self, params):
        a = [math.sin(2.1 * i) * 30.0 for i in range(100)]
        b = [math.cos(1.3 * i) * 60.0 for i in range(100)]
        full = [x + y for x, y in zip(a, b)]
        cats = {"eq": a, "fx": b}
        v = imcc(full, cats, params)
        es_full = es_lh_scaled(full, cats, params.category_lh, params.lh_ladder)
        es_sum = sum(es_lh_scaled(s, {c: s}, params.category_lh, params.lh_ladder)
                     for c, s in cats.items())
        assert es_full <= v + 1e-9 <= es_sum + 1e-9
        assert v == pytest.approx(0.5 * es_full + 0.5 * es_sum, abs=1e-9)


class TestBacktest:
    def test_zone_edges(self):
        assert backtest_zone(0) == "green"
        assert backtest_zone(4) == "green"
        assert backtest_zone(5) == "amber"
        assert backtest_zone(9) == "amber"
        assert backtest_zone(10) == "red"
        assert backtest_zone(13) == "red"

    def test_multiplier_table_edges(self, params):
        assert backtest_multiplier(4, params) == 1.5
        assert backtest_multiplier(5, params) == 1.70
        assert backtest_multiplier(9, params) == 1.92
        assert backtest_multiplier(10, params) == 2.0
        assert backtest_multiplier(13, params) == 2.0  # > 12: red-zone cap

    def test_exception_counting(self, params):
        pnl = [-5.0, -15.0, 3.0, -10.0, -10.1]
        var = [10.0, 10.0, 10.0, 10.0, 10.0]
        res = backtest(pnl, var, params)
        assert res.exceptions == 2  # strictly below -VaR only (-15, -10.1)
        assert res.zone == "green"
        assert res.multiplier == 1.5

    def test_errors(self, params):
        with pytest.raises(ValueError, match="mismatch"):
            backtest([1.0], [1.0, 2.0], params)
        with pytest.raises(ValueError, match="non-negative"):
            backtest([1.0], [-1.0], params)
        with pytest.raises(ValueError, match="negative"):
            backtest_zone(-1)


class TestSesAndCapital:
    def test_ses_zero_diversification_sum(self):
        entries = [{"factor": "a", "desk": "d", "stressed_loss": 100.0},
                   {"factor": "b", "desk": "d", "stressed_loss": 250.0}]
        assert ses(entries) == 350.0
        assert ses([]) == 0.0

    def test_ses_negative_raises(self):
        with pytest.raises(ValueError, match="stressed_loss"):
            ses([{"factor": "a", "desk": "d", "stressed_loss": -1.0}])

    def test_ima_capital_formula(self):
        assert ima_capital(100.0, 1.7, 30.0, 5.0) == pytest.approx(205.0, abs=1e-12)
        with pytest.raises(ValueError, match="multiplier"):
            ima_capital(100.0, -1.0, 0.0)
