/// Sensitivity-engine and orchestration tests: bump signs, zero-sensitivity
/// dropping, empty-desk behaviour, determinism (bit-identical recomputation),
/// and engine-level data loading.

#include <gtest/gtest.h>

#include <cmath>

#include "frtb/sa.hpp"
#include "frtb/sensitivities.hpp"
#include "test_helpers.hpp"

namespace {

using namespace frtb;

TEST(Sensitivities, BondGirrIsNegativeAtCashflowTenors) {
    // A long bond loses value when rates rise: dV/dr <= 0 at every node.
    const Results& res = frtb_test::results();
    std::vector<Instrument> insts = {Bond("B", 1e6, 0.03, 5.0, "USD", "I", "AAA")};
    Sensitivities sens = compute_sensitivities(insts, res.market, res.params);
    ASSERT_TRUE(sens.girr.count("USD"));
    EXPECT_FALSE(sens.girr.count("EUR"));  // all-zero currency dropped
    for (const auto& [t, s] : sens.girr.at("USD")) {
        EXPECT_LE(s, 0.0) << "tenor " << t;
    }
    EXPECT_LT(sens.girr.at("USD").at(5.0), 0.0);  // principal node clearly negative
}

TEST(Sensitivities, OptionSignsFollowPosition) {
    const Results& res = frtb_test::results();
    std::vector<Instrument> long_call = {
        EquityOption("O", "AAA_TECH", "call", 1, 1000.0, 105.0, 1.0, "USD")};
    std::vector<Instrument> short_call = {
        EquityOption("O", "AAA_TECH", "call", -1, 1000.0, 105.0, 1.0, "USD")};
    Sensitivities sl = compute_sensitivities(long_call, res.market, res.params);
    Sensitivities ss = compute_sensitivities(short_call, res.market, res.params);
    EXPECT_GT(sl.equity_delta.at("AAA_TECH"), 0.0);
    EXPECT_GT(sl.equity_vega.at("AAA_TECH"), 0.0);
    EXPECT_LT(ss.equity_delta.at("AAA_TECH"), 0.0);
    EXPECT_LT(ss.equity_vega.at("AAA_TECH"), 0.0);
}

TEST(Sensitivities, FxForwardDeltaMatchesClosedForm) {
    // V = N(S*DFf - K*DFd): a 1% relative bump moves V by N*0.01*S*DFf, so the
    // reported s = (V+ - V)/0.01 = N*S*DFf.
    const Results& res = frtb_test::results();
    std::vector<Instrument> insts = {FxForward("F", "EURUSD", 1e6, 1.1, 1.0)};
    Sensitivities sens = compute_sensitivities(insts, res.market, res.params);
    const double expect = 1e6 * 1.085 * res.market.curve("EUR").df(1.0);
    EXPECT_NEAR(sens.fx_delta.at("EURUSD"), expect, 1e-4);
}

TEST(Sensitivities, EmptyScopeGivesZeroCapital) {
    const Results& res = frtb_test::results();
    Sensitivities sens = compute_sensitivities({}, res.market, res.params);
    EXPECT_TRUE(sens.girr.empty());
    EXPECT_TRUE(sens.equity_delta.empty());
    EXPECT_TRUE(sens.fx_delta.empty());
    EXPECT_TRUE(sens.girr_cvr.empty());
    SaScope sa = compute_sa({}, res.market, res.params);
    EXPECT_DOUBLE_EQ(sa.sbm.capital, 0.0);
    EXPECT_DOUBLE_EQ(sa.capital(), 0.0);
    EXPECT_DOUBLE_EQ(sa.drc_hbr, 1.0);
}

TEST(Sensitivities, UnknownEquityBucketThrows) {
    const Results& res = frtb_test::results();
    Market m = res.market;
    m.equities["WEIRD"] = EquityQuote(50.0, 0.2, 0.0, "99");  // bucket not pinned
    std::vector<Instrument> insts = {
        EquityOption("O", "WEIRD", "call", 1, 100.0, 50.0, 1.0, "USD")};
    EXPECT_THROW(compute_sa(insts, m, res.params), std::invalid_argument);
}

TEST(Engine, DeterministicRecomputation) {
    // No runtime RNG anywhere: recomputing the firm SBM capital from scratch
    // reproduces the exact same double, bit for bit.
    const Results& res = frtb_test::results();
    std::vector<Instrument> all;
    for (const auto& [name, desk] : res.desks)
        for (const Instrument& i : desk.instruments) all.push_back(i);
    Sensitivities sens = compute_sensitivities(all, res.market, res.params);
    SbmResult again = sbm_capital(sens, res.market, res.params);
    EXPECT_EQ(again.capital, res.sa.at("firm").sbm.capital);
    for (const std::string& scen : SCENARIOS)
        EXPECT_EQ(again.scenario_totals.at(scen),
                  res.sa.at("firm").sbm.scenario_totals.at(scen));
}

TEST(Engine, PnlCsvLoading) {
    PnlTable hypo = load_pnl_csv(frtb_test::data_dir() + "/pnl_hypo.csv");
    ASSERT_TRUE(hypo.data.count("desk1"));
    ASSERT_TRUE(hypo.data.count("desk2"));
    EXPECT_EQ(hypo.series("desk1").size(), 260u);
    // Category columns preserve file order and sum to the desk column.
    CategoryPnl cats = desk_categories("desk2", hypo);
    ASSERT_EQ(cats.size(), 3u);
    EXPECT_EQ(cats[0].first, "eq");
    EXPECT_EQ(cats[1].first, "fx");
    EXPECT_EQ(cats[2].first, "cr");
    for (std::size_t i = 0; i < 260; ++i) {
        double s = cats[0].second[i] + cats[1].second[i] + cats[2].second[i];
        EXPECT_NEAR(s, hypo.series("desk2")[i], 1e-6);
    }
    EXPECT_THROW(load_pnl_csv(frtb_test::data_dir() + "/nope.csv"), std::invalid_argument);
}

TEST(Engine, ScenarioTotalsAndCapitalConsistency) {
    // capital = max over the three scenario totals; SA capital adds DRC+RRAO.
    const Results& res = frtb_test::results();
    for (const auto& [scope, sa] : res.sa) {
        double mx = 0.0;
        bool first = true;
        for (const auto& [scen, total] : sa.sbm.scenario_totals) {
            (void)scen;
            if (first || total > mx) mx = total;
            first = false;
        }
        EXPECT_EQ(sa.sbm.capital, mx) << scope;
        EXPECT_DOUBLE_EQ(sa.capital(), sa.sbm.capital + sa.drc + sa.rrao) << scope;
    }
}

TEST(Engine, HighScenarioRhoCapRespected) {
    // GIRR short-tenor rho 0.970446 * 1.25 caps at 1.0 under "high".
    EXPECT_DOUBLE_EQ(scale_rho(0.970446, "high"), 1.0);
    // ... while low scales it by 0.75 exactly.
    EXPECT_DOUBLE_EQ(scale_rho(0.970446, "low"), 0.75 * 0.970446);
}

}  // namespace
