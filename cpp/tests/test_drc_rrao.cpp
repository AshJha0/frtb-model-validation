/// DRC-lite and RRAO tests: JTD formula, same-issuer netting, HBR weighting
/// on a hand case, all-long and empty-book edge cases, rating error paths,
/// and the pinned RRAO rates.

#include <gtest/gtest.h>

#include "frtb/sa.hpp"
#include "test_helpers.hpp"

namespace {

using namespace frtb;

TEST(Drc, JtdFormula) {
    // JTD = LGD*notional + (MV - notional): 0.75*100 + (98 - 100) = 73.
    DrcPosition p{"ISS", "BBB", 100.0, 98.0, 0.75};
    EXPECT_DOUBLE_EQ(p.jtd(), 73.0);
    // Short position: negative notional flips the sign.
    DrcPosition s{"ISS", "BBB", -100.0, -98.0, 0.75};
    EXPECT_DOUBLE_EQ(s.jtd(), -73.0);
}

TEST(Drc, SameIssuerNetting) {
    // Long 100 + short 40 on the same issuer nets before weighting.
    const SbmParams& params = frtb_test::params();
    std::vector<DrcPosition> book = {
        {"ISS", "BBB", 100.0, 100.0, 0.75},   // JTD = 75
        {"ISS", "BBB", -40.0, -40.0, 0.75},   // JTD = -30
    };
    DrcResult r = drc_charge(book, params);
    ASSERT_EQ(r.net_jtd.size(), 1u);
    EXPECT_DOUBLE_EQ(r.net_jtd[0].second, 45.0);
    EXPECT_DOUBLE_EQ(r.hbr, 1.0);  // all-long after netting
    EXPECT_DOUBLE_EQ(r.charge, 0.06 * 45.0);
}

TEST(Drc, HbrHandCase) {
    // net longs: A 75 (AAA), B 30 (BB); net short: C -45 (BBB).
    const SbmParams& params = frtb_test::params();
    std::vector<DrcPosition> book = {
        {"A", "AAA", 100.0, 100.0, 0.75},
        {"B", "BB", 40.0, 40.0, 0.75},
        {"C", "BBB", -60.0, -60.0, 0.75},
    };
    DrcResult r = drc_charge(book, params);
    const double hbr = 105.0 / 150.0;
    EXPECT_DOUBLE_EQ(r.hbr, hbr);
    EXPECT_DOUBLE_EQ(r.gross_long, 105.0);
    EXPECT_DOUBLE_EQ(r.gross_short, 45.0);
    const double expect = 0.005 * 75.0 + 0.15 * 30.0 - hbr * 0.06 * 45.0;
    EXPECT_NEAR(r.charge, expect, 1e-12);
}

TEST(Drc, EmptyBookAndFloor) {
    const SbmParams& params = frtb_test::params();
    DrcResult empty = drc_charge({}, params);
    EXPECT_DOUBLE_EQ(empty.charge, 0.0);
    EXPECT_DOUBLE_EQ(empty.hbr, 1.0);
    // Net-short-only book: charge floored at 0 (HBR = 0).
    DrcResult shorts = drc_charge({{"A", "B", -100.0, -100.0, 0.75}}, params);
    EXPECT_DOUBLE_EQ(shorts.hbr, 0.0);
    EXPECT_DOUBLE_EQ(shorts.charge, 0.0);
}

TEST(Drc, RatingErrors) {
    const SbmParams& params = frtb_test::params();
    EXPECT_THROW(drc_charge({{"A", "ZZZ", 100.0, 100.0, 0.75}}, params), std::invalid_argument);
    // One issuer with two different ratings is rejected.
    EXPECT_THROW(drc_charge({{"A", "AAA", 100.0, 100.0, 0.75},
                             {"A", "BB", 50.0, 50.0, 0.75}},
                            params),
                 std::invalid_argument);
}

TEST(Rrao, PinnedRatesAndErrors) {
    const SbmParams& params = frtb_test::params();
    EXPECT_DOUBLE_EQ(params.rrao_rate("exotic"), 0.01);
    EXPECT_DOUBLE_EQ(params.rrao_rate("other"), 0.001);
    EXPECT_THROW(params.rrao_rate("plain"), std::invalid_argument);

    std::vector<Instrument> insts = {
        Bond("B1", 1e6, 0.05, 3.0, "USD", "I", "AAA", 0.75, RraoFlag("other", 1e6)),
        EquityOption("O1", "AAA_TECH", "call", -1, 1000.0, 100.0, 1.0, "USD",
                     RraoFlag("exotic", 5e5)),
        FxForward("F1", "EURUSD", 1e6, 1.1, 1.0),  // unflagged: no contribution
    };
    EXPECT_DOUBLE_EQ(rrao_charge(insts, params), 0.001 * 1e6 + 0.01 * 5e5);
    EXPECT_DOUBLE_EQ(rrao_charge({}, params), 0.0);
}

TEST(Drc, FirmBondsAllLong) {
    // The bundled firm book is all-long: HBR = 1 by construction.
    const frtb::Results& res = frtb_test::results();
    EXPECT_DOUBLE_EQ(res.sa.at("firm").drc_hbr, 1.0);
    EXPECT_GT(res.sa.at("firm").drc, 0.0);
}

}  // namespace
