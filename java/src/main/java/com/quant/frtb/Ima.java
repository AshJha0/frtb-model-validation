package com.quant.frtb;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Internal Models Approach sketch: ES 97.5% with liquidity-horizon scaling,
 * IMCC, backtesting zones/multipliers, NMRF stress capital (SES).
 *
 * <p>Pinned conventions (see API_SPEC.md):
 * <ul>
 *   <li>ES 97.5 (daily): losses L = -PnL sorted descending;
 *       {@code k = max(1, ceil((1-alpha)*n - 1e-9))} (the epsilon guards
 *       binary-float ceil artefacts); ES = mean of the k worst losses.
 *       Base 10d ES = sqrt(10) * daily ES.</li>
 *   <li>LH ladder (10, 20, 40, 60, 120):
 *       {@code ES_LH = sqrt(ES1(P)^2 + sum_j (ES1(P_j)*sqrt((LH_j-LH_{j-1})/10))^2)}
 *       where P_j sums the categories with horizon &ge; LH_j. Monotone:
 *       ES_LH &ge; base ES.</li>
 *   <li>IMCC = rho*ES_LH(full) + (1-rho)*sum_c ES_LH(category), rho = 0.5.</li>
 *   <li>Backtesting (99% VaR, 260d): exception when PnL_t &lt; -VaR_t; zones
 *       green 0-4, amber 5-9, red &ge; 10; pinned multiplier table.</li>
 *   <li>SES = sum of pinned NMRF stressed losses, zero diversification.</li>
 * </ul>
 */
public final class Ima {

    private Ima() {
    }

    /** One pinned non-modellable risk factor entry from {@code nmrf.json}. */
    public record NmrfEntry(String factor, String desk, double stressedLoss) {
    }

    /** Daily ES at level alpha: mean of the k = ceil((1-alpha)*n) worst losses. */
    public static double expectedShortfallDaily(double[] pnl, double alpha) {
        int n = pnl.length;
        if (n == 0) {
            throw new IllegalArgumentException("expectedShortfallDaily: empty P&L series");
        }
        if (!(0.0 < alpha && alpha < 1.0)) {
            throw new IllegalArgumentException(
                    "expectedShortfallDaily: alpha must be in (0,1), got " + alpha);
        }
        for (double v : pnl) {
            if (!Double.isFinite(v)) {
                throw new IllegalArgumentException(
                        "expectedShortfallDaily: P&L contains non-finite values");
            }
        }
        // tiny epsilon guards against binary-float artefacts like 0.025*40 -> 1.0000...09
        int k = Math.max(1, (int) Math.ceil((1.0 - alpha) * n - 1e-9));
        double[] losses = new double[n];
        for (int i = 0; i < n; i++) {
            losses[i] = -pnl[i];
        }
        Arrays.sort(losses); // ascending; the k worst losses sit at the top
        double sum = 0.0;
        for (int i = n - 1; i >= n - k; i--) {
            sum += losses[i]; // largest first, matching the reference sum order
        }
        return sum / k;
    }

    /** Base 10-day ES: sqrt(10) * daily ES (pinned square-root-of-time scaling). */
    public static double esBase10d(double[] pnl, double alpha) {
        return Math.sqrt(10.0) * expectedShortfallDaily(pnl, alpha);
    }

    /**
     * Liquidity-horizon-scaled ES (Basel ladder formula, see class docs).
     *
     * <p>The category series must sum to the full P&L (validated to 1e-6) and
     * every category must have a pinned liquidity horizon.
     */
    public static double esLhScaled(double[] fullPnl, Map<String, double[]> categoryPnl,
                                    Map<String, Integer> categoryLh, int[] lhLadder,
                                    double alpha) {
        if (lhLadder.length < 1 || !strictlyIncreasing(lhLadder)) {
            throw new IllegalArgumentException("esLhScaled: lhLadder must be strictly increasing");
        }
        if (lhLadder[0] != 10) {
            throw new IllegalArgumentException(
                    "esLhScaled: lhLadder must start at the 10d base horizon");
        }
        int n = fullPnl.length;
        for (Map.Entry<String, double[]> e : categoryPnl.entrySet()) {
            if (!categoryLh.containsKey(e.getKey())) {
                throw new IllegalArgumentException(
                        "esLhScaled: no pinned liquidity horizon for category '"
                                + e.getKey() + "'");
            }
            if (e.getValue().length != n) {
                throw new IllegalArgumentException(
                        "esLhScaled: category '" + e.getKey() + "' length mismatch");
            }
        }
        for (int i = 0; i < n; i++) {
            double s = 0.0;
            for (double[] series : categoryPnl.values()) {
                s += series[i];
            }
            if (Math.abs(s - fullPnl[i]) > 1e-6) {
                throw new IllegalArgumentException(
                        "esLhScaled: category P&L does not sum to the full P&L on day " + i
                                + " (" + s + " vs " + fullPnl[i] + ")");
            }
        }

        double base = esBase10d(fullPnl, alpha);
        double totalSq = base * base;
        for (int j = 1; j < lhLadder.length; j++) {
            int lhJ = lhLadder[j];
            int lhPrev = lhLadder[j - 1];
            double[] subset = new double[n];
            boolean anyCat = false;
            boolean allZero = true;
            for (Map.Entry<String, double[]> e : categoryPnl.entrySet()) {
                if (categoryLh.get(e.getKey()) >= lhJ) {
                    anyCat = true;
                    double[] series = e.getValue();
                    for (int i = 0; i < n; i++) {
                        subset[i] += series[i];
                    }
                }
            }
            if (!anyCat) {
                continue;
            }
            for (double v : subset) {
                if (v != 0.0) {
                    allZero = false;
                    break;
                }
            }
            if (allZero) {
                continue;
            }
            double term = esBase10d(subset, alpha) * Math.sqrt((lhJ - lhPrev) / 10.0);
            totalSq += term * term;
        }
        return Math.sqrt(totalSq);
    }

