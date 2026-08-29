package com.quant.frtb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pricer kit: BS edge cases, put-call parity, Greeks vs finite differences,
 * binomial convergence, bond/swap/FX-forward pricing, input validation.
 */
public class PricersTest {

    private static Curve flatCurve(double r) {
        return new Curve(new double[] {0.5, 1.0, 2.0, 5.0, 10.0},
                new double[] {r, r, r, r, r});
    }

    // ---- Black-Scholes -----------------------------------------------------

    @Test
    public void putCallParityGrid() {
        // property loop: C - P = S e^{-qT} - K e^{-rT} on a strike/maturity grid
        double s = 100.0;
        double r = 0.03;
        double q = 0.015;
        double sigma = 0.25;
        for (double k : new double[] {60.0, 80.0, 100.0, 120.0, 150.0}) {
            for (double t : new double[] {0.1, 0.5, 1.0, 3.0}) {
                double c = Pricers.bsPrice(s, k, t, r, q, sigma, true);
                double p = Pricers.bsPrice(s, k, t, r, q, sigma, false);
                double want = s * Libm.exp(-q * t) - k * Libm.exp(-r * t);
                assertEquals("K=" + k + " T=" + t, want, c - p, 1e-10);
            }
        }
    }

    @Test
    public void expiryIntrinsic() {
        assertEquals(10.0, Pricers.bsPrice(110.0, 100.0, 0.0, 0.05, 0.0, 0.2, true), 0.0);
        assertEquals(0.0, Pricers.bsPrice(90.0, 100.0, 0.0, 0.05, 0.0, 0.2, true), 0.0);
        assertEquals(10.0, Pricers.bsPrice(90.0, 100.0, 0.0, 0.05, 0.0, 0.2, false), 0.0);
    }

    @Test
    public void sigmaZeroDiscountedForwardIntrinsic() {
        double s = 100.0;
        double k = 90.0;
        double t = 2.0;
        double r = 0.05;
        double q = 0.01;
        double want = s * Libm.exp(-q * t) - k * Libm.exp(-r * t);
        assertEquals(want, Pricers.bsPrice(s, k, t, r, q, 0.0, true), 1e-12);
        assertEquals(0.0, Pricers.bsPrice(s, k, t, r, q, 0.0, false), 0.0);
    }

    @Test
    public void deepItmOtm() {
        double s = 100.0;
        double r = 0.03;
        double q = 0.01;
        double sigma = 0.2;
        double t = 1.0;
        // deep ITM call ~ forward intrinsic; deep OTM ~ 0
        double want = s * Libm.exp(-q * t) - 1e-4 * Libm.exp(-r * t);
        assertEquals(want, Pricers.bsPrice(s, 1e-4, t, r, q, sigma, true),
                Math.abs(want) * 1e-9);
        assertEquals(0.0, Pricers.bsPrice(s, 1e6, t, r, q, sigma, true), 1e-9);
    }

    @Test
    public void negativeRatesSupported() {
        double p = Pricers.bsPrice(100.0, 100.0, 1.0, -0.01, -0.005, 0.2, true);
        assertTrue(p > 0.0 && Double.isFinite(p));
    }

    @Test
    public void deltaVegaVsFiniteDifference() {
        double s = 100.0;
        double r = 0.03;
        double q = 0.01;
        double sigma = 0.25;
        double h = 1e-5;
        for (double k : new double[] {80.0, 100.0, 125.0}) {
            for (double t : new double[] {0.25, 1.0, 2.0}) {
                for (boolean call : new boolean[] {true, false}) {
                    double fdD = (Pricers.bsPrice(s + h, k, t, r, q, sigma, call)
                            - Pricers.bsPrice(s - h, k, t, r, q, sigma, call)) / (2 * h);
                    assertEquals(fdD, Pricers.bsDelta(s, k, t, r, q, sigma, call), 1e-7);
                    double fdV = (Pricers.bsPrice(s, k, t, r, q, sigma + h, call)
                            - Pricers.bsPrice(s, k, t, r, q, sigma - h, call)) / (2 * h);
                    assertEquals(fdV, Pricers.bsVega(s, k, t, r, q, sigma), 1e-6);
                }
            }
        }
    }

