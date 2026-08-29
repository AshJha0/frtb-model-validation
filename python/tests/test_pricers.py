"""Pricer kit: BS edge cases, put-call parity, Greeks vs finite differences,
binomial convergence, bond/swap/FX-forward pricing, input validation."""
import math

import pytest

from frtb.instruments import Bond, FxForward, PayerSwap
from frtb.market import Curve
from frtb.pricers import (binomial_price, bs_delta, bs_price, bs_vega,
                          price_bond, price_fx_forward, price_payer_swap)


def flat_curve(r: float) -> Curve:
    return Curve((0.5, 1.0, 2.0, 5.0, 10.0), (r,) * 5)


class TestBlackScholes:
    def test_put_call_parity_grid(self):
        # property loop: C - P = S e^{-qT} - K e^{-rT} on a strike/maturity grid
        s, r, q, sigma = 100.0, 0.03, 0.015, 0.25
        for k in (60.0, 80.0, 100.0, 120.0, 150.0):
            for t in (0.1, 0.5, 1.0, 3.0):
                c = bs_price(s, k, t, r, q, sigma, True)
                p = bs_price(s, k, t, r, q, sigma, False)
                want = s * math.exp(-q * t) - k * math.exp(-r * t)
                assert c - p == pytest.approx(want, abs=1e-10)

    def test_expiry_intrinsic(self):
        assert bs_price(110.0, 100.0, 0.0, 0.05, 0.0, 0.2, True) == 10.0
        assert bs_price(90.0, 100.0, 0.0, 0.05, 0.0, 0.2, True) == 0.0
        assert bs_price(90.0, 100.0, 0.0, 0.05, 0.0, 0.2, False) == 10.0

    def test_sigma_zero_discounted_forward_intrinsic(self):
        s, k, t, r, q = 100.0, 90.0, 2.0, 0.05, 0.01
        want = s * math.exp(-q * t) - k * math.exp(-r * t)
        assert bs_price(s, k, t, r, q, 0.0, True) == pytest.approx(want, abs=1e-12)
        assert bs_price(s, k, t, r, q, 0.0, False) == 0.0

    def test_deep_itm_otm(self):
        s, r, q, sigma, t = 100.0, 0.03, 0.01, 0.2, 1.0
        # deep ITM call ~ forward intrinsic; deep OTM ~ 0
        want = s * math.exp(-q * t) - 1e-4 * math.exp(-r * t)
        assert bs_price(s, 1e-4, t, r, q, sigma, True) == pytest.approx(want, rel=1e-9)
        assert bs_price(s, 1e6, t, r, q, sigma, True) == pytest.approx(0.0, abs=1e-9)

    def test_negative_rates_supported(self):
        p = bs_price(100.0, 100.0, 1.0, -0.01, -0.005, 0.2, True)
        assert p > 0.0 and math.isfinite(p)

    def test_delta_vega_vs_finite_difference(self):
        s, r, q, sigma = 100.0, 0.03, 0.01, 0.25
        h = 1e-5
        for k in (80.0, 100.0, 125.0):
            for t in (0.25, 1.0, 2.0):
                for call in (True, False):
                    fd_d = (bs_price(s + h, k, t, r, q, sigma, call)
                            - bs_price(s - h, k, t, r, q, sigma, call)) / (2 * h)
                    assert bs_delta(s, k, t, r, q, sigma, call) == pytest.approx(fd_d, abs=1e-7)
                    fd_v = (bs_price(s, k, t, r, q, sigma + h, call)
                            - bs_price(s, k, t, r, q, sigma - h, call)) / (2 * h)
                    assert bs_vega(s, k, t, r, q, sigma) == pytest.approx(fd_v, abs=1e-6)

    def test_invalid_inputs_raise(self):
        with pytest.raises(ValueError, match="positive"):
            bs_price(-1.0, 100.0, 1.0, 0.0, 0.0, 0.2, True)
        with pytest.raises(ValueError, match="positive"):
            bs_price(100.0, 0.0, 1.0, 0.0, 0.0, 0.2, True)
        with pytest.raises(ValueError, match="maturity"):
            bs_price(100.0, 100.0, -1.0, 0.0, 0.0, 0.2, True)
        with pytest.raises(ValueError, match="sigma"):
            bs_price(100.0, 100.0, 1.0, 0.0, 0.0, -0.2, True)
        with pytest.raises(ValueError, match="finite"):
            bs_price(float("nan"), 100.0, 1.0, 0.0, 0.0, 0.2, True)


