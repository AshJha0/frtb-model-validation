package com.quant.frtb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/** IMA sketch: ES definition, LH ladder, IMCC, backtest zones/multipliers, SES. */
public class ImaTest {

    private static final int[] LADDER = {10, 20, 40, 60, 120};

    private static double[] series(int n, java.util.function.IntToDoubleFunction f) {
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = f.applyAsDouble(i);
        }
        return out;
    }

    // ---- expected shortfall ------------------------------------------------

    @Test
    public void pinnedTailDefinition() {
        // n = 40, alpha = 0.975 -> k = ceil(1) = 1 -> ES = worst loss
        double[] pnl = series(40, i -> i - 20);
        assertEquals(20.0, Ima.expectedShortfallDaily(pnl, 0.975), 0.0);
        assertEquals(20.0 * Math.sqrt(10.0), Ima.esBase10d(pnl, 0.975), 1e-12);
    }

    @Test
    public void kOfTwo() {
        // n = 80 -> k = ceil(2) = 2 -> mean of two worst losses
        double[] pnl = new double[80];
        pnl[78] = -30.0;
        pnl[79] = -10.0;
        assertEquals(20.0, Ima.expectedShortfallDaily(pnl, 0.975), 1e-12);
    }

    @Test
    public void esErrors() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Ima.expectedShortfallDaily(new double[0], 0.975))
                .getMessage().contains("empty"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Ima.expectedShortfallDaily(new double[] {1.0}, 1.5))
                .getMessage().contains("alpha"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Ima.expectedShortfallDaily(
                        new double[] {1.0, Double.POSITIVE_INFINITY}, 0.975))
                .getMessage().contains("finite"));
    }

    // ---- liquidity-horizon ladder -----------------------------------------

    @Test
    public void singleCategorySqrtScaling() {
        // one category with LH = 40: ladder collapses to sqrt(40/10) = 2x base
        double[] pnl = series(100, i -> Math.sin(3.0 * i) * 100.0);
        double esB = Ima.esBase10d(pnl, 0.975);
        double esL = Ima.esLhScaled(pnl, Map.of("fx", pnl), Map.of("fx", 40), LADDER, 0.975);
        assertEquals(2.0 * esB, esL, 1e-9);
    }

    @Test
    public void lh10CategoryEqualsBase() {
        double[] pnl = series(80, i -> Math.cos(2.0 * i) * 50.0);
        double esL = Ima.esLhScaled(pnl, Map.of("x", pnl), Map.of("x", 10), LADDER, 0.975);
        assertEquals(Ima.esBase10d(pnl, 0.975), esL, 1e-9);
    }

    @Test
    public void ladderMonotone() {
        // property-style loop: LH scaling never decreases the ES, and it is
        // non-decreasing in the category liquidity horizon
        double[] pnl = series(120, i -> Math.sin(1.7 * i) * 80.0 + Math.cos(0.3 * i) * 40.0);
        double base = Ima.esBase10d(pnl, 0.975);
        double prev = 0.0;
        for (int lh : new int[] {10, 20, 40, 60, 120}) {
            double esL = Ima.esLhScaled(pnl, Map.of("c", pnl), Map.of("c", lh), LADDER, 0.975);
            assertTrue("ES_LH >= base at lh " + lh, esL >= base - 1e-12);
            assertTrue("ES_LH non-decreasing at lh " + lh, esL >= prev - 1e-12);
            prev = esL;
        }
    }

    @Test
    public void twoCategoriesHand() {
        // cat a (LH 10) + cat b (LH 20): ES_LH^2 = ES(full)^2 + ES(b)^2*(20-10)/10
        double[] a = series(100, i -> Math.sin(2.1 * i) * 30.0);
        double[] b = series(100, i -> Math.cos(1.3 * i) * 60.0);
        double[] full = new double[100];
        for (int i = 0; i < 100; i++) {
            full[i] = a[i] + b[i];
        }
        Map<String, double[]> cats = new LinkedHashMap<>();
        cats.put("a", a);
        cats.put("b", b);
        double got = Ima.esLhScaled(full, cats, Map.of("a", 10, "b", 20), LADDER, 0.975);
        double want = Math.sqrt(Math.pow(Ima.esBase10d(full, 0.975), 2)
                + Math.pow(Ima.esBase10d(b, 0.975), 2));
        assertEquals(want, got, 1e-9);
    }

    @Test
    public void categorySumMismatchThrows() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Ima.esLhScaled(new double[] {1.0, -2.0, 3.0},
                        Map.of("a", new double[] {1.0, 1.0, 1.0}), Map.of("a", 20),
                        LADDER, 0.975));
        assertTrue(e.getMessage().contains("sum"));
    }

    @Test
    public void missingCategoryLhThrows() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Ima.esLhScaled(new double[] {1.0, -1.0},
                        Map.of("a", new double[] {1.0, -1.0}), Map.of(), LADDER, 0.975));
        assertTrue(e.getMessage().contains("liquidity horizon"));
    }

    @Test
    public void badLadderThrows() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Ima.esLhScaled(new double[] {1.0, -1.0},
                        Map.of("a", new double[] {1.0, -1.0}), Map.of("a", 20),
                        new int[] {20, 40}, 0.975));
        assertTrue(e.getMessage().contains("10d base"));
    }

    // ---- IMCC --------------------------------------------------------------

    @Test
    public void imccRhoBlendSingleCategory() {
        // single category: full ES == partial ES -> IMCC == ES_LH exactly
        SbmParams params = TestData.params();
        double[] pnl = series(90, i -> Math.sin(0.9 * i) * 100.0);
        double want = Ima.esLhScaled(pnl, Map.of("ir", pnl), params.categoryLh(),
                params.lhLadder(), params.imaAlpha());
        assertEquals(want, Ima.imcc(pnl, Map.of("ir", pnl), params), 1e-9);
    }

    @Test
    public void imccBetweenFullAndSum() {
        SbmParams params = TestData.params();
        double[] a = series(100, i -> Math.sin(2.1 * i) * 30.0);
        double[] b = series(100, i -> Math.cos(1.3 * i) * 60.0);
        double[] full = new double[100];
        for (int i = 0; i < 100; i++) {
            full[i] = a[i] + b[i];
        }
        Map<String, double[]> cats = new LinkedHashMap<>();
        cats.put("eq", a);
        cats.put("fx", b);
        double v = Ima.imcc(full, cats, params);
        double esFull = Ima.esLhScaled(full, cats, params.categoryLh(), params.lhLadder(),
                0.975);
        double esSum = 0.0;
        for (Map.Entry<String, double[]> e : cats.entrySet()) {
            esSum += Ima.esLhScaled(e.getValue(), Map.of(e.getKey(), e.getValue()),
                    params.categoryLh(), params.lhLadder(), 0.975);
        }
        assertTrue(esFull <= v + 1e-9);
        assertTrue(v <= esSum + 1e-9);
        assertEquals(0.5 * esFull + 0.5 * esSum, v, 1e-9);
    }

    // ---- backtesting -------------------------------------------------------

    @Test
    public void zoneEdges() {
        assertEquals("green", Ima.backtestZone(0));
        assertEquals("green", Ima.backtestZone(4));
        assertEquals("amber", Ima.backtestZone(5));
        assertEquals("amber", Ima.backtestZone(9));
        assertEquals("red", Ima.backtestZone(10));
        assertEquals("red", Ima.backtestZone(13));
    }

    @Test
    public void multiplierTableEdges() {
        SbmParams params = TestData.params();
        assertEquals(1.5, Ima.backtestMultiplier(4, params), 0.0);
        assertEquals(1.70, Ima.backtestMultiplier(5, params), 0.0);
        assertEquals(1.92, Ima.backtestMultiplier(9, params), 0.0);
        assertEquals(2.0, Ima.backtestMultiplier(10, params), 0.0);
        assertEquals(2.0, Ima.backtestMultiplier(13, params), 0.0); // > 12: red cap
    }

    @Test
    public void exceptionCounting() {
        SbmParams params = TestData.params();
        double[] pnl = {-5.0, -15.0, 3.0, -10.0, -10.1};
        double[] var = {10.0, 10.0, 10.0, 10.0, 10.0};
        Ima.BacktestResult res = Ima.backtest(pnl, var, params);
        assertEquals(2, res.exceptions()); // strictly below -VaR only (-15, -10.1)
        assertEquals("green", res.zone());
        assertEquals(1.5, res.multiplier(), 0.0);
    }

    @Test
    public void backtestErrors() {
        SbmParams params = TestData.params();
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Ima.backtest(new double[] {1.0}, new double[] {1.0, 2.0}, params))
                .getMessage().contains("mismatch"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Ima.backtest(new double[] {1.0}, new double[] {-1.0}, params))
                .getMessage().contains("non-negative"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Ima.backtestZone(-1)).getMessage().contains("negative"));
    }

    // ---- SES and IMA capital ----------------------------------------------

    @Test
    public void sesZeroDiversificationSum() {
        List<Ima.NmrfEntry> entries = List.of(
                new Ima.NmrfEntry("a", "d", 100.0), new Ima.NmrfEntry("b", "d", 250.0));
        assertEquals(350.0, Ima.ses(entries), 0.0);
        assertEquals(0.0, Ima.ses(List.of()), 0.0);
    }

    @Test
    public void sesNegativeThrows() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Ima.ses(List.of(new Ima.NmrfEntry("a", "d", -1.0))));
        assertTrue(e.getMessage().contains("stressed_loss"));
    }

    @Test
    public void imaCapitalFormula() {
        assertEquals(205.0, Ima.imaCapital(100.0, 1.7, 30.0, 5.0), 1e-12);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Ima.imaCapital(100.0, -1.0, 0.0, 0.0))
                .getMessage().contains("multiplier"));
    }
}
