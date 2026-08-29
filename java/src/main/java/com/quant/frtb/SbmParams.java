package com.quant.frtb;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pinned regulatory parameter set, loaded from {@code data/sbm_params.json}.
 *
 * <p><b>IMPORTANT:</b> the parameter values are an EDUCATIONAL,
 * Basel-2019-flavored set — simplified bucket structure, pinned correlations,
 * no securitisation buckets. They are NOT the official Basel text and must
 * not be used for real capital.
 *
 * <p>All lookups raise {@link IllegalArgumentException} with a clear message
 * when a bucket / rating / tenor is missing (spec edge case), mirroring the
 * Python reference's {@code ValueError}s.
 */
public final class SbmParams {

    /** Per-bucket equity parameters: delta RW, vega RW, intra-bucket rho. */
    public record EquityBucket(double deltaRw, double vegaRw, double rho) {
    }

    // GIRR
    private final double[] girrTenors;
    private final Map<Double, Double> girrDeltaRw;
    private final double[][] girrRho;
    private final double girrVegaRw;
    private final double girrCurvatureRw;
    private final double girrGamma;
    // Equity
    private final Map<String, EquityBucket> equityBuckets;
    private final double equityGamma;
    // FX
    private final double fxDeltaRw;
    private final double fxRho;
    private final double fxGamma;
    // scenario scalers
    private final double scenarioHigh;
    private final double scenarioLow;
    // DRC
    private final Map<String, Double> drcRwByRating;
    // RRAO
    private final Map<String, Double> rraoRates;
    // IMA
    private final double imaAlpha;
    private final double imaRho;
    private final int[] lhLadder;
    private final Map<String, Integer> categoryLh;
    private final Map<Integer, Double> backtestAmberMultipliers;
    private final double backtestBaseMultiplier;
    private final double backtestRedMultiplier;
    // PLAT
    private final double platSpearmanGreen;
    private final double platSpearmanAmber;
    private final double platKsGreen;
    private final double platKsAmber;
    private final double platKSurcharge;

    private SbmParams(double[] girrTenors, Map<Double, Double> girrDeltaRw, double[][] girrRho,
                      double girrVegaRw, double girrCurvatureRw, double girrGamma,
                      Map<String, EquityBucket> equityBuckets, double equityGamma,
                      double fxDeltaRw, double fxRho, double fxGamma,
                      double scenarioHigh, double scenarioLow,
                      Map<String, Double> drcRwByRating, Map<String, Double> rraoRates,
                      double imaAlpha, double imaRho, int[] lhLadder,
                      Map<String, Integer> categoryLh,
                      Map<Integer, Double> backtestAmberMultipliers,
                      double backtestBaseMultiplier, double backtestRedMultiplier,
                      double platSpearmanGreen, double platSpearmanAmber,
                      double platKsGreen, double platKsAmber, double platKSurcharge) {
        this.girrTenors = girrTenors;
        this.girrDeltaRw = girrDeltaRw;
        this.girrRho = girrRho;
        this.girrVegaRw = girrVegaRw;
        this.girrCurvatureRw = girrCurvatureRw;
        this.girrGamma = girrGamma;
        this.equityBuckets = equityBuckets;
        this.equityGamma = equityGamma;
        this.fxDeltaRw = fxDeltaRw;
        this.fxRho = fxRho;
        this.fxGamma = fxGamma;
        this.scenarioHigh = scenarioHigh;
        this.scenarioLow = scenarioLow;
        this.drcRwByRating = drcRwByRating;
        this.rraoRates = rraoRates;
        this.imaAlpha = imaAlpha;
        this.imaRho = imaRho;
        this.lhLadder = lhLadder;
        this.categoryLh = categoryLh;
        this.backtestAmberMultipliers = backtestAmberMultipliers;
        this.backtestBaseMultiplier = backtestBaseMultiplier;
        this.backtestRedMultiplier = backtestRedMultiplier;
        this.platSpearmanGreen = platSpearmanGreen;
        this.platSpearmanAmber = platSpearmanAmber;
        this.platKsGreen = platKsGreen;
        this.platKsAmber = platKsAmber;
        this.platKSurcharge = platKSurcharge;
    }

    // ------------------------------------------------------------ lookups --

    /** Pinned GIRR tenor grid (copy). */
    public double[] girrTenors() {
        return girrTenors.clone();
    }

