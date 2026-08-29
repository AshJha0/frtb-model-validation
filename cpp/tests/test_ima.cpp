/// IMA tests: ES hand cases, sqrt(10) scaling, LH-ladder monotonicity and
/// collapse property, category-sum validation, backtest zone/multiplier table
/// edges, SES and IMA capital error paths.

#include <gtest/gtest.h>

#include <cmath>

#include "frtb/ima.hpp"
#include "test_helpers.hpp"

namespace {

using namespace frtb;

TEST(ExpectedShortfall, HandCase) {
    // n=4, alpha=0.975 -> k = ceil(0.1) = 1: the single worst loss.
    std::vector<double> pnl = {-10.0, 5.0, -30.0, 2.0};
    EXPECT_DOUBLE_EQ(expected_shortfall_daily(pnl), 30.0);
    // n=40, alpha=0.975 -> k = 1 (0.025*40 = 1.0 with the epsilon guard).
    std::vector<double> p40(40, 1.0);
    p40[7] = -99.0;
    EXPECT_DOUBLE_EQ(expected_shortfall_daily(p40), 99.0);
    // n=260 -> k = 7 (asserted indirectly by the golden ES cases).
    EXPECT_DOUBLE_EQ(es_base_10d(pnl), std::sqrt(10.0) * 30.0);
}

TEST(ExpectedShortfall, Errors) {
    EXPECT_THROW(expected_shortfall_daily({}), std::invalid_argument);
    EXPECT_THROW(expected_shortfall_daily({1.0}, 1.5), std::invalid_argument);
    EXPECT_THROW(expected_shortfall_daily({1.0, NAN}), std::invalid_argument);
}

TEST(LhLadder, SingleCategoryCollapse) {
    // One category with horizon LH collapses to ES1 * sqrt(LH/10).
    const SbmParams& params = frtb_test::params();
    std::vector<double> pnl;
    for (int i = 0; i < 40; ++i) pnl.push_back((i % 7) - 3.0 - (i == 5 ? 50.0 : 0.0));
    const double es1 = es_base_10d(pnl, params.ima_alpha);
    for (const auto& [cat, lh] : params.category_lh) {
        CategoryPnl cats = {{cat, pnl}};
        double es_lh =
            es_lh_scaled(pnl, cats, params.category_lh, params.lh_ladder, params.ima_alpha);
        EXPECT_NEAR(es_lh, es1 * std::sqrt(lh / 10.0), 1e-10) << "category " << cat;
        EXPECT_GE(es_lh, es1 - 1e-12);  // ladder is monotone
    }
}

TEST(LhLadder, MonotoneOnBundledDesks) {
    const frtb::Results& res = frtb_test::results();
    for (const auto& [d, r] : res.ima) {
        EXPECT_GE(r.es_lh, r.es_base) << d;
        EXPECT_GE(r.imcc, 0.0) << d;
    }
}

TEST(LhLadder, ValidationErrors) {
    const SbmParams& params = frtb_test::params();
    std::vector<double> pnl = {1.0, -2.0, 3.0, -4.0};
    // Category series must sum to the full P&L.
    CategoryPnl bad_sum = {{"ir", {1.0, -2.0, 3.0, -3.0}}};
    EXPECT_THROW(es_lh_scaled(pnl, bad_sum, params.category_lh, params.lh_ladder),
                 std::invalid_argument);
    // Unknown category horizon.
    CategoryPnl unknown = {{"weather", pnl}};
    EXPECT_THROW(es_lh_scaled(pnl, unknown, params.category_lh, params.lh_ladder),
                 std::invalid_argument);
    // Length mismatch.
    CategoryPnl short_series = {{"ir", {1.0, -2.0}}};
    EXPECT_THROW(es_lh_scaled(pnl, short_series, params.category_lh, params.lh_ladder),
                 std::invalid_argument);
    // Ladder must start at 10 and be strictly increasing.
    CategoryPnl ok = {{"ir", pnl}};
    EXPECT_THROW(es_lh_scaled(pnl, ok, params.category_lh, {20, 40}), std::invalid_argument);
    EXPECT_THROW(es_lh_scaled(pnl, ok, params.category_lh, {10, 40, 20}), std::invalid_argument);
}

TEST(Backtest, ZoneAndMultiplierTableEdges) {
    const SbmParams& params = frtb_test::params();
    // Table edges: 4 -> green 1.5; 5 -> amber 1.70; 9 -> amber 1.92; 10 -> red 2.0.
    EXPECT_EQ(backtest_zone(0), "green");
    EXPECT_EQ(backtest_zone(4), "green");
    EXPECT_EQ(backtest_zone(5), "amber");
    EXPECT_EQ(backtest_zone(9), "amber");
    EXPECT_EQ(backtest_zone(10), "red");
    EXPECT_EQ(backtest_zone(13), "red");  // > 12 stays red (cap)
    EXPECT_DOUBLE_EQ(backtest_multiplier(4, params), 1.5);
    EXPECT_DOUBLE_EQ(backtest_multiplier(5, params), 1.70);
    EXPECT_DOUBLE_EQ(backtest_multiplier(6, params), 1.75);
    EXPECT_DOUBLE_EQ(backtest_multiplier(9, params), 1.92);
    EXPECT_DOUBLE_EQ(backtest_multiplier(10, params), 2.0);
    EXPECT_DOUBLE_EQ(backtest_multiplier(50, params), 2.0);
    EXPECT_THROW(backtest_zone(-1), std::invalid_argument);
}

TEST(Backtest, StrictExceptionCounting) {
    const SbmParams& params = frtb_test::params();
    // PnL == -VaR is NOT an exception (strict inequality).
    BacktestResult r = backtest({-10.0, -10.1, 5.0}, {10.0, 10.0, 10.0}, params);
    EXPECT_EQ(r.exceptions, 1);
    EXPECT_EQ(r.zone, "green");
    EXPECT_DOUBLE_EQ(r.multiplier, 1.5);
    EXPECT_THROW(backtest({1.0}, {1.0, 2.0}, params), std::invalid_argument);
    EXPECT_THROW(backtest({}, {}, params), std::invalid_argument);
    EXPECT_THROW(backtest({1.0}, {-1.0}, params), std::invalid_argument);
}

TEST(Ses, SumAndErrors) {
    EXPECT_DOUBLE_EQ(ses({{"f1", "d1", 100.0}, {"f2", "d2", 50.5}}), 150.5);
    EXPECT_DOUBLE_EQ(ses({}), 0.0);
    EXPECT_THROW(ses({{"f1", "d1", -1.0}}), std::invalid_argument);
}

TEST(ImaCapital, FormulaAndErrors) {
    EXPECT_DOUBLE_EQ(ima_capital(100.0, 1.5, 20.0, 5.0), 175.0);
    EXPECT_THROW(ima_capital(-1.0, 1.5, 0.0), std::invalid_argument);
    EXPECT_THROW(ima_capital(1.0, 1.5, -1.0), std::invalid_argument);
}

}  // namespace
