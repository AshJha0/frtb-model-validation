"""Bump-and-revalue sensitivities, empty-desk edge cases and engine wiring."""
import math

import pytest

from frtb.engine import compute_sa, desk_categories
from frtb.instruments import Bond, EquityOption, FxForward, PayerSwap
from frtb.market import Curve, EquityQuote, Market
from frtb.pricers import bs_delta, bs_vega
from frtb.sensitivities import compute_sensitivities


@pytest.fixture(scope="module")
def market(results):
    return results["market"]


class TestGirrSensitivities:
    def test_bond_dv01_negative_and_localised(self, results):
        """Long bond: rates up -> price down; sensitivity sits at coupon tenors."""
        params = results["params"]
        market = results["market"]
        bond = Bond(inst_id="b", notional=1e7, coupon=0.03, maturity=5.0,
                    currency="USD", issuer="X", rating="AAA")
        sens = compute_sensitivities([bond], market, params)
        girr = sens.girr["USD"]
        assert girr[5.0] < 0.0                      # principal + last coupon
        assert girr[30.0] == pytest.approx(0.0, abs=1e-4)  # beyond maturity
        assert "EUR" not in sens.girr               # all-zero currency dropped

    def test_payer_swap_positive_rate_sensitivity(self, results):
        params = results["params"]
        market = results["market"]
        swap = PayerSwap(inst_id="s", notional=2e7, fixed_rate=0.028,
                         maturity=10.0, currency="USD")
        sens = compute_sensitivities([swap], market, params)
        assert sum(sens.girr["USD"].values()) > 0.0  # payer gains when rates rise


class TestEquityFxSensitivities:
    def test_equity_delta_close_to_analytic(self, results):
        params = results["params"]
        market = results["market"]
        opt = EquityOption(inst_id="o", underlier="AAA_TECH", option_type="call",
                           position=1, contracts=1000.0, strike=105.0,
                           maturity=1.0, currency="USD")
        q = market.equity("AAA_TECH")
        r = market.curve("USD").rate(1.0)
        sens = compute_sensitivities([opt], market, params)
        analytic = q.spot * bs_delta(q.spot, 105.0, 1.0, r, q.div_yield, q.vol, True) * 1000.0
        # 1% relative bump: forward-difference convexity error is O(bump)
        assert sens.equity_delta["AAA_TECH"] == pytest.approx(analytic, rel=0.05)
        analytic_vega = bs_vega(q.spot, 105.0, 1.0, r, q.div_yield, q.vol) * q.vol * 1000.0
        assert sens.equity_vega["AAA_TECH"] == pytest.approx(analytic_vega, rel=0.05)

    def test_fx_forward_delta_exact(self, results):
        """Linear payoff: relative-bump delta is exact: s = N * S * DF_for(T)."""
        params = results["params"]
        market = results["market"]
        fwd = FxForward(inst_id="f", pair="EURUSD", notional=1.5e7, strike=1.10,
                        maturity=1.0)
        sens = compute_sensitivities([fwd], market, params)
        want = 1.5e7 * market.fx_spot("EURUSD") * market.curve("EUR").df(1.0)
        assert sens.fx_delta["EURUSD"] == pytest.approx(want, rel=1e-10)
        # linear payoff -> zero curvature (CVR identically 0)
        up, dn = sens.fx_cvr["EURUSD"]
        assert up == pytest.approx(0.0, abs=1e-4)
        assert dn == pytest.approx(0.0, abs=1e-4)

    def test_short_option_negative_gamma_positive_cvr(self, results):
        """Short call (negative gamma): curvature CVR must be a positive loss."""
        params = results["params"]
        market = results["market"]
        opt = EquityOption(inst_id="o", underlier="GLOBAL_INDEX", option_type="call",
                           position=-1, contracts=30000.0, strike=260.0,
                           maturity=0.75, currency="USD")
        sens = compute_sensitivities([opt], market, params)
        up, dn = sens.equity_cvr["GLOBAL_INDEX"]
        assert up > 0.0 and dn > 0.0

    def test_long_option_negative_cvr(self, results):
        params = results["params"]
        market = results["market"]
        opt = EquityOption(inst_id="o", underlier="AAA_TECH", option_type="call",
                           position=1, contracts=1000.0, strike=105.0,
                           maturity=1.0, currency="USD")
        sens = compute_sensitivities([opt], market, params)
        up, dn = sens.equity_cvr["AAA_TECH"]
        assert up < 0.0 and dn < 0.0  # convexity benefit both ways


class TestEmptyDeskAndErrors:
    def test_empty_desk_all_zero(self, results):
        params = results["params"]
        market = results["market"]
        sa = compute_sa([], market, params)
        assert sa.sbm.capital == 0.0
        assert sa.drc == 0.0
        assert sa.rrao == 0.0
        assert sa.capital == 0.0
        for scen, total in sa.sbm.scenario_totals.items():
            assert total == 0.0

    def test_missing_equity_bucket_param_raises(self, results):
        """Sensitivity mapped to a bucket absent from the pinned set -> error."""
        params = results["params"]
        market = results["market"]
        bad = Market(market.curves,
                     {"ROGUE": EquityQuote(spot=50.0, vol=0.2, div_yield=0.0,
                                           bucket="99")},
                     market.fx)
        opt = EquityOption(inst_id="o", underlier="ROGUE", option_type="call",
                           position=1, contracts=100.0, strike=50.0,
                           maturity=1.0, currency="USD")
        # the bump-and-revalue pass already needs the curvature RW -> raises there
        with pytest.raises(ValueError, match="unknown equity bucket '99'"):
            compute_sensitivities([opt], bad, params)

    def test_desk_categories_extraction(self, results):
        hypo = {"desk1": [1.0], "desk1_ir": [1.0], "desk2": [2.0],
                "desk2_eq": [1.5], "desk2_fx": [0.5]}
        assert set(desk_categories("desk1", hypo)) == {"ir"}
        assert set(desk_categories("desk2", hypo)) == {"eq", "fx"}


class TestPortfolioLevelStructure:
    def test_firm_capital_positive_and_scenarios_reported(self, results):
        sa = results["sa"]["firm"]
        assert sa.sbm.capital > 0.0
        assert set(sa.sbm.scenario_totals) == {"high", "medium", "low"}
        assert sa.sbm.capital == max(sa.sbm.scenario_totals.values())

    def test_girr_vega_zero_no_ir_options(self, results):
        """No IR-vol instruments in scope -> GIRR vega charge exactly 0."""
        for scen in ("high", "medium", "low"):
            assert results["sa"]["firm"].sbm.charges["girr"]["vega"][scen] == 0.0

    def test_desk_hbr_all_long(self, results):
        # bundled portfolio holds only long bonds -> HBR = 1 on every scope
        for scope in ("desk1", "desk2", "firm"):
            assert results["sa"][scope].drc_hbr == 1.0
