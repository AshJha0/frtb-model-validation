/// Pricer kit tests: Black-Scholes values and edge cases, put-call parity
/// grid, binomial benchmark convergence, Greeks vs finite differences, curve
/// and linear-instrument pricing, invalid-input error paths.

#include <gtest/gtest.h>

#include <cmath>

#include "frtb/instruments.hpp"
#include "frtb/market.hpp"
#include "frtb/pricers.hpp"

namespace {

using namespace frtb;

TEST(BlackScholes, KnownAtmValue) {
    // Hull-style reference: S=100, K=100, T=1, r=5%, q=0, sigma=20%.
    EXPECT_NEAR(bs_price(100.0, 100.0, 1.0, 0.05, 0.0, 0.2, true), 10.4506, 1e-4);
}

TEST(BlackScholes, PutCallParityGrid) {
    // Property-style loop: C - P = S e^{-qT} - K e^{-rT} across a grid.
    const double s = 100.0, r = 0.03, q = 0.015, sigma = 0.25;
    for (double k : {50.0, 80.0, 100.0, 120.0, 200.0})
        for (double t : {0.1, 0.5, 1.0, 3.0}) {
            double c = bs_price(s, k, t, r, q, sigma, true);
            double p = bs_price(s, k, t, r, q, sigma, false);
            double fwd = s * std::exp(-q * t) - k * std::exp(-r * t);
            EXPECT_NEAR(c - p, fwd, 1e-10) << "K=" << k << " T=" << t;
        }
}

TEST(BlackScholes, EdgeCases) {
    // T = 0: intrinsic value.
    EXPECT_DOUBLE_EQ(bs_price(110.0, 100.0, 0.0, 0.05, 0.0, 0.2, true), 10.0);
    EXPECT_DOUBLE_EQ(bs_price(90.0, 100.0, 0.0, 0.05, 0.0, 0.2, true), 0.0);
    // sigma = 0: discounted deterministic payoff.
    double expect = 100.0 * std::exp(-0.01) - 90.0 * std::exp(-0.03);
    EXPECT_DOUBLE_EQ(bs_price(100.0, 90.0, 1.0, 0.03, 0.01, 0.0, true), expect);
    // Deep ITM call ~ discounted forward; deep OTM ~ 0.
    EXPECT_GT(bs_price(100.0, 1.0, 1.0, 0.03, 0.0, 0.2, true), 95.0);
    EXPECT_LT(bs_price(100.0, 1000.0, 1.0, 0.03, 0.0, 0.2, true), 1e-6);
    // Negative rates are fine (parity still holds).
    double c = bs_price(100.0, 100.0, 1.0, -0.01, 0.0, 0.2, true);
    double p = bs_price(100.0, 100.0, 1.0, -0.01, 0.0, 0.2, false);
    EXPECT_NEAR(c - p, 100.0 - 100.0 * std::exp(0.01), 1e-10);
}

TEST(BlackScholes, InvalidInputsThrow) {
    EXPECT_THROW(bs_price(-1.0, 100.0, 1.0, 0.0, 0.0, 0.2, true), std::invalid_argument);
    EXPECT_THROW(bs_price(100.0, 0.0, 1.0, 0.0, 0.0, 0.2, true), std::invalid_argument);
    EXPECT_THROW(bs_price(100.0, 100.0, -1.0, 0.0, 0.0, 0.2, true), std::invalid_argument);
    EXPECT_THROW(bs_price(100.0, 100.0, 1.0, 0.0, 0.0, -0.2, true), std::invalid_argument);
    EXPECT_THROW(bs_price(NAN, 100.0, 1.0, 0.0, 0.0, 0.2, true), std::invalid_argument);
    EXPECT_THROW(binomial_price(100.0, 100.0, 1.0, 0.0, 0.0, 0.2, true, 0),
                 std::invalid_argument);
}

TEST(BlackScholes, GreeksVsFiniteDifference) {
    const double s = 100.0, k = 105.0, t = 0.75, r = 0.02, q = 0.01, sigma = 0.3;
    const double h = 1e-4 * s;
    for (bool call : {true, false}) {
        double fd = (bs_price(s + h, k, t, r, q, sigma, call) -
                     bs_price(s - h, k, t, r, q, sigma, call)) /
                    (2.0 * h);
        EXPECT_NEAR(bs_delta(s, k, t, r, q, sigma, call), fd, 1e-6);
    }
    double dv = 1e-5;
    double fd_vega =
        (bs_price(s, k, t, r, q, sigma + dv, true) - bs_price(s, k, t, r, q, sigma - dv, true)) /
        (2.0 * dv);
    EXPECT_NEAR(bs_vega(s, k, t, r, q, sigma), fd_vega, 1e-5);
}

TEST(Binomial, ConvergesToBlackScholes) {
    for (double k : {85.0, 100.0, 115.0})
        for (bool call : {true, false}) {
            double bs = bs_price(100.0, k, 1.0, 0.03, 0.01, 0.2, call);
            double bin = binomial_price(100.0, k, 1.0, 0.03, 0.01, 0.2, call, 501);
            EXPECT_NEAR(bin, bs, 0.05) << "K=" << k;
        }
}

Curve usd_flat() { return Curve({1.0, 2.0, 3.0, 5.0}, {0.03, 0.03, 0.03, 0.03}); }

TEST(Curve, InterpolationAndDf) {
    Curve c({1.0, 2.0}, {0.02, 0.04});
    EXPECT_DOUBLE_EQ(c.rate(1.5), 0.03);   // linear interior
    EXPECT_DOUBLE_EQ(c.rate(0.5), 0.02);   // flat left extrapolation
    EXPECT_DOUBLE_EQ(c.rate(10.0), 0.04);  // flat right extrapolation
    EXPECT_DOUBLE_EQ(c.df(0.0), 1.0);
    EXPECT_DOUBLE_EQ(c.df(2.0), std::exp(-0.04 * 2.0));
}

TEST(Curve, InvalidInputsThrow) {
    EXPECT_THROW(Curve({}, {}), std::invalid_argument);
    EXPECT_THROW(Curve({2.0, 1.0}, {0.01, 0.02}), std::invalid_argument);  // non-increasing
    EXPECT_THROW(Curve({-1.0, 1.0}, {0.01, 0.02}), std::invalid_argument);
    EXPECT_THROW(usd_flat().bumped_node(4.0, 1e-4), std::invalid_argument);  // non-node tenor
    EXPECT_THROW(usd_flat().rate(-1.0), std::invalid_argument);
}

TEST(Bond, HandPricedValue) {
    // 3y 5% annual bond on a flat 3% curve: PV = sum 0.05*N*DF(i) + N*DF(3).
    Bond b("B", 100.0, 0.05, 3.0, "USD", "ISS", "AAA");
    Curve c = usd_flat();
    double expect = 100.0 * c.df(3.0);
    for (double t : {1.0, 2.0, 3.0}) expect += 0.05 * 100.0 * c.df(t);
    EXPECT_DOUBLE_EQ(price_bond(b, c), expect);
    EXPECT_GT(price_bond(b, c), 100.0);  // coupon above the curve -> premium
}

TEST(PayerSwap, AtParFairRateIsNearZero) {
    // On a flat curve the par rate is ~ (1-DF(T))/annuity; value there is 0.
    Curve c = usd_flat();
    double annuity = c.df(1.0) + c.df(2.0) + c.df(3.0) + c.df(5.0);
    (void)annuity;
    double a = 0.0;
    for (double t : {1.0, 2.0, 3.0, 4.0, 5.0}) a += c.df(t);
    double par = (1.0 - c.df(5.0)) / a;
    PayerSwap s("S", 1e6, par, 5.0, "USD");
    EXPECT_NEAR(price_payer_swap(s, c), 0.0, 1e-6);
    // Payer swap gains when rates rise.
    PayerSwap s2("S2", 1e6, 0.03, 5.0, "USD");
    double v0 = price_payer_swap(s2, c);
    double v1 = price_payer_swap(s2, c.bumped_parallel(0.01));
    EXPECT_GT(v1, v0);
}

TEST(FxForward, HandValue) {
    FxForward f("F", "EURUSD", 1e6, 1.10, 1.0);
    Curve dom({1.0}, {0.03});
    Curve fgn({1.0}, {0.02});
    double expect = 1e6 * (1.085 * fgn.df(1.0) - 1.10 * dom.df(1.0));
    EXPECT_DOUBLE_EQ(price_fx_forward(f, 1.085, dom, fgn), expect);
    EXPECT_THROW(price_fx_forward(f, -1.0, dom, fgn), std::invalid_argument);
}

TEST(Instruments, ValidationThrows) {
    EXPECT_THROW(Bond("B", 0.0, 0.05, 3.0, "USD", "I", "AAA"), std::invalid_argument);
    EXPECT_THROW(Bond("B", 1.0, 0.05, -1.0, "USD", "I", "AAA"), std::invalid_argument);
    EXPECT_THROW(Bond("B", 1.0, 0.05, 3.0, "USD", "I", "AAA", 1.5), std::invalid_argument);
    EXPECT_THROW(PayerSwap("S", 1.0, 0.02, 0.5, "USD"), std::invalid_argument);
    EXPECT_THROW(EquityOption("O", "X", "straddle", 1, 1.0, 100.0, 1.0, "USD"),
                 std::invalid_argument);
    EXPECT_THROW(EquityOption("O", "X", "call", 2, 1.0, 100.0, 1.0, "USD"),
                 std::invalid_argument);
    EXPECT_THROW(FxForward("F", "EURUSD!", 1.0, 1.1, 1.0), std::invalid_argument);
    EXPECT_THROW(RraoFlag("weird", 1.0), std::invalid_argument);
}

TEST(Market, LookupErrors) {
    Market m;
    m.curves.emplace("USD", usd_flat());
    EXPECT_THROW(m.curve("JPY"), std::invalid_argument);
    EXPECT_THROW(m.equity("NOPE"), std::invalid_argument);
    EXPECT_THROW(m.fx_spot("EURUSD"), std::invalid_argument);
}

}  // namespace
