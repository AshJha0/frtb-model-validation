package com.quant.frtb;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * PLAT zones/surcharge and the native Spearman/KS implementations (the
 * Python suite cross-checks them against scipy; here they are pinned by the
 * golden PLAT cases on the bundled data plus hand-checkable examples).
 */
public class StatsPlatTest {

    /** Deterministic pseudo-noisy series (no RNG in the test suite). */
    private static double[] series(int n, double f1, double f2) {
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = Math.sin(f1 * i) + 0.5 * Math.cos(f2 * i * i);
        }
        return out;
    }

    // ---- native stats ------------------------------------------------------

    @Test
    public void averageRanksTies() {
        assertArrayEquals(new double[] {1.0, 2.5, 2.5, 4.0},
                Stats.averageRanks(new double[] {10.0, 20.0, 20.0, 30.0}), 0.0);
    }

    @Test
    public void perfectMonotone() {
        double[] x = {1.0, 2.0, 3.0, 4.0};
        assertEquals(1.0, Stats.spearman(x, new double[] {10.0, 20.0, 30.0, 40.0}), 1e-15);
        assertEquals(-1.0, Stats.spearman(x, new double[] {4.0, 3.0, 2.0, 1.0}), 1e-15);
    }

    @Test
    public void spearmanWithTiesHandChecked() {
        // Hand-computed with average ranks (matches scipy.stats.spearmanr):
        // x ranks: [2, 3.5, 3.5, 6, 6, 6, 8, 1], y ranks: [2.5, 2.5, 4, 5, 6.5, 6.5, 8, 1]
        double[] x = {1.0, 2.0, 2.0, 3.0, 3.0, 3.0, 4.0, 0.0};
        double[] y = {5.0, 5.0, 6.0, 7.0, 8.0, 8.0, 9.0, 4.0};
        double got = Stats.spearman(x, y);
        double want = Stats.pearson(
                new double[] {2.0, 3.5, 3.5, 6.0, 6.0, 6.0, 8.0, 1.0},
                new double[] {2.5, 2.5, 4.0, 5.0, 6.5, 6.5, 8.0, 1.0});
        assertEquals(want, got, 1e-15);
        assertTrue(got > 0.9); // strongly monotone data
    }

    @Test
    public void spearmanInvariantUnderMonotoneTransform() {
        // property loop: rank correlation ignores monotone rescaling
        for (int n : new int[] {25, 60, 120}) {
            double[] x = series(n, 1.1, 0.3);
            double[] y = new double[n];
            double[] y2 = new double[n];
            for (int i = 0; i < n; i++) {
                y[i] = x[i] + 0.1 * Math.sin(0.9 * i);
                y2[i] = 3.0 * y[i] + 7.0; // strictly increasing transform
            }
            assertEquals(Stats.spearman(x, y), Stats.spearman(x, y2), 1e-12);
        }
    }

    @Test
    public void ksHandCases() {
        // x = {1,1,2,3}, y = {1,2,2,4}: pooled sweep gives sup diff 0.25
        assertEquals(0.25, Stats.ksStatistic(
                new double[] {1.0, 1.0, 2.0, 3.0}, new double[] {1.0, 2.0, 2.0, 4.0}), 1e-15);
        // fully separated samples: KS = 1
        assertEquals(1.0, Stats.ksStatistic(
                new double[] {0.0, 1.0, 2.0}, new double[] {10.0, 11.0}), 0.0);
        // constant shift of half the mass
        assertEquals(0.5, Stats.ksStatistic(
                new double[] {1.0, 2.0, 3.0, 4.0}, new double[] {3.0, 4.0, 5.0, 6.0}), 1e-15);
    }

    @Test
    public void ksIdenticalZero() {
        double[] x = series(40, 1.0, 0.5);
        assertEquals(0.0, Stats.ksStatistic(x, x.clone()), 0.0);
    }

    @Test
    public void ksUnequalLengths() {
        // n=2, m=3: F_x steps 0.5, F_y steps 1/3; max gap at t in [2,3): |1 - 1/3|...
        // pooled sweep: x={1,2}, y={3,4,5} -> after 2: |1 - 0| = 1
        assertEquals(1.0, Stats.ksStatistic(
                new double[] {1.0, 2.0}, new double[] {3.0, 4.0, 5.0}), 0.0);
        // ties across the two samples
        assertEquals(0.5, Stats.ksStatistic(
                new double[] {1.0, 3.0}, new double[] {2.0, 3.0}), 1e-15);
    }

    @Test
    public void statsErrors() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Stats.spearman(new double[] {1.0, 2.0, 3.0}, new double[] {1.0, 2.0}))
                .getMessage().contains("equal length"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Stats.pearson(new double[] {1.0, 1.0, 1.0}, new double[] {1.0, 2.0, 3.0}))
                .getMessage().contains("constant"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Stats.ksStatistic(new double[0], new double[] {1.0}))
                .getMessage().contains("non-empty"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Stats.ksStatistic(new double[] {Double.NaN}, new double[] {1.0}))
                .getMessage().contains("finite"));
    }

    // ---- PLAT zones --------------------------------------------------------

    @Test
    public void thresholdBoundaries() {
        SbmParams params = TestData.params();
        assertEquals("green", Plat.platZoneFromMetrics(0.85, 0.09, params)); // on both edges
        assertEquals("amber", Plat.platZoneFromMetrics(0.8499999, 0.09, params));
        assertEquals("amber", Plat.platZoneFromMetrics(0.85, 0.0900001, params));
        assertEquals("amber", Plat.platZoneFromMetrics(0.80, 0.12, params)); // on amber edges
        assertEquals("red", Plat.platZoneFromMetrics(0.7999999, 0.05, params));
        assertEquals("red", Plat.platZoneFromMetrics(0.99, 0.1200001, params));
        assertEquals("amber", Plat.platZoneFromMetrics(0.82, 0.05, params));
    }

    @Test
    public void constantPnlIsRed() {
        SbmParams params = TestData.params();
        double[] noisy = series(20, 1.1, 0.3);
        double[] constant = new double[20];
        java.util.Arrays.fill(constant, 5.0);
        Plat.PlatResult res = Plat.platTest(constant, noisy, params);
        assertEquals("red", res.zone());
        assertNull(res.spearman());
        assertNull(res.ks());
        // constant on the RTPL side too
        assertEquals("red", Plat.platTest(noisy, new double[20], params).zone());
    }

    @Test
    public void identicalSeriesGreen() {
        SbmParams params = TestData.params();
        double[] x = series(50, 1.3, 0.7);
        Plat.PlatResult res = Plat.platTest(x, x.clone(), params);
        assertEquals("green", res.zone());
        assertEquals(1.0, res.spearman(), 1e-12);
        assertEquals(0.0, res.ks(), 0.0);
    }

    @Test
    public void platLengthErrors() {
        SbmParams params = TestData.params();
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Plat.platTest(new double[] {1.0, 2.0}, new double[] {1.0}, params))
                .getMessage().contains("mismatch"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Plat.platTest(new double[] {1.0, 2.0}, new double[] {1.0, 2.0}, params))
                .getMessage().contains("at least 3"));
    }

    // ---- PLAT surcharge ----------------------------------------------------

    @Test
    public void amberInterpolation() {
        SbmParams params = TestData.params();
        // k = 0.5 pinned: surcharge = 0.5 * max(0, SA - IMA)
        assertEquals(20.0, Plat.platSurcharge("amber", 100.0, 60.0, params), 1e-12);
        assertEquals(0.0, Plat.platSurcharge("amber", 50.0, 60.0, params), 0.0); // SA < IMA
    }

    @Test
    public void greenRedNoSurcharge() {
        SbmParams params = TestData.params();
        assertEquals(0.0, Plat.platSurcharge("green", 100.0, 60.0, params), 0.0);
        assertEquals(0.0, Plat.platSurcharge("red", 100.0, 60.0, params), 0.0);
    }

    @Test
    public void badZoneThrows() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Plat.platSurcharge("blue", 1.0, 1.0, TestData.params()));
        assertTrue(e.getMessage().contains("zone"));
    }
}
