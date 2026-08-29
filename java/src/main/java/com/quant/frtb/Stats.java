package com.quant.frtb;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Native statistics used by PLAT: Spearman rank correlation and the exact
 * two-sample Kolmogorov-Smirnov statistic. Implemented from first principles
 * (no external dependencies), mirroring the Python reference exactly.
 */
public final class Stats {

    private Stats() {
    }

    private static void validatePair(double[] x, double[] y, int minN) {
        if (x.length != y.length) {
            throw new IllegalArgumentException(
                    "series must have equal length (" + x.length + " vs " + y.length + ")");
        }
        if (x.length < minN) {
            throw new IllegalArgumentException(
                    "series must have at least " + minN + " observations, got " + x.length);
        }
        for (double v : x) {
            if (!Double.isFinite(v)) {
                throw new IllegalArgumentException("series must contain only finite values");
            }
        }
        for (double v : y) {
            if (!Double.isFinite(v)) {
                throw new IllegalArgumentException("series must contain only finite values");
            }
        }
    }

    /** Ranks 1..n with ties assigned the average rank of the tied block. */
    public static double[] averageRanks(double[] x) {
        int n = x.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparingDouble(i -> x[i]));
        double[] ranks = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && x[order[j + 1]] == x[order[i]]) {
                j++;
            }
            double avg = (i + j) / 2.0 + 1.0; // average of ranks i+1 .. j+1
            for (int k = i; k <= j; k++) {
                ranks[order[k]] = avg;
            }
            i = j + 1;
        }
        return ranks;
    }

    /** Pearson correlation; raises when either series is constant. */
    public static double pearson(double[] x, double[] y) {
        validatePair(x, y, 2);
        int n = x.length;
        double mx = 0.0;
        for (double a : x) {
            mx += a;
        }
        mx /= n;
        double my = 0.0;
        for (double b : y) {
            my += b;
        }
        my /= n;
        double sxx = 0.0;
        for (double a : x) {
            sxx += (a - mx) * (a - mx);
        }
        double syy = 0.0;
        for (double b : y) {
            syy += (b - my) * (b - my);
        }
        if (sxx == 0.0 || syy == 0.0) {
            throw new IllegalArgumentException(
                    "pearson: correlation undefined for a constant series");
        }
        double sxy = 0.0;
        for (int i = 0; i < n; i++) {
            sxy += (x[i] - mx) * (y[i] - my);
        }
        return sxy / Math.sqrt(sxx * syy);
    }

    /**
     * Spearman rank correlation: Pearson correlation of average ranks.
     * Raises when either series is constant (correlation undefined — PLAT
     * maps that case to the Red zone, see {@link Plat}).
     */
    public static double spearman(double[] x, double[] y) {
        validatePair(x, y, 3);
        return pearson(averageRanks(x), averageRanks(y));
    }

    /**
     * Two-sample Kolmogorov-Smirnov statistic {@code sup_t |F_x(t) - F_y(t)|},
     * computed exactly over the pooled sample with a two-pointer sweep
     * (handles ties identically to scipy.stats.ks_2samp).
     */
    public static double ksStatistic(double[] x, double[] y) {
        if (x.length == 0 || y.length == 0) {
            throw new IllegalArgumentException("ksStatistic: series must be non-empty");
        }
        for (double v : x) {
            if (!Double.isFinite(v)) {
                throw new IllegalArgumentException("ksStatistic: series must contain only finite values");
            }
        }
        for (double v : y) {
            if (!Double.isFinite(v)) {
                throw new IllegalArgumentException("ksStatistic: series must contain only finite values");
            }
        }
        double[] xs = x.clone();
        double[] ys = y.clone();
        Arrays.sort(xs);
        Arrays.sort(ys);
        int n = xs.length;
        int m = ys.length;
        int i = 0;
        int j = 0;
        double d = 0.0;
        while (i < n && j < m) {
            double v = xs[i] <= ys[j] ? xs[i] : ys[j];
            while (i < n && xs[i] <= v) {
                i++;
            }
            while (j < m && ys[j] <= v) {
                j++;
            }
            d = Math.max(d, Math.abs((double) i / n - (double) j / m));
        }
        // after one sample is exhausted the ECDF gap can only shrink toward |1-1|=0
        d = Math.max(d, i == n
                ? Math.abs(1.0 - (double) j / m)
                : Math.abs((double) i / n - 1.0));
        return d;
    }
}
