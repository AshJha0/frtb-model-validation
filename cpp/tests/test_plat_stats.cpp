/// PLAT and native-statistics tests: rank/Spearman/KS hand cases and tie
/// handling, pinned zone thresholds at the exact boundaries, the constant-
/// series Red convention, and the Amber surcharge formula.

#include <gtest/gtest.h>

#include <cmath>

#include "frtb/plat.hpp"
#include "frtb/stats.hpp"
#include "test_helpers.hpp"

namespace {

using namespace frtb;

TEST(Stats, AverageRanksWithTies) {
    // Values 10, 20, 20, 30 -> ranks 1, 2.5, 2.5, 4.
    std::vector<double> r = average_ranks({10.0, 20.0, 20.0, 30.0});
    EXPECT_DOUBLE_EQ(r[0], 1.0);
    EXPECT_DOUBLE_EQ(r[1], 2.5);
    EXPECT_DOUBLE_EQ(r[2], 2.5);
    EXPECT_DOUBLE_EQ(r[3], 4.0);
}

TEST(Stats, SpearmanMonotone) {
    std::vector<double> x = {1.0, 2.0, 3.0, 4.0, 5.0};
    std::vector<double> y_up = {10.0, 20.0, 25.0, 40.0, 100.0};
    std::vector<double> y_dn = {5.0, 4.0, 3.0, 2.0, 1.0};
    EXPECT_NEAR(spearman(x, y_up), 1.0, 1e-12);
    EXPECT_NEAR(spearman(x, y_dn), -1.0, 1e-12);
}

TEST(Stats, PearsonErrors) {
    EXPECT_THROW(pearson({1.0, 1.0, 1.0}, {1.0, 2.0, 3.0}), std::invalid_argument);  // constant
    EXPECT_THROW(pearson({1.0}, {2.0}), std::invalid_argument);                      // too short
    EXPECT_THROW(pearson({1.0, 2.0}, {1.0, 2.0, 3.0}), std::invalid_argument);       // mismatch
    EXPECT_THROW(spearman({1.0, NAN, 3.0}, {1.0, 2.0, 3.0}), std::invalid_argument);
}

TEST(Stats, KsHandCases) {
    // Identical samples: D = 0.  Fully separated samples: D = 1.
    std::vector<double> a = {1.0, 2.0, 3.0, 4.0};
    EXPECT_DOUBLE_EQ(ks_statistic(a, a), 0.0);
    EXPECT_DOUBLE_EQ(ks_statistic({1.0, 2.0}, {10.0, 20.0}), 1.0);
    // Hand case with ties across samples: max pooled ECDF gap = 0.25.
    EXPECT_DOUBLE_EQ(ks_statistic({1.0, 2.0, 3.0, 4.0}, {2.0, 3.0, 4.0, 5.0}), 0.25);
    EXPECT_THROW(ks_statistic({}, {1.0}), std::invalid_argument);
}

TEST(Plat, ZoneThresholdBoundaries) {
    const SbmParams& p = frtb_test::params();
    // Exact green boundary: spearman = 0.85, KS = 0.09.
    EXPECT_EQ(plat_zone_from_metrics(0.85, 0.09, p), "green");
    // Just below green spearman / above green KS -> amber.
    EXPECT_EQ(plat_zone_from_metrics(0.8499, 0.09, p), "amber");
    EXPECT_EQ(plat_zone_from_metrics(0.85, 0.0901, p), "amber");
    // Amber floor is inclusive: spearman = 0.80, KS = 0.12 stays amber.
    EXPECT_EQ(plat_zone_from_metrics(0.80, 0.12, p), "amber");
    // Below the amber floor / above the amber KS cap -> red.
    EXPECT_EQ(plat_zone_from_metrics(0.7999, 0.05, p), "red");
    EXPECT_EQ(plat_zone_from_metrics(0.9, 0.1201, p), "red");
    EXPECT_THROW(plat_zone_from_metrics(NAN, 0.05, p), std::invalid_argument);
}

TEST(Plat, ConstantSeriesIsRedWithNullMetrics) {
    const SbmParams& p = frtb_test::params();
    std::vector<double> constant(10, 3.0);
    std::vector<double> varying = {1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0};
    PlatResult r = plat_test(constant, varying, p);
    EXPECT_EQ(r.zone, "red");
    EXPECT_FALSE(r.spearman.has_value());
    EXPECT_FALSE(r.ks.has_value());
    EXPECT_THROW(plat_test({1.0, 2.0}, {1.0, 2.0}, p), std::invalid_argument);  // < 3 obs
    EXPECT_THROW(plat_test({1.0, 2.0, 3.0}, {1.0, 2.0}, p), std::invalid_argument);
}

TEST(Plat, SurchargeFormula) {
    const SbmParams& p = frtb_test::params();
    EXPECT_DOUBLE_EQ(plat_surcharge("amber", 300.0, 100.0, p), 100.0);  // 0.5*(300-100)
    EXPECT_DOUBLE_EQ(plat_surcharge("amber", 100.0, 300.0, p), 0.0);    // floored at 0
    EXPECT_DOUBLE_EQ(plat_surcharge("green", 300.0, 100.0, p), 0.0);
    EXPECT_DOUBLE_EQ(plat_surcharge("red", 300.0, 100.0, p), 0.0);
    EXPECT_THROW(plat_surcharge("blue", 1.0, 1.0, p), std::invalid_argument);
    EXPECT_THROW(plat_surcharge("amber", -1.0, 1.0, p), std::invalid_argument);
}

TEST(Plat, BundledDesksMatchPinnedFacts) {
    // desk1 green (no surcharge), desk2 amber (positive surcharge since SA > IMA core).
    const frtb::Results& res = frtb_test::results();
    EXPECT_EQ(res.ima.at("desk1").plat.zone, "green");
    EXPECT_EQ(res.ima.at("desk2").plat.zone, "amber");
    EXPECT_DOUBLE_EQ(res.ima.at("desk1").plat_surcharge, 0.0);
    EXPECT_GT(res.ima.at("desk2").plat_surcharge, 0.0);
}

}  // namespace