class TestBinomial:
    def test_converges_to_bs(self):
        s, k, t, r, q, sigma = 100.0, 105.0, 1.0, 0.03, 0.01, 0.2
        for call in (True, False):
            bs = bs_price(s, k, t, r, q, sigma, call)
            assert binomial_price(s, k, t, r, q, sigma, call, 501) == pytest.approx(bs, abs=0.02)

    def test_edge_cases_delegate(self):
        assert binomial_price(110.0, 100.0, 0.0, 0.05, 0.0, 0.2, True, 100) == 10.0
        s, k, t, r, q = 100.0, 90.0, 1.0, 0.05, 0.0
        want = bs_price(s, k, t, r, q, 0.0, True)
        assert binomial_price(s, k, t, r, q, 0.0, True, 100) == want

    def test_bad_steps_raise(self):
        with pytest.raises(ValueError, match="steps"):
            binomial_price(100.0, 100.0, 1.0, 0.0, 0.0, 0.2, True, 0)


class TestCurveInstruments:
    def test_bond_price_flat_curve_hand(self):
        # 2y 5% annual bond, flat 3% cc curve
        bond = Bond(inst_id="b", notional=100.0, coupon=0.05, maturity=2.0,
                    currency="USD", issuer="X", rating="AAA")
        curve = flat_curve(0.03)
        want = 5.0 * math.exp(-0.03) + 105.0 * math.exp(-0.06)
        assert price_bond(bond, curve) == pytest.approx(want, abs=1e-12)

    def test_bond_negative_rates(self):
        bond = Bond(inst_id="b", notional=100.0, coupon=0.0, maturity=5.0,
                    currency="USD", issuer="X", rating="AAA")
        assert price_bond(bond, flat_curve(-0.01)) == pytest.approx(
            100.0 * math.exp(0.05), abs=1e-10)

    def test_payer_swap_at_par_rate_is_zero(self):
        # flat cc curve: par rate c* = (1 - DF(T)) / annuity -> value 0
        curve = flat_curve(0.03)
        annuity = sum(curve.df(t) for t in (1.0, 2.0, 3.0))
        par = (1.0 - curve.df(3.0)) / annuity
        swap = PayerSwap(inst_id="s", notional=1e6, fixed_rate=par, maturity=3.0,
                         currency="USD")
        assert price_payer_swap(swap, curve) == pytest.approx(0.0, abs=1e-6)

    def test_payer_swap_gains_when_rates_rise(self):
        swap = PayerSwap(inst_id="s", notional=1e6, fixed_rate=0.03, maturity=5.0,
                         currency="USD")
        v_lo = price_payer_swap(swap, flat_curve(0.02))
        v_hi = price_payer_swap(swap, flat_curve(0.04))
        assert v_hi > v_lo

    def test_fx_forward_zero_at_market_forward(self):
        dom, for_ = flat_curve(0.03), flat_curve(0.02)
        spot, t = 1.10, 1.0
        k = spot * for_.df(t) / dom.df(t)  # zero-value strike
        fwd = FxForward(inst_id="f", pair="EURUSD", notional=1e6, strike=k, maturity=t)
        assert price_fx_forward(fwd, spot, dom, for_) == pytest.approx(0.0, abs=1e-9)

    def test_fx_forward_bad_spot_raises(self):
        fwd = FxForward(inst_id="f", pair="EURUSD", notional=1e6, strike=1.1, maturity=1.0)
        with pytest.raises(ValueError, match="spot"):
            price_fx_forward(fwd, -1.0, flat_curve(0.03), flat_curve(0.02))

    def test_curve_validation(self):
        with pytest.raises(ValueError, match="increasing"):
            Curve((1.0, 1.0), (0.03, 0.03))
        with pytest.raises(ValueError, match="node"):
            flat_curve(0.03).bumped_node(7.0, 1e-4)
        with pytest.raises(ValueError, match="invalid time"):
            flat_curve(0.03).rate(-1.0)
