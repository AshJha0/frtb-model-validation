"""SBM aggregation: hand-computable example, scenarios, S_b fallback,
curvature psi rule, guards and error paths."""
import math

import pytest

from frtb.sbm import (SCENARIOS, aggregate_buckets, bucket_kb,
                      curvature_bucket_kb, curvature_charge, delta_vega_charge,
                      psi, scale_rho)


class TestHandExample:
    """2-bucket example checkable by hand (spec: tolerance 1e-12)."""

    def test_bucket_kb(self):
        k_a = bucket_kb([10.0, -5.0], lambda i, j: 0.5)
        assert abs(k_a - math.sqrt(75.0)) < 1e-12  # 100 + 25 + 2*0.5*10*(-5)

    def test_full_aggregation(self):
        k_a = bucket_kb([10.0, -5.0], lambda i, j: 0.5)
        k_b = bucket_kb([8.0], lambda i, j: 0.0)
        agg = aggregate_buckets({"A": k_a, "B": k_b}, {"A": 5.0, "B": 8.0},
                                lambda b, c: 0.25)
        assert abs(agg.charge - math.sqrt(159.0)) < 1e-12
        assert not agg.used_fallback

    def test_via_delta_vega_charge(self):
        res = delta_vega_charge(
            {"A": {"k1": 10.0, "k2": -5.0}, "B": {"k1": 8.0}},
            lambda b, k, l: 0.5 if b == "A" else 0.0,
            lambda b, c: 0.25, "medium")
        assert abs(res.charge - math.sqrt(159.0)) < 1e-12
        assert abs(res.kb["A"] - math.sqrt(75.0)) < 1e-12
        assert abs(res.kb["B"] - 8.0) < 1e-12


class TestScenarios:
    def test_high_scales_and_caps(self):
        assert scale_rho(0.5, "high") == pytest.approx(0.625, abs=1e-15)
        assert scale_rho(0.9, "high") == 1.0  # 1.125 capped at 1
        assert scale_rho(0.97, "high") == 1.0

    def test_low_scales(self):
        assert scale_rho(0.5, "low") == pytest.approx(0.375, abs=1e-15)
        assert scale_rho(0.8, "medium") == 0.8

    def test_unknown_scenario_raises(self):
        with pytest.raises(ValueError, match="scenario"):
            scale_rho(0.5, "extreme")

    def test_high_cap_collapses_to_perfect_correlation(self):
        # rho = 0.9 -> high scenario rho = 1.0 -> K_b = |ws1 + ws2| exactly
        res = delta_vega_charge({"A": {"k1": 3.0, "k2": 4.0}},
                                lambda b, k, l: 0.9, lambda b, c: 0.0, "high")
        assert res.charge == pytest.approx(7.0, abs=1e-12)

    def test_scenario_monotonicity_same_sign_ws(self):
        # same-sign WS: higher correlation -> higher charge
        ws = {"A": {"k1": 3.0, "k2": 4.0}}
        charges = {s: delta_vega_charge(ws, lambda b, k, l: 0.5,
                                        lambda b, c: 0.0, s).charge
                   for s in SCENARIOS}
        assert charges["low"] < charges["medium"] < charges["high"]


