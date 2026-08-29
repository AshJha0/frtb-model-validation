/// Validation-framework tests: benchmark and FD checks pass their pinned
/// thresholds, data-quality counting, every findings rule fires on a
/// constructed failure, verdict logic, and the report generator emitting all
/// ten sections.

#include <gtest/gtest.h>

#include <cmath>
#include <limits>

#include "frtb/validation.hpp"
#include "test_helpers.hpp"

namespace {

using namespace frtb;

TEST(Checks, BenchmarkWithinPinnedTolerance) {
    double d = benchmark_max_diff();
    EXPECT_GT(d, 0.0);
    EXPECT_LE(d, BENCH_TOL);
}

TEST(Checks, DeltaFdWithinPinnedTolerance) {
    double d = sensitivity_max_diff();
    EXPECT_GE(d, 0.0);
    EXPECT_LE(d, SENS_TOL);
}

TEST(Checks, DataQualityCounting) {
    const double nan = std::numeric_limits<double>::quiet_NaN();
    DataQuality dq = data_quality({1.0, 1.0, 2.0, nan, 2.0, 2.0});
    EXPECT_EQ(dq.gaps, 1);
    // clean = 1,1,2,2,2 -> zero-change pairs: (1,1), (2,2), (2,2) = 3.
    EXPECT_EQ(dq.stale_days, 3);
    EXPECT_THROW(data_quality({1.0}), std::invalid_argument);
}

DeskCheckInputs clean_inputs() {
    DeskCheckInputs c;
    c.benchmark_max_diff = 0.01;
    c.sensitivity_max_diff = 1e-9;
    c.stability_rel_change = 0.05;
    c.backtest_zone = "green";
    c.plat_zone = "green";
    c.stale_days = 0;
    c.gaps = 0;
    return c;
}

TEST(Findings, NoFindingsOnCleanInputs) {
    EXPECT_TRUE(classify_findings(clean_inputs()).empty());
    EXPECT_EQ(overall_verdict({}), "approve");
}

TEST(Findings, EachRuleFiresOnConstructedFailure) {
    struct Case {
        const char* rule;
        const char* severity;
        void (*mutate)(DeskCheckInputs&);
    };
    const Case cases[] = {
        {"BENCH-01", "High", [](DeskCheckInputs& c) { c.benchmark_max_diff = 0.06; }},
        {"SENS-01", "High", [](DeskCheckInputs& c) { c.sensitivity_max_diff = 1e-5; }},
        {"BT-01", "High", [](DeskCheckInputs& c) { c.backtest_zone = "red"; }},
        {"BT-02", "Medium", [](DeskCheckInputs& c) { c.backtest_zone = "amber"; }},
        {"PLAT-01", "High", [](DeskCheckInputs& c) { c.plat_zone = "red"; }},
        {"PLAT-02", "Medium", [](DeskCheckInputs& c) { c.plat_zone = "amber"; }},
        {"STAB-01", "Medium", [](DeskCheckInputs& c) { c.stability_rel_change = 0.30; }},
        {"DQ-01", "Medium", [](DeskCheckInputs& c) { c.stale_days = 16; }},
        {"DQ-02", "Low", [](DeskCheckInputs& c) { c.gaps = 2; }},
    };
    for (const Case& tc : cases) {
        DeskCheckInputs c = clean_inputs();
        tc.mutate(c);
        std::vector<Finding> fs = classify_findings(c);
        ASSERT_EQ(fs.size(), 1u) << tc.rule;
        EXPECT_EQ(fs[0].rule_id, tc.rule);
        EXPECT_EQ(fs[0].severity, tc.severity);
        EXPECT_FALSE(fs[0].description.empty());
    }
}

TEST(Findings, StrictThresholdBoundaries) {
    // All rule comparisons are strict '>': values AT the threshold don't fire.
    DeskCheckInputs c = clean_inputs();
    c.benchmark_max_diff = BENCH_TOL;
    c.sensitivity_max_diff = SENS_TOL;
    c.stability_rel_change = STABILITY_THRESHOLD;
    c.stale_days = STALENESS_THRESHOLD;
    EXPECT_TRUE(classify_findings(c).empty());
}

TEST(Findings, VerdictSeverityLadder) {
    EXPECT_EQ(overall_verdict({{"X", "Low", "d"}}), "approve");
    EXPECT_EQ(overall_verdict({{"X", "Low", "d"}, {"Y", "Medium", "d"}}),
              "approve-with-conditions");
    EXPECT_EQ(overall_verdict({{"X", "Medium", "d"}, {"Y", "High", "d"}}), "reject");
}

TEST(Report, ContainsAllTenSections) {
    const std::string& md = frtb_test::results().validation.report_md;
    ASSERT_EQ(REPORT_SECTIONS.size(), 10u);
    for (const std::string& section : REPORT_SECTIONS)
        EXPECT_NE(md.find("## " + section), std::string::npos) << section;
}

TEST(Report, ContainsVerdictsAndDisclaimers) {
    const frtb::Results& res = frtb_test::results();
    const std::string& md = res.validation.report_md;
    EXPECT_NE(md.find("desk1: **approve**"), std::string::npos);
    EXPECT_NE(md.find("desk2: **approve-with-conditions**"), std::string::npos);
    EXPECT_NE(md.find("Educational FRTB implementation"), std::string::npos);
    EXPECT_NE(md.find("[BT-02]"), std::string::npos);    // desk2 amber backtest
    EXPECT_NE(md.find("[PLAT-02]"), std::string::npos);  // desk2 amber PLAT
}

TEST(Stability, BundledBookIsStable) {
    const frtb::Results& res = frtb_test::results();
    const ValidationBlock& val = res.validation;
    EXPECT_GT(val.stability_capital_rw_up10, val.stability_base_capital);
    EXPECT_LT(val.stability_capital_rw_dn10, val.stability_base_capital);
    EXPECT_LE(val.stability_rel_change, STABILITY_THRESHOLD);  // no STAB-01 finding
}

}  // namespace
