"""DRC-lite: JTD, issuer netting, HBR; RRAO rates and error paths."""
import pytest

from frtb.instruments import Bond, RraoFlag
from frtb.sa import DrcPosition, drc_charge, rrao_charge


def make(issuer, rating, notional, mv, lgd=0.75):
    return DrcPosition(issuer=issuer, rating=rating, notional=notional,
                       market_value=mv, lgd=lgd)


class TestJtd:
    def test_jtd_formula_long(self):
        # JTD = LGD*N + (MV - N) = 0.75*10M + (9.8M - 10M) = 7.3M
        assert make("X", "BBB", 10e6, 9.8e6).jtd() == pytest.approx(7.3e6, abs=1e-6)

    def test_jtd_formula_short(self):
        # short: N = -4M, MV = -3.9M -> -3M + 0.1M = -2.9M
        assert make("X", "BBB", -4e6, -3.9e6).jtd() == pytest.approx(-2.9e6, abs=1e-6)


class TestDrcHandCase:
    def test_netting_and_hbr(self, params):
        # X: 7.3M long + (-2.9M) short -> net +4.4M (BBB, RW 6%)
        # Y: net -0.77M short (B, RW 30%)
        # HBR = 4.4 / (4.4 + 0.77); DRC = 0.06*4.4M - HBR*0.30*0.77M
        pos = [make("X", "BBB", 10e6, 9.8e6), make("X", "BBB", -4e6, -3.9e6),
               make("Y", "B", -1e6, -1.02e6)]
        res = drc_charge(pos, params)
        hbr = 4.4e6 / (4.4e6 + 0.77e6)
        assert res.net_jtd["X"] == pytest.approx(4.4e6, abs=1e-6)
        assert res.net_jtd["Y"] == pytest.approx(-0.77e6, abs=1e-6)
        assert res.hbr == pytest.approx(hbr, abs=1e-12)
        assert res.charge == pytest.approx(0.06 * 4.4e6 - hbr * 0.30 * 0.77e6, abs=1e-9)

    def test_same_issuer_full_netting(self, params):
        pos = [make("X", "BBB", 10e6, 9.8e6), make("X", "BBB", -10e6, -9.8e6)]
        res = drc_charge(pos, params)
        assert res.net_jtd["X"] == pytest.approx(0.0, abs=1e-9)
        assert res.charge == pytest.approx(0.0, abs=1e-9)

    def test_all_long_hbr_is_one(self, params):
        pos = [make("X", "BBB", 10e6, 10e6), make("Y", "BB", 5e6, 5e6)]
        res = drc_charge(pos, params)
        assert res.hbr == 1.0
        assert res.charge == pytest.approx(0.06 * 7.5e6 + 0.15 * 3.75e6, abs=1e-9)

    def test_short_dominated_floor_at_zero(self, params):
        pos = [make("X", "AAA", 1e6, 1e6), make("Y", "CCC", -10e6, -10e6)]
        assert drc_charge(pos, params).charge == 0.0

    def test_empty_book(self, params):
        res = drc_charge([], params)
        assert res.charge == 0.0
        assert res.hbr == 1.0

    def test_unknown_rating_raises(self, params):
        with pytest.raises(ValueError, match="DRC risk weight"):
            drc_charge([make("X", "ZZZ", 1e6, 1e6)], params)

    def test_inconsistent_issuer_rating_raises(self, params):
        with pytest.raises(ValueError, match="inconsistent"):
            drc_charge([make("X", "BBB", 1e6, 1e6), make("X", "BB", 1e6, 1e6)], params)


class TestRrao:
    def test_rates(self, params):
        bond = Bond(inst_id="b", notional=1e6, coupon=0.05, maturity=2.0,
                    currency="USD", issuer="I", rating="BBB",
                    rrao=RraoFlag("other", 1e6))
        exotic = Bond(inst_id="e", notional=2e6, coupon=0.05, maturity=2.0,
                      currency="USD", issuer="I", rating="BBB",
                      rrao=RraoFlag("exotic", 2e6))
        plain = Bond(inst_id="p", notional=3e6, coupon=0.05, maturity=2.0,
                     currency="USD", issuer="I", rating="BBB")
        # 0.1% * 1M + 1.0% * 2M = 1_000 + 20_000
        assert rrao_charge([bond, exotic, plain], params) == pytest.approx(21000.0, abs=1e-12)

    def test_empty(self, params):
        assert rrao_charge([], params) == 0.0

    def test_bad_category_raises(self):
        with pytest.raises(ValueError, match="exotic"):
            RraoFlag("weird", 1e6)