    @Test
    public void invalidInputsThrow() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Pricers.bsPrice(-1.0, 100.0, 1.0, 0.0, 0.0, 0.2, true))
                .getMessage().contains("positive"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Pricers.bsPrice(100.0, 0.0, 1.0, 0.0, 0.0, 0.2, true))
                .getMessage().contains("positive"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Pricers.bsPrice(100.0, 100.0, -1.0, 0.0, 0.0, 0.2, true))
                .getMessage().contains("maturity"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Pricers.bsPrice(100.0, 100.0, 1.0, 0.0, 0.0, -0.2, true))
                .getMessage().contains("sigma"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Pricers.bsPrice(Double.NaN, 100.0, 1.0, 0.0, 0.0, 0.2, true))
                .getMessage().contains("finite"));
    }

    // ---- binomial benchmark ------------------------------------------------

    @Test
    public void binomialConvergesToBs() {
        double s = 100.0;
        double k = 105.0;
        double t = 1.0;
        double r = 0.03;
        double q = 0.01;
        double sigma = 0.2;
        for (boolean call : new boolean[] {true, false}) {
            double bs = Pricers.bsPrice(s, k, t, r, q, sigma, call);
            assertEquals(bs, Pricers.binomialPrice(s, k, t, r, q, sigma, call, 501), 0.02);
        }
    }

    @Test
    public void binomialEdgeCasesDelegate() {
        assertEquals(10.0, Pricers.binomialPrice(110.0, 100.0, 0.0, 0.05, 0.0, 0.2, true, 100),
                0.0);
        double want = Pricers.bsPrice(100.0, 90.0, 1.0, 0.05, 0.0, 0.0, true);
        assertEquals(want, Pricers.binomialPrice(100.0, 90.0, 1.0, 0.05, 0.0, 0.0, true, 100),
                0.0);
    }

    @Test
    public void binomialBadStepsThrow() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Pricers.binomialPrice(100.0, 100.0, 1.0, 0.0, 0.0, 0.2, true, 0))
                .getMessage().contains("steps"));
    }

    // ---- curve instruments -------------------------------------------------

    @Test
    public void bondPriceFlatCurveHand() {
        // 2y 5% annual bond, flat 3% cc curve
        Bond bond = new Bond("b", 100.0, 0.05, 2.0, "USD", "X", "AAA", 0.75, null);
        double want = 5.0 * Libm.exp(-0.03) + 105.0 * Libm.exp(-0.06);
        assertEquals(want, Pricers.priceBond(bond, flatCurve(0.03)), 1e-12);
    }

    @Test
    public void bondNegativeRates() {
        Bond bond = new Bond("b", 100.0, 0.0, 5.0, "USD", "X", "AAA", 0.75, null);
        assertEquals(100.0 * Libm.exp(0.05), Pricers.priceBond(bond, flatCurve(-0.01)), 1e-10);
    }

    @Test
    public void payerSwapAtParRateIsZero() {
        // flat cc curve: par rate c* = (1 - DF(T)) / annuity -> value 0
        Curve curve = flatCurve(0.03);
        double annuity = curve.df(1.0) + curve.df(2.0) + curve.df(3.0);
        double par = (1.0 - curve.df(3.0)) / annuity;
        PayerSwap swap = new PayerSwap("s", 1e6, par, 3.0, "USD", null);
        assertEquals(0.0, Pricers.pricePayerSwap(swap, curve), 1e-6);
    }

    @Test
    public void payerSwapGainsWhenRatesRise() {
        PayerSwap swap = new PayerSwap("s", 1e6, 0.03, 5.0, "USD", null);
        double vLo = Pricers.pricePayerSwap(swap, flatCurve(0.02));
        double vHi = Pricers.pricePayerSwap(swap, flatCurve(0.04));
        assertTrue(vHi > vLo);
    }

    @Test
    public void fxForwardZeroAtMarketForward() {
        Curve dom = flatCurve(0.03);
        Curve forC = flatCurve(0.02);
        double spot = 1.10;
        double t = 1.0;
        double k = spot * forC.df(t) / dom.df(t); // zero-value strike
        FxForward fwd = new FxForward("f", "EURUSD", 1e6, k, t, null);
        assertEquals(0.0, Pricers.priceFxForward(fwd, spot, dom, forC), 1e-9);
    }

    @Test
    public void fxForwardBadSpotThrows() {
        FxForward fwd = new FxForward("f", "EURUSD", 1e6, 1.1, 1.0, null);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Pricers.priceFxForward(fwd, -1.0, flatCurve(0.03), flatCurve(0.02)))
                .getMessage().contains("spot"));
    }

    @Test
    public void curveValidation() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new Curve(new double[] {1.0, 1.0}, new double[] {0.03, 0.03}))
                .getMessage().contains("increasing"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> flatCurve(0.03).bumpedNode(7.0, 1e-4))
                .getMessage().contains("node"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> flatCurve(0.03).rate(-1.0))
                .getMessage().contains("invalid time"));
    }
}