    /** GIRR delta risk weight for one tenor; error when not pinned. */
    public double girrRw(double tenor) {
        Double rw = girrDeltaRw.get(tenor);
        if (rw == null) {
            throw new IllegalArgumentException(
                    "SbmParams: no GIRR delta risk weight for tenor " + tenor);
        }
        return rw;
    }

    /** GIRR tenor-tenor delta correlation by tenor indices. */
    public double girrRhoKl(int i, int j) {
        return girrRho[i][j];
    }

    /** GIRR vega risk weight (unused with the bundled book: GIRR vega = 0). */
    public double girrVegaRw() {
        return girrVegaRw;
    }

    /** GIRR curvature risk weight (parallel absolute curve shock size). */
    public double girrCurvatureRw() {
        return girrCurvatureRw;
    }

    /** GIRR cross-currency gamma. */
    public double girrGamma() {
        return girrGamma;
    }

    /** Equity bucket parameters; unknown bucket raises. */
    public EquityBucket equityBucket(String bucket) {
        EquityBucket b = equityBuckets.get(bucket);
        if (b == null) {
            throw new IllegalArgumentException("SbmParams: unknown equity bucket '" + bucket
                    + "' (known: " + new TreeMap<>(equityBuckets).keySet() + ")");
        }
        return b;
    }

    /** Equity cross-bucket gamma. */
    public double equityGamma() {
        return equityGamma;
    }

    /** FX delta risk weight (single pinned bucket). */
    public double fxDeltaRw() {
        return fxDeltaRw;
    }

    /** FX intra-bucket rho. */
    public double fxRho() {
        return fxRho;
    }

    /** FX cross-bucket gamma (unused with a single bucket). */
    public double fxGamma() {
        return fxGamma;
    }

    /** High-scenario correlation scaler (1.25, capped at rho = 1). */
    public double scenarioHigh() {
        return scenarioHigh;
    }

    /** Low-scenario correlation scaler (0.75, pinned simplification). */
    public double scenarioLow() {
        return scenarioLow;
    }

    /** DRC risk weight by rating; unknown rating raises. */
    public double drcRw(String rating) {
        Double rw = drcRwByRating.get(rating);
        if (rw == null) {
            throw new IllegalArgumentException("SbmParams: no DRC risk weight for rating '"
                    + rating + "' (known: " + new TreeMap<>(drcRwByRating).keySet() + ")");
        }
        return rw;
    }

    /** RRAO rate by category; unknown category raises. */
    public double rraoRate(String category) {
        Double rate = rraoRates.get(category);
        if (rate == null) {
            throw new IllegalArgumentException(
                    "SbmParams: unknown RRAO category '" + category + "'");
        }
        return rate;
    }

    /** ES confidence level alpha (0.975 pinned). */
    public double imaAlpha() {
        return imaAlpha;
    }

    /** IMCC rho blend weight (0.5 pinned). */
    public double imaRho() {
        return imaRho;
    }

    /** Liquidity-horizon ladder (10, 20, 40, 60, 120 pinned; copy). */
    public int[] lhLadder() {
        return lhLadder.clone();
    }

    /** Pinned liquidity horizon per risk-factor category. */
    public Map<String, Integer> categoryLh() {
        return categoryLh;
    }

    /** Pinned amber backtest multipliers, exceptions 5..9. */
    public Map<Integer, Double> backtestAmberMultipliers() {
        return backtestAmberMultipliers;
    }

    /** Green-zone backtest multiplier (1.5 pinned). */
    public double backtestBaseMultiplier() {
        return backtestBaseMultiplier;
    }

    /** Red-zone backtest multiplier cap (2.0 pinned). */
    public double backtestRedMultiplier() {
        return backtestRedMultiplier;
    }

    /** PLAT green Spearman threshold (0.85). */
    public double platSpearmanGreen() {
        return platSpearmanGreen;
    }

    /** PLAT amber Spearman threshold (0.80). */
    public double platSpearmanAmber() {
        return platSpearmanAmber;
    }

    /** PLAT green KS threshold (0.09). */
    public double platKsGreen() {
        return platKsGreen;
    }

    /** PLAT amber KS threshold (0.12). */
    public double platKsAmber() {
        return platKsAmber;
    }

    /** PLAT amber surcharge interpolation factor k (0.5). */
    public double platKSurcharge() {
        return platKSurcharge;
    }

