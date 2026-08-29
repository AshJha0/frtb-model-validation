package com.quant.frtb;

/**
 * P&amp;L attribution test (PLAT): Spearman + KS metrics, traffic-light zone,
 * Amber capital surcharge.
 *
 * <p>Pinned thresholds: Green iff {@code spearman >= 0.85 AND KS <= 0.09};
 * Red iff {@code spearman < 0.80 OR KS > 0.12}; Amber otherwise. Constant
 * P&amp;L on either side leaves the rank correlation undefined; the desk is
 * assigned RED with null metrics (documented conservative convention).
 *
 * <p>Amber surcharge (pinned k = 0.5 interpolation between IMA and SA):
 * {@code surcharge = k * max(0, SA_desk - IMA_desk_core)}.
 */
public final class Plat {

    private Plat() {
    }

    /**
     * PLAT outcome; metrics are {@code null} when undefined (constant series,
     * which maps to Red).
     *
     * @param spearman Spearman rank correlation, or null when undefined
     * @param ks       two-sample KS statistic, or null when undefined
     * @param zone     "green" | "amber" | "red"
     */
    public record PlatResult(Double spearman, Double ks, String zone) {
    }

    /** Map (Spearman, KS) to a PLAT zone using the pinned thresholds. */
    public static String platZoneFromMetrics(double spearmanRho, double ksStat,
                                             SbmParams params) {
        if (!(Double.isFinite(spearmanRho) && Double.isFinite(ksStat))) {
            throw new IllegalArgumentException("platZoneFromMetrics: metrics must be finite");
        }
        if (spearmanRho < params.platSpearmanAmber() || ksStat > params.platKsAmber()) {
            return "red";
        }
        if (spearmanRho >= params.platSpearmanGreen() && ksStat <= params.platKsGreen()) {
            return "green";
        }
        return "amber";
    }

    /**
     * Run the PLAT on hypothetical vs risk-theoretical P&amp;L.
     *
     * <p>A constant series on either side makes the rank correlation
     * undefined: the result is Red with null metrics (documented edge case).
     */
    public static PlatResult platTest(double[] hypo, double[] rtpl, SbmParams params) {
        if (hypo.length != rtpl.length) {
            throw new IllegalArgumentException("platTest: series length mismatch ("
                    + hypo.length + " vs " + rtpl.length + ")");
        }
        if (hypo.length < 3) {
            throw new IllegalArgumentException("platTest: need at least 3 observations");
        }
        double rho;
        try {
            rho = Stats.spearman(hypo, rtpl);
        } catch (IllegalArgumentException e) {
            // constant series -> correlation undefined -> Red (conservative)
            return new PlatResult(null, null, "red");
        }
        double ks = Stats.ksStatistic(hypo, rtpl);
        return new PlatResult(rho, ks, platZoneFromMetrics(rho, ks, params));
    }

    /**
     * Amber-zone capital surcharge: {@code k * max(0, SA - IMA_core)}; 0 for
     * green/red (red-zone desks fall back to SA entirely — a reporting
     * matter handled by the caller, not a surcharge).
     */
    public static double platSurcharge(String zone, double saCapital, double imaCapitalCore,
                                       SbmParams params) {
        if (!"green".equals(zone) && !"amber".equals(zone) && !"red".equals(zone)) {
            throw new IllegalArgumentException("platSurcharge: unknown zone '" + zone + "'");
        }
        checkNonNegative("saCapital", saCapital);
        checkNonNegative("imaCapitalCore", imaCapitalCore);
        if (!"amber".equals(zone)) {
            return 0.0;
        }
        return params.platKSurcharge() * Math.max(0.0, saCapital - imaCapitalCore);
    }

    private static void checkNonNegative(String name, double v) {
        if (!Double.isFinite(v) || v < 0.0) {
            throw new IllegalArgumentException(
                    "platSurcharge: " + name + " must be >= 0 and finite");
        }
    }
}