    private static boolean strictlyIncreasing(int[] xs) {
        for (int i = 1; i < xs.length; i++) {
            if (xs[i] <= xs[i - 1]) {
                return false;
            }
        }
        return true;
    }

    /** IMCC = rho*ES_LH(full) + (1-rho)*sum over categories of ES_LH(category). */
    public static double imcc(double[] fullPnl, Map<String, double[]> categoryPnl,
                              SbmParams params) {
        double rho = params.imaRho();
        double esFull = esLhScaled(fullPnl, categoryPnl, params.categoryLh(),
                params.lhLadder(), params.imaAlpha());
        double esPartials = 0.0;
        for (Map.Entry<String, double[]> e : categoryPnl.entrySet()) {
            esPartials += esLhScaled(e.getValue(), Map.of(e.getKey(), e.getValue()),
                    params.categoryLh(), params.lhLadder(), params.imaAlpha());
        }
        return rho * esFull + (1.0 - rho) * esPartials;
    }

    // ---------------------------------------------------------------------
    // Backtesting
    // ---------------------------------------------------------------------

    /**
     * VaR backtest outcome.
     *
     * @param exceptions number of days with PnL below -VaR (strict)
     * @param zone       "green" | "amber" | "red"
     * @param multiplier pinned capital multiplier for the zone
     */
    public record BacktestResult(int exceptions, String zone, double multiplier) {
    }

    /** Count 99% VaR exceptions (PnL_t &lt; -VaR_t) and map to zone/multiplier. */
    public static BacktestResult backtest(double[] pnl, double[] var99, SbmParams params) {
        if (pnl.length != var99.length) {
            throw new IllegalArgumentException("backtest: P&L and VaR length mismatch ("
                    + pnl.length + " vs " + var99.length + ")");
        }
        if (pnl.length == 0) {
            throw new IllegalArgumentException("backtest: empty series");
        }
        for (double v : var99) {
            if (!Double.isFinite(v) || v < 0.0) {
                throw new IllegalArgumentException(
                        "backtest: VaR values must be non-negative and finite");
            }
        }
        int exceptions = 0;
        for (int i = 0; i < pnl.length; i++) {
            if (pnl[i] < -var99[i]) {
                exceptions++;
            }
        }
        return new BacktestResult(exceptions, backtestZone(exceptions),
                backtestMultiplier(exceptions, params));
    }

    /** Basel traffic-light zone: green 0-4, amber 5-9, red &ge; 10. */
    public static String backtestZone(int exceptions) {
        if (exceptions < 0) {
            throw new IllegalArgumentException("backtestZone: exception count cannot be negative");
        }
        if (exceptions <= 4) {
            return "green";
        }
        if (exceptions <= 9) {
            return "amber";
        }
        return "red";
    }

    /** Pinned multiplier: 1.5 green; amber table 5..9; 2.0 red (cap, also &gt; 12). */
    public static double backtestMultiplier(int exceptions, SbmParams params) {
        String zone = backtestZone(exceptions);
        if ("green".equals(zone)) {
            return params.backtestBaseMultiplier();
        }
        if ("amber".equals(zone)) {
            Double m = params.backtestAmberMultipliers().get(exceptions);
            if (m == null) {
                throw new IllegalArgumentException(
                        "backtestMultiplier: no amber multiplier pinned for " + exceptions);
            }
            return m;
        }
        return params.backtestRedMultiplier();
    }

    // ---------------------------------------------------------------------
    // NMRF / SES
    // ---------------------------------------------------------------------

    /**
     * Stress scenario capital: sum of stressed losses, zero diversification.
     * Negative or non-finite stressed losses raise.
     */
    public static double ses(List<NmrfEntry> entries) {
        double total = 0.0;
        for (NmrfEntry e : entries) {
            double loss = e.stressedLoss();
            if (!Double.isFinite(loss) || loss < 0.0) {
                throw new IllegalArgumentException(
                        "ses: stressed_loss must be >= 0 and finite (factor '"
                                + e.factor() + "')");
            }
            total += loss;
        }
        return total;
    }

    /**
     * IMA capital = multiplier * IMCC + SES + PLAT surcharge.
     *
     * <p>Simplification (documented): avg60(IMCC) = IMCC for the static
     * bundled portfolio, so max(IMCC, m*avg60(IMCC)) = m*IMCC since m &ge; 1.5.
     */
    public static double imaCapital(double imccValue, double multiplier, double sesValue,
                                    double platSurcharge) {
        checkNonNegative("imcc", imccValue);
        checkNonNegative("multiplier", multiplier);
        checkNonNegative("ses", sesValue);
        checkNonNegative("platSurcharge", platSurcharge);
        return multiplier * imccValue + sesValue + platSurcharge;
    }

    private static void checkNonNegative(String name, double v) {
        if (!Double.isFinite(v) || v < 0.0) {
            throw new IllegalArgumentException(
                    "imaCapital: " + name + " must be >= 0 and finite, got " + v);
        }
    }
}