    /**
     * Copy with every GIRR delta RW scaled by {@code factor} (the +-10%
     * capital stability check of the validation framework).
     */
    public SbmParams withGirrDeltaRwScaled(double factor) {
        if (factor <= 0.0 || !Double.isFinite(factor)) {
            throw new IllegalArgumentException("withGirrDeltaRwScaled: bad factor " + factor);
        }
        Map<Double, Double> scaled = new LinkedHashMap<>();
        for (Map.Entry<Double, Double> e : girrDeltaRw.entrySet()) {
            scaled.put(e.getKey(), e.getValue() * factor);
        }
        return new SbmParams(girrTenors, Collections.unmodifiableMap(scaled), girrRho,
                girrVegaRw, girrCurvatureRw, girrGamma, equityBuckets, equityGamma,
                fxDeltaRw, fxRho, fxGamma, scenarioHigh, scenarioLow, drcRwByRating,
                rraoRates, imaAlpha, imaRho, lhLadder, categoryLh,
                backtestAmberMultipliers, backtestBaseMultiplier, backtestRedMultiplier,
                platSpearmanGreen, platSpearmanAmber, platKsGreen, platKsAmber,
                platKSurcharge);
    }

    // ------------------------------------------------------------- loading --

    /**
     * Load and validate the pinned parameter file; missing keys raise
     * {@link IllegalArgumentException} ({@code ValueError} analogue).
     */
    public static SbmParams load(Path path) {
        String text;
        try {
            text = Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Object> raw = Json.asObject(Json.parse(text), "sbm_params root");

        Map<String, Object> girr = Json.asObject(req(raw, "girr", "root"), "girr");
        List<Object> tenorList = Json.asArray(req(girr, "tenors", "girr"), "girr.tenors");
        double[] tenors = new double[tenorList.size()];
        for (int i = 0; i < tenors.length; i++) {
            tenors[i] = Json.asNumber(tenorList.get(i), "girr.tenors");
        }
        Map<String, Object> rwRaw = Json.asObject(req(girr, "delta_rw", "girr"), "girr.delta_rw");
        Map<Double, Double> deltaRw = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : rwRaw.entrySet()) {
            deltaRw.put(Double.parseDouble(e.getKey()),
                    Json.asNumber(e.getValue(), "girr.delta_rw"));
        }
        for (double t : tenors) {
            if (!deltaRw.containsKey(t)) {
                throw new IllegalArgumentException(
                        "sbm_params.json: girr.delta_rw missing tenor " + t);
            }
        }
        List<Object> rhoRaw = Json.asArray(req(girr, "delta_rho", "girr"), "girr.delta_rho");
        int n = tenors.length;
        if (rhoRaw.size() != n) {
            throw new IllegalArgumentException(
                    "sbm_params.json: girr.delta_rho must be a square tenor x tenor matrix");
        }
        double[][] rho = new double[n][n];
        for (int i = 0; i < n; i++) {
            List<Object> row = Json.asArray(rhoRaw.get(i), "girr.delta_rho row");
            if (row.size() != n) {
                throw new IllegalArgumentException(
                        "sbm_params.json: girr.delta_rho must be a square tenor x tenor matrix");
            }
            for (int j = 0; j < n; j++) {
                rho[i][j] = Json.asNumber(row.get(j), "girr.delta_rho");
            }
            if (Math.abs(rho[i][i] - 1.0) > 1e-12) {
                throw new IllegalArgumentException(
                        "sbm_params.json: girr.delta_rho diagonal must be 1");
            }
        }

        Map<String, Object> eq = Json.asObject(req(raw, "equity", "root"), "equity");
        Map<String, EquityBucket> ebuckets = new LinkedHashMap<>();
        Map<String, Object> bucketsRaw =
                Json.asObject(req(eq, "buckets", "equity"), "equity.buckets");
        for (Map.Entry<String, Object> e : bucketsRaw.entrySet()) {
            Map<String, Object> p = Json.asObject(e.getValue(), "equity bucket " + e.getKey());
            String ctx = "equity bucket " + e.getKey();
            ebuckets.put(e.getKey(), new EquityBucket(
                    Json.asNumber(req(p, "delta_rw", ctx), ctx),
                    Json.asNumber(req(p, "vega_rw", ctx), ctx),
                    Json.asNumber(req(p, "rho", ctx), ctx)));
        }

        Map<String, Object> fx = Json.asObject(req(raw, "fx", "root"), "fx");
        Map<String, Object> scen = Json.asObject(req(raw, "scenarios", "root"), "scenarios");
        Map<String, Object> drc = Json.asObject(req(raw, "drc", "root"), "drc");
        Map<String, Object> rrao = Json.asObject(req(raw, "rrao", "root"), "rrao");
        Map<String, Object> ima = Json.asObject(req(raw, "ima", "root"), "ima");
        Map<String, Object> bt =
                Json.asObject(req(ima, "backtest_multiplier", "ima"), "backtest");
        Map<String, Object> plat = Json.asObject(req(ima, "plat", "ima"), "plat");

        Map<String, Double> drcRw = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : Json.asObject(req(drc, "rw_by_rating", "drc"),
                "drc.rw_by_rating").entrySet()) {
            drcRw.put(e.getKey(), Json.asNumber(e.getValue(), "drc.rw_by_rating"));
        }
        Map<String, Double> rraoRates = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : rrao.entrySet()) {
            if (e.getValue() instanceof Double d) {
                rraoRates.put(e.getKey(), d);
            }
        }
        List<Object> ladderRaw = Json.asArray(req(ima, "lh_ladder", "ima"), "ima.lh_ladder");
        int[] ladder = new int[ladderRaw.size()];
        for (int i = 0; i < ladder.length; i++) {
            ladder[i] = (int) Json.asNumber(ladderRaw.get(i), "ima.lh_ladder");
        }
        Map<String, Integer> catLh = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : Json.asObject(req(ima, "category_lh", "ima"),
                "ima.category_lh").entrySet()) {
            catLh.put(e.getKey(), (int) Json.asNumber(e.getValue(), "ima.category_lh"));
        }
        Map<Integer, Double> amber = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : Json.asObject(req(bt, "amber", "backtest"),
                "backtest.amber").entrySet()) {
            amber.put(Integer.parseInt(e.getKey()),
                    Json.asNumber(e.getValue(), "backtest.amber"));
        }

        return new SbmParams(
                tenors, Collections.unmodifiableMap(deltaRw), rho,
                Json.asNumber(req(girr, "vega_rw", "girr"), "girr.vega_rw"),
                Json.asNumber(req(girr, "curvature_rw", "girr"), "girr.curvature_rw"),
                Json.asNumber(req(girr, "gamma", "girr"), "girr.gamma"),
                Collections.unmodifiableMap(ebuckets),
                Json.asNumber(req(eq, "gamma", "equity"), "equity.gamma"),
                Json.asNumber(req(fx, "delta_rw", "fx"), "fx.delta_rw"),
                Json.asNumber(req(fx, "rho", "fx"), "fx.rho"),
                Json.asNumber(req(fx, "gamma", "fx"), "fx.gamma"),
                Json.asNumber(req(scen, "high", "scenarios"), "scenarios.high"),
                Json.asNumber(req(scen, "low", "scenarios"), "scenarios.low"),
                Collections.unmodifiableMap(drcRw), Collections.unmodifiableMap(rraoRates),
                Json.asNumber(req(ima, "alpha", "ima"), "ima.alpha"),
                Json.asNumber(req(ima, "rho", "ima"), "ima.rho"),
                ladder, Collections.unmodifiableMap(catLh),
                Collections.unmodifiableMap(amber),
                Json.asNumber(req(bt, "base", "backtest"), "backtest.base"),
                Json.asNumber(req(bt, "red", "backtest"), "backtest.red"),
                Json.asNumber(req(plat, "spearman_green", "plat"), "plat.spearman_green"),
                Json.asNumber(req(plat, "spearman_amber", "plat"), "plat.spearman_amber"),
                Json.asNumber(req(plat, "ks_green", "plat"), "plat.ks_green"),
                Json.asNumber(req(plat, "ks_amber", "plat"), "plat.ks_amber"),
                Json.asNumber(req(plat, "k_surcharge", "plat"), "plat.k_surcharge"));
    }

    private static Object req(Map<String, Object> d, String key, String ctx) {
        if (!d.containsKey(key)) {
            throw new IllegalArgumentException(
                    "sbm_params.json: missing '" + key + "' in " + ctx);
        }
        return d.get(key);
    }
}
