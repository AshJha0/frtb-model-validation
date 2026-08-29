package com.quant.frtb;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sensitivities-Based Method (SBM) aggregation.
 *
 * <p>Formulas (FRTB structure, educational parameter set):
 * <pre>
 * Weighted sensitivity   WS_k = RW_k * s_k
 * Within-bucket          K_b  = sqrt(max(0, sum WS^2 + sum_{k!=l} rho_kl WS_k WS_l))
 * Across buckets         Charge = sqrt(max(0, sum K_b^2 + sum_{b!=c} gamma_bc S_b S_c))
 * </pre>
 * with {@code S_b = sum_k WS_k}; if the argument of the outer sqrt is
 * negative the S_b FALLBACK applies: {@code S_b = max(min(sum WS_k, K_b), -K_b)}
 * and the aggregate is recomputed once (the max(0,.) guard is kept).
 *
 * <p>Correlation scenarios (applied to every rho and gamma): high
 * {@code min(1.25*rho, 1)}, medium {@code rho}, low {@code 0.75*rho} (pinned
 * simplification of Basel's {@code max(2*rho-1, 0.75*rho)}). The SBM capital
 * is the max over the three scenario totals.
 *
 * <p>Curvature uses CVR+/CVR- per factor, the psi both-negative rule at both
 * aggregation levels, squared (scenario-scaled) delta correlations, no S_b
 * fallback, and up-side selection on K_b+ = K_b- ties (pinned
 * simplifications, documented in API_SPEC.md).
 */
public final class Sbm {

    private Sbm() {
    }

    /** The three correlation scenarios, in reporting order. */
    public static final List<String> SCENARIOS = List.of("high", "medium", "low");

    /** Pairwise correlation lookup by factor index (k != l). */
    @FunctionalInterface
    public interface PairCorr {
        /** Correlation between factors k and l of a bucket. */
        double get(int k, int l);
    }

    /** Medium-scenario intra-bucket correlation lookup by bucket and factor keys. */
    @FunctionalInterface
    public interface IntraRho {
        /** Correlation between factor keys k and l inside a bucket. */
        double get(String bucket, String k, String l);
    }

    /** Medium-scenario cross-bucket gamma lookup. */
    @FunctionalInterface
    public interface CrossGamma {
        /** Gamma between buckets b and c. */
        double get(String b, String c);
    }

    /** Apply the correlation scenario scaler; "high" is capped at 1.0. */
    public static double scaleRho(double rho, String scenario, double high, double low) {
        switch (scenario) {
            case "medium":
                return rho;
            case "high":
                return Math.min(high * rho, 1.0);
            case "low":
                return low * rho;
            default:
                throw new IllegalArgumentException("scaleRho: unknown scenario '" + scenario + "'");
        }
    }

    /** {@code scaleRho} with the pinned default scalers 1.25 / 0.75. */
    public static double scaleRho(double rho, String scenario) {
        return scaleRho(rho, scenario, 1.25, 0.75);
    }

    /**
     * Within-bucket charge
     * {@code K_b = sqrt(max(0, sum WS^2 + sum_{k!=l} rho WS_k WS_l))}.
     *
     * <p>The max(0,.) guard protects against negative rounding of the
     * quadratic form.
     */
    public static double bucketKb(List<Double> ws, PairCorr rho) {
        for (double w : ws) {
            if (!Double.isFinite(w)) {
                throw new IllegalArgumentException(
                        "bucketKb: weighted sensitivities must be finite");
            }
        }
        double total = 0.0;
        for (double w : ws) {
            total += w * w;
        }
        int n = ws.size();
        for (int k = 0; k < n; k++) {
            for (int l = 0; l < n; l++) {
                if (k != l) {
                    total += rho.get(k, l) * ws.get(k) * ws.get(l);
                }
            }
        }
        return Math.sqrt(Math.max(0.0, total));
    }

    /**
     * Across-bucket aggregation output (with the S_b fallback bookkeeping).
     *
     * @param charge       the aggregated charge
     * @param usedFallback true when the S_b fallback branch was taken
     * @param sb           the S_b actually used (post-fallback if triggered)
     */
    public record AggregateResult(double charge, boolean usedFallback, Map<String, Double> sb) {
    }

    /**
     * Across-bucket aggregation with the FRTB S_b fallback rule.
     *
     * <p>First tries {@code S_b = sum_k WS_k}. If
     * {@code sum K_b^2 + sum_{b!=c} gamma S_b S_c < 0}, recomputes once with
     * {@code S_b = max(min(sum_k WS_k, K_b), -K_b)}.
     */
    public static AggregateResult aggregateBuckets(Map<String, Double> kb,
                                                   Map<String, Double> wsSum,
                                                   CrossGamma gamma) {
        if (!kb.keySet().equals(wsSum.keySet())) {
            throw new IllegalArgumentException(
                    "aggregateBuckets: kb and wsSum must cover the same buckets");
        }
        List<String> buckets = new ArrayList<>(kb.keySet());
        Collections.sort(buckets);

        Map<String, Double> sb0 = new LinkedHashMap<>();
        for (String b : buckets) {
            sb0.put(b, wsSum.get(b));
        }
        double inner = aggregateInner(buckets, kb, sb0, gamma);
        if (inner >= 0.0) {
            return new AggregateResult(Math.sqrt(inner), false, sb0);
        }
        Map<String, Double> sb1 = new LinkedHashMap<>();
        for (String b : buckets) {
            sb1.put(b, Math.max(Math.min(wsSum.get(b), kb.get(b)), -kb.get(b)));
        }
        inner = aggregateInner(buckets, kb, sb1, gamma);
        return new AggregateResult(Math.sqrt(Math.max(0.0, inner)), true, sb1);
    }

    private static double aggregateInner(List<String> buckets, Map<String, Double> kb,
                                         Map<String, Double> sb, CrossGamma gamma) {
        double total = 0.0;
        for (String b : buckets) {
            double k = kb.get(b);
            total += k * k;
        }
        for (String b : buckets) {
            for (String c : buckets) {
                if (!b.equals(c)) {
                    total += gamma.get(b, c) * sb.get(b) * sb.get(c);
                }
            }
        }
        return total;
    }

    // ---------------------------------------------------------------------
    // Delta / vega charges
    // ---------------------------------------------------------------------

    /**
     * Per-scenario charge with per-bucket K_b detail (for reporting/golden).
     *
     * @param charge       the risk-class charge under one scenario
     * @param kb           per-bucket within-bucket charges
     * @param usedFallback whether the across-bucket S_b fallback fired
     */
    public record RiskClassCharge(double charge, Map<String, Double> kb, boolean usedFallback) {
    }

    /**
     * Generic delta or vega charge for one risk class under one scenario.
     *
     * @param bucketWs  {@code bucket -> factorKey -> WS}
     * @param intraRho  (bucket, factorK, factorL) -&gt; medium-scenario correlation
     * @param gamma     (bucketB, bucketC) -&gt; medium-scenario cross-bucket gamma
     * @param scenario  "high" | "medium" | "low"
     * @param scenarioHigh high scaler (1.25 pinned)
     * @param scenarioLow  low scaler (0.75 pinned)
     * @return the charge with per-bucket K_b detail
     */
    public static RiskClassCharge deltaVegaCharge(Map<String, Map<String, Double>> bucketWs,
                                                  IntraRho intraRho, CrossGamma gamma,
                                                  String scenario,
                                                  double scenarioHigh, double scenarioLow) {
        Map<String, Double> kb = new LinkedHashMap<>();
        Map<String, Double> wsSum = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Double>> e : bucketWs.entrySet()) {
            String b = e.getKey();
            List<String> keys = new ArrayList<>(e.getValue().keySet());
            Collections.sort(keys);
            List<Double> ws = new ArrayList<>();
            for (String key : keys) {
                ws.add(e.getValue().get(key));
            }
            PairCorr rhoFn = (i, j) -> scaleRho(intraRho.get(b, keys.get(i), keys.get(j)),
                    scenario, scenarioHigh, scenarioLow);
            kb.put(b, bucketKb(ws, rhoFn));
            double sum = 0.0;
            for (double w : ws) {
                sum += w;
            }
            wsSum.put(b, sum);
        }

        if (kb.isEmpty()) {
            return new RiskClassCharge(0.0, Map.of(), false);
        }
        CrossGamma gammaFn = (b, c) -> scaleRho(gamma.get(b, c), scenario,
                scenarioHigh, scenarioLow);
        AggregateResult agg = aggregateBuckets(kb, wsSum, gammaFn);
        return new RiskClassCharge(agg.charge(), kb, agg.usedFallback());
    }

    // ---------------------------------------------------------------------
    // Curvature
    // ---------------------------------------------------------------------

    /** FRTB psi: 0 when both CVR terms are negative, else 1. */
    public static double psi(double a, double b) {
        return (a < 0.0 && b < 0.0) ? 0.0 : 1.0;
    }

    /**
     * Within-bucket curvature charge and side selection.
     *
     * @param kb the winning K_b = max(K_b+, K_b-)
     * @param sb the CVR sum on the winning side (up side on ties)
     */
    public record KbSb(double kb, double sb) {
    }

    /**
     * Within-bucket curvature charge.
     *
     * <p>{@code K_b+/- = sqrt(max(0, sum max(CVR,0)^2 + sum_{k!=l} rho CVR_k CVR_l psi))},
     * K_b = max of the sides, S_b = sum of CVRs on the winning side (up on
     * ties). {@code rho} must already be the CURVATURE correlation (the
     * scenario-scaled delta rho squared).
     */
    public static KbSb curvatureBucketKb(List<Double> cvrUp, List<Double> cvrDn, PairCorr rho) {
        if (cvrUp.size() != cvrDn.size()) {
            throw new IllegalArgumentException("curvatureBucketKb: up/down CVR lists must match");
        }
        double kUp = curvatureSide(cvrUp, rho);
        double kDn = curvatureSide(cvrDn, rho);
        if (kUp >= kDn) {
            return new KbSb(kUp, sum(cvrUp));
        }
        return new KbSb(kDn, sum(cvrDn));
    }

    private static double curvatureSide(List<Double> cvr, PairCorr rho) {
        double total = 0.0;
        for (double c : cvr) {
            double m = Math.max(c, 0.0);
            total += m * m;
        }
        int n = cvr.size();
        for (int k = 0; k < n; k++) {
            for (int l = 0; l < n; l++) {
                if (k != l) {
                    total += rho.get(k, l) * cvr.get(k) * cvr.get(l)
                            * psi(cvr.get(k), cvr.get(l));
                }
            }
        }
        return Math.sqrt(Math.max(0.0, total));
    }

    private static double sum(List<Double> xs) {
        double s = 0.0;
        for (double x : xs) {
            s += x;
        }
        return s;
    }

    /**
     * Curvature charge for one risk class under one scenario.
     *
     * @param bucketCvr  {@code bucket -> [CVR+ list, CVR- list]} aligned with
     *                   {@code factorKeys.get(bucket)}
     * @param intraRho   medium DELTA correlations; scenario-scaled then SQUARED
     *                   for curvature (pinned simplification)
     * @param gamma      medium DELTA cross-bucket gamma, same treatment
     * @param scenario   "high" | "medium" | "low"
     * @param factorKeys per-bucket factor key lists for the rho lookups
     * @param scenarioHigh high scaler
     * @param scenarioLow  low scaler
     * @return the curvature charge with per-bucket K_b detail
     */
    public static RiskClassCharge curvatureCharge(Map<String, List<List<Double>>> bucketCvr,
                                                  IntraRho intraRho, CrossGamma gamma,
                                                  String scenario,
                                                  Map<String, List<String>> factorKeys,
                                                  double scenarioHigh, double scenarioLow) {
        Map<String, Double> kb = new LinkedHashMap<>();
        Map<String, Double> sb = new LinkedHashMap<>();
        for (Map.Entry<String, List<List<Double>>> e : bucketCvr.entrySet()) {
            String b = e.getKey();
            List<Double> up = e.getValue().get(0);
            List<Double> dn = e.getValue().get(1);
            List<String> keys = factorKeys.get(b);
            if (keys == null || keys.size() != up.size()) {
                throw new IllegalArgumentException(
                        "curvatureCharge: factor keys mismatch in bucket '" + b + "'");
            }
            PairCorr rhoFn = (i, j) -> {
                double r = scaleRho(intraRho.get(b, keys.get(i), keys.get(j)), scenario,
                        scenarioHigh, scenarioLow);
                return r * r;
            };
            KbSb res = curvatureBucketKb(up, dn, rhoFn);
            kb.put(b, res.kb());
            sb.put(b, res.sb());
        }

        if (kb.isEmpty()) {
            return new RiskClassCharge(0.0, Map.of(), false);
        }
        List<String> buckets = new ArrayList<>(kb.keySet());
        Collections.sort(buckets);
        double total = 0.0;
        for (String b : buckets) {
            double k = kb.get(b);
            total += k * k;
        }
        for (String b : buckets) {
            for (String c : buckets) {
                if (!b.equals(c)) {
                    double g = scaleRho(gamma.get(b, c), scenario, scenarioHigh, scenarioLow);
                    total += (g * g) * sb.get(b) * sb.get(c) * psi(sb.get(b), sb.get(c));
                }
            }
        }
        return new RiskClassCharge(Math.sqrt(Math.max(0.0, total)), kb, false);
    }
}
