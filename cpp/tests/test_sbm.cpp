/// SBM aggregation tests: hand-computable bucket/across-bucket cases, the
/// correlation-scenario scaling (high cap at 1), the S_b fallback branch,
/// curvature psi rules and tie-breaking, and error paths.

#include <gtest/gtest.h>

#include <cmath>

#include "frtb/sbm.hpp"

namespace {

using namespace frtb;

TEST(ScaleRho, ScenariosAndCap) {
    EXPECT_DOUBLE_EQ(scale_rho(0.5, "medium"), 0.5);
    EXPECT_DOUBLE_EQ(scale_rho(0.5, "high"), 0.625);
    EXPECT_DOUBLE_EQ(scale_rho(0.9, "high"), 1.0);  // 1.25*0.9 = 1.125 capped at 1
    EXPECT_DOUBLE_EQ(scale_rho(1.0, "high"), 1.0);
    EXPECT_DOUBLE_EQ(scale_rho(0.8, "low"), 0.6);
    EXPECT_THROW(scale_rho(0.5, "sideways"), std::invalid_argument);
}

TEST(BucketKb, HandTwoFactor) {
    // K = sqrt(10^2 + (-5)^2 + 2*0.5*10*(-5)) = sqrt(75).
    double k = bucket_kb({10.0, -5.0}, [](std::size_t, std::size_t) { return 0.5; });
    EXPECT_NEAR(k, std::sqrt(75.0), 1e-12);
}

TEST(BucketKb, NegativeRoundingGuard) {
    // Constructed rho makes the quadratic form negative: max(0,.) clamps to 0.
    double k = bucket_kb({1.0, 1.0}, [](std::size_t, std::size_t) { return -1.5; });
    EXPECT_DOUBLE_EQ(k, 0.0);
    EXPECT_THROW(bucket_kb({1.0, NAN}, [](std::size_t, std::size_t) { return 0.0; }),
                 std::invalid_argument);
}

TEST(AggregateBuckets, NoFallbackHandCase) {
    // total = K_A^2 + K_B^2 + 2*gamma*S_A*S_B, all hand-numbers.
    AggregateResult agg = aggregate_buckets(
        {{"A", 3.0}, {"B", 4.0}}, {{"A", 3.0}, {"B", -4.0}},
        [](const std::string&, const std::string&) { return 0.25; });
    EXPECT_FALSE(agg.used_fallback);
    EXPECT_NEAR(agg.charge, std::sqrt(9.0 + 16.0 + 2.0 * 0.25 * 3.0 * (-4.0)), 1e-12);
}

TEST(AggregateBuckets, SbFallbackBranch) {
    // Small K_b with large opposite-signed S_b forces the inner sum negative;
    // the fallback clamps S_b into [-K_b, K_b] and recomputes once.
    AggregateResult agg = aggregate_buckets(
        {{"A", 0.1}, {"B", 0.1}}, {{"A", 10.0}, {"B", -10.0}},
        [](const std::string&, const std::string&) { return 0.5; });
    EXPECT_TRUE(agg.used_fallback);
    EXPECT_DOUBLE_EQ(agg.sb.at("A"), 0.1);
    EXPECT_DOUBLE_EQ(agg.sb.at("B"), -0.1);
    // total = 0.01 + 0.01 + 2*0.5*(0.1*-0.1) = 0.01
    EXPECT_NEAR(agg.charge, 0.1, 1e-12);
}

TEST(AggregateBuckets, BucketMismatchThrows) {
    EXPECT_THROW(aggregate_buckets({{"A", 1.0}}, {{"B", 1.0}},
                                   [](const std::string&, const std::string&) { return 0.0; }),
                 std::invalid_argument);
}

TEST(DeltaVegaCharge, SingleBucketMatchesBucketKb) {
    BucketWs ws = {{"1", {{"x", 2.0}, {"y", -1.0}}}};
    auto rho = [](const std::string&, const std::string&, const std::string&) { return 0.15; };
    auto gamma = [](const std::string&, const std::string&) { return 0.15; };
    RiskClassCharge r = delta_vega_charge(ws, rho, gamma, "medium");
    double expect = bucket_kb({2.0, -1.0}, [](std::size_t, std::size_t) { return 0.15; });
    EXPECT_NEAR(r.charge, expect, 1e-14);
    EXPECT_NEAR(r.kb.at("1"), expect, 1e-14);
    // Empty risk class -> zero charge.
    EXPECT_DOUBLE_EQ(delta_vega_charge({}, rho, gamma, "medium").charge, 0.0);
    // High/low scenarios bracket medium for same-sign sensitivities.
    BucketWs pos = {{"1", {{"x", 2.0}, {"y", 1.0}}}};
    double hi = delta_vega_charge(pos, rho, gamma, "high").charge;
    double md = delta_vega_charge(pos, rho, gamma, "medium").charge;
    double lo = delta_vega_charge(pos, rho, gamma, "low").charge;
    EXPECT_GT(hi, md);
    EXPECT_GT(md, lo);
}

TEST(Psi, BothNegativeRule) {
    EXPECT_DOUBLE_EQ(psi(-1.0, -2.0), 0.0);
    EXPECT_DOUBLE_EQ(psi(-1.0, 2.0), 1.0);
    EXPECT_DOUBLE_EQ(psi(1.0, 2.0), 1.0);
    EXPECT_DOUBLE_EQ(psi(0.0, -1.0), 1.0);  // zero is not negative
}

TEST(CurvatureBucketKb, PsiZeroesBothNegativePairs) {
    // Both CVRs negative: cross term killed by psi and max(CVR,0)=0 -> K=0.
    auto rho = [](std::size_t, std::size_t) { return 1.0; };
    auto [k, s] = curvature_bucket_kb({-1.0, -2.0}, {-3.0, -4.0}, rho);
    EXPECT_DOUBLE_EQ(k, 0.0);
    EXPECT_DOUBLE_EQ(s, -3.0);  // tie (0 == 0) -> up side sum
}

TEST(CurvatureBucketKb, WinningSideAndTie) {
    auto rho = [](std::size_t, std::size_t) { return 0.0; };
    // Down side wins: K- = 5 > K+ = 1 -> S_b from the down side.
    auto [k1, s1] = curvature_bucket_kb({1.0}, {5.0}, rho);
    EXPECT_DOUBLE_EQ(k1, 5.0);
    EXPECT_DOUBLE_EQ(s1, 5.0);
    // Exact tie -> up side (pinned).
    auto [k2, s2] = curvature_bucket_kb({2.0}, {2.0}, rho);
    EXPECT_DOUBLE_EQ(k2, 2.0);
    EXPECT_DOUBLE_EQ(s2, 2.0);
    EXPECT_THROW(curvature_bucket_kb({1.0}, {1.0, 2.0}, rho), std::invalid_argument);
}

TEST(CurvatureCharge, SquaredCorrelationsAndPsiAcrossBuckets) {
    // Two one-factor buckets, opposite S_b signs -> cross term suppressed by
    // psi only when both negative; correlations enter squared.
    BucketCvr cvr = {{"A", {{3.0}, {1.0}}}, {"B", {{4.0}, {0.5}}}};
    FactorKeys keys = {{"A", {"a"}}, {"B", {"b"}}};
    auto rho = [](const std::string&, const std::string&, const std::string&) { return 0.5; };
    auto gamma = [](const std::string&, const std::string&) { return 0.5; };
    RiskClassCharge r = curvature_charge(cvr, rho, gamma, "medium", keys);
    // K_A = 3, K_B = 4, gamma^2 = 0.25, S_A = 3, S_B = 4 (up sides win).
    EXPECT_NEAR(r.charge, std::sqrt(9.0 + 16.0 + 2.0 * 0.25 * 12.0), 1e-12);
    // Both-negative S_b pair: psi = 0 kills the cross term entirely.
    BucketCvr neg = {{"A", {{-3.0}, {-1.0}}}, {"B", {{-4.0}, {-0.5}}}};
    RiskClassCharge rn = curvature_charge(neg, rho, gamma, "medium", keys);
    EXPECT_DOUBLE_EQ(rn.charge, 0.0);  // K_b = 0 each and psi(S_A,S_B) = 0
    EXPECT_THROW(curvature_charge(cvr, rho, gamma, "medium", FactorKeys{{"A", {}}, {"B", {"b"}}}),
                 std::invalid_argument);
}

TEST(GoldenHandCheck, TwoBucketAggregation) {
    // Same numbers as the sbm_agg_hand_2bucket golden, asserted at 1e-12.
    double k_a = bucket_kb({10.0, -5.0}, [](std::size_t, std::size_t) { return 0.5; });
    double k_b = bucket_kb({8.0}, [](std::size_t, std::size_t) { return 0.0; });
    AggregateResult agg =
        aggregate_buckets({{"A", k_a}, {"B", k_b}}, {{"A", 5.0}, {"B", 8.0}},
                          [](const std::string&, const std::string&) { return 0.25; });
    EXPECT_NEAR(k_a, 8.660254037844387, 1e-12);
    EXPECT_NEAR(k_b, 8.0, 1e-12);
    EXPECT_NEAR(agg.charge, 12.609520212918492, 1e-12);
    EXPECT_FALSE(agg.used_fallback);
}

}  // namespace