class TestGuardsAndFallback:
    def test_negative_rounding_guard(self):
        # quadratic form pushed (unphysically) below zero -> max(0, .) guard
        assert bucket_kb([1.0, 1.0], lambda i, j: -1.0000001) == 0.0

    def test_sb_fallback_triggered(self):
        # K = 5 both buckets, S = +7 / -7, gamma 0.9: 50 - 88.2 < 0 -> fallback
        agg = aggregate_buckets({"A": 5.0, "B": 5.0}, {"A": 7.0, "B": -7.0},
                                lambda b, c: 0.9)
        assert agg.used_fallback
        assert agg.sb == {"A": 5.0, "B": -5.0}  # clamped to [-K_b, K_b]
        assert agg.charge == pytest.approx(math.sqrt(5.0), abs=1e-12)

    def test_fallback_not_triggered_when_positive(self):
        agg = aggregate_buckets({"A": 5.0, "B": 5.0}, {"A": 7.0, "B": 7.0},
                                lambda b, c: 0.9)
        assert not agg.used_fallback

    def test_bucket_mismatch_raises(self):
        with pytest.raises(ValueError, match="same buckets"):
            aggregate_buckets({"A": 1.0}, {"B": 1.0}, lambda b, c: 0.0)

    def test_zero_sensitivities_zero_capital(self):
        assert delta_vega_charge({}, lambda b, k, l: 0.5,
                                 lambda b, c: 0.25, "medium").charge == 0.0
        assert delta_vega_charge({"A": {"k1": 0.0}}, lambda b, k, l: 0.5,
                                 lambda b, c: 0.25, "medium").charge == 0.0

    def test_non_finite_ws_raises(self):
        with pytest.raises(ValueError, match="finite"):
            bucket_kb([1.0, float("nan")], lambda i, j: 0.0)


class TestCurvature:
    def test_psi_rule(self):
        assert psi(-1.0, -2.0) == 0.0
        assert psi(-1.0, 2.0) == 1.0
        assert psi(1.0, 2.0) == 1.0
        assert psi(0.0, -1.0) == 1.0  # zero is not negative

    def test_bucket_hand_case(self):
        # CVR+ = [3, -1], curvature rho = 0.25 (= delta rho 0.5 squared):
        # K+^2 = 3^2 + max(-1,0)^2 + 2*0.25*3*(-1)*psi(3,-1) = 9 - 1.5 = 7.5
        # CVR- = [-2, -2]: all negative -> K- = 0.  K_b = sqrt(7.5), S_b = 2.
        kb, sb = curvature_bucket_kb([3.0, -1.0], [-2.0, -2.0],
                                     lambda i, j: 0.25)
        assert kb == pytest.approx(math.sqrt(7.5), abs=1e-12)
        assert sb == pytest.approx(2.0, abs=1e-12)

    def test_negative_gamma_exposure_all_negative_cvr(self):
        # long-option book: curvature benefit on both sides -> zero charge
        kb, sb = curvature_bucket_kb([-4.0], [-2.0], lambda i, j: 0.0)
        assert kb == 0.0
        assert sb == -4.0  # up side selected on tie (0 == 0)

    def test_cross_bucket_psi_zeroes_negative_pairs(self):
        # both S_b negative -> cross term dropped -> total = sqrt(sum K_b^2)
        res = curvature_charge(
            {"A": ([-1.0], [2.0]), "B": ([-3.0], [1.5])},
            lambda b, k, l: 0.5, lambda b, c: 0.8, "medium",
            {"A": ["x"], "B": ["y"]})
        # K_A = 2 (down side, S_A = 2), K_B = 1.5 (down side, S_B = 1.5)
        # both S positive here -> cross term active with gamma^2 = 0.64
        expected = math.sqrt(4.0 + 2.25 + 2 * 0.64 * 2.0 * 1.5)
        assert res.charge == pytest.approx(expected, abs=1e-12)

        res2 = curvature_charge(
            {"A": ([-1.0], [-2.0]), "B": ([-3.0], [-1.5])},
            lambda b, k, l: 0.5, lambda b, c: 0.8, "medium",
            {"A": ["x"], "B": ["y"]})
        assert res2.charge == 0.0  # all CVRs negative everywhere

    def test_curvature_empty(self):
        res = curvature_charge({}, lambda b, k, l: 0.0, lambda b, c: 0.0,
                               "medium", {})
        assert res.charge == 0.0

    def test_mismatched_lengths_raise(self):
        with pytest.raises(ValueError, match="up/down"):
            curvature_bucket_kb([1.0], [1.0, 2.0], lambda i, j: 0.0)
