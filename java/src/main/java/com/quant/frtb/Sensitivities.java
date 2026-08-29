package com.quant.frtb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Bump-and-revalue sensitivities with pinned bump sizes.
 *
 * <p>Pinned bumps (documented in API_SPEC.md):
 * <ul>
 *   <li>GIRR delta: +1bp absolute bump of one curve node; {@code s = (V+ - V)/1e-4}
 *       (sensitivity per unit of rate, dV/dr).</li>
 *   <li>Equity delta: +1% relative spot bump; {@code s = (V+ - V)/0.01}
 *       (i.e. S*dV/dS, the FRTB relative-shift convention).</li>
 *   <li>Equity vega: +1 vol point absolute bump; {@code raw = (V+ - V)/0.01},
 *       WS uses {@code s = raw * sigma}.</li>
 *   <li>FX delta: +1% relative spot bump; {@code s = (V+ - V)/0.01}.</li>
 *   <li>Curvature: full risk-weight shock up/down (parallel for GIRR curves,
 *       relative for equity/FX spots) with the delta term stripped:
 *       {@code CVR+ = -(Vup - V - RW*s)}, {@code CVR- = -(Vdn - V + RW*s)}.</li>
 * </ul>
 *
 * <p>Sensitivities with {@code |s| <= 1e-9} are dropped (all-zero currencies
 * excluded from GIRR buckets). Bumps are applied to immutable market
 * snapshots with full portfolio revaluation each bump. Deterministic: no RNG.
 *
 * @param girr       {@code currency -> tenor -> dV/dr} (only non-zero currencies kept;
 *                   inner map iterates in pinned tenor order)
 * @param equityDelta {@code name -> S*dV/dS}
 * @param equityVega  {@code name -> vega*sigma}
 * @param fxDelta     {@code pair -> S*dV/dS}
 * @param girrCvr     {@code ccy -> [CVR+, CVR-]}
 * @param equityCvr   {@code name -> [CVR+, CVR-]}
 * @param fxCvr       {@code pair -> [CVR+, CVR-]}
 */
public record Sensitivities(Map<String, Map<Double, Double>> girr,
                            Map<String, Double> equityDelta,
                            Map<String, Double> equityVega,
                            Map<String, Double> fxDelta,
                            Map<String, double[]> girrCvr,
                            Map<String, double[]> equityCvr,
                            Map<String, double[]> fxCvr) {

    static final double GIRR_BUMP = 1e-4;   // 1bp absolute zero-rate bump
    static final double EQ_SPOT_BUMP = 0.01; // 1% relative spot bump
    static final double VOL_BUMP = 0.01;     // 1 vol point absolute bump
    static final double FX_BUMP = 0.01;      // 1% relative FX spot bump
    private static final double ZERO_TOL = 1e-9;

    /**
     * Full bump-and-revalue pass over one instrument scope (desk or firm).
     * An empty scope returns all-empty maps (capital 0 downstream).
     */
    public static Sensitivities compute(List<Instrument> instruments, Market market,
                                        SbmParams params) {
        double base = Pricers.pricePortfolio(instruments, market);

        // ---- GIRR delta: bump each curve node of each currency by 1bp ------
        Map<String, Map<Double, Double>> girr = new LinkedHashMap<>();
        for (String ccy : new TreeSet<>(market.curves().keySet())) {
            Map<Double, Double> perTenor = new LinkedHashMap<>();
            boolean anyNonZero = false;
            for (double tenor : params.girrTenors()) {
                Market bumped = market.bumpCurveNode(ccy, tenor, GIRR_BUMP);
                double s = (Pricers.pricePortfolio(instruments, bumped) - base) / GIRR_BUMP;
                if (Math.abs(s) > ZERO_TOL) {
                    anyNonZero = true;
                }
                perTenor.put(tenor, s);
            }
            if (anyNonZero) {
                girr.put(ccy, perTenor);
            }
        }

        // ---- Equity delta & vega -------------------------------------------
        Map<String, Double> equityDelta = new LinkedHashMap<>();
        Map<String, Double> equityVega = new LinkedHashMap<>();
        TreeSet<String> names = new TreeSet<>();
        TreeSet<String> pairs = new TreeSet<>();
        for (Instrument i : instruments) {
            if (i instanceof EquityOption opt) {
                names.add(opt.underlier());
            } else if (i instanceof FxForward fwd) {
                pairs.add(fwd.pair());
            }
        }
        for (String name : names) {
            Market.EquityQuote q = market.equity(name);
            double sd = (Pricers.pricePortfolio(instruments,
                    market.bumpEquitySpot(name, EQ_SPOT_BUMP)) - base) / EQ_SPOT_BUMP;
            double rawVega = (Pricers.pricePortfolio(instruments,
                    market.bumpEquityVol(name, VOL_BUMP)) - base) / VOL_BUMP;
            double sv = rawVega * q.vol();
            if (Math.abs(sd) > ZERO_TOL) {
                equityDelta.put(name, sd);
            }
            if (Math.abs(sv) > ZERO_TOL) {
                equityVega.put(name, sv);
            }
        }

        // ---- FX delta ------------------------------------------------------
        Map<String, Double> fxDelta = new LinkedHashMap<>();
        for (String pair : pairs) {
            double s = (Pricers.pricePortfolio(instruments,
                    market.bumpFx(pair, FX_BUMP)) - base) / FX_BUMP;
            if (Math.abs(s) > ZERO_TOL) {
                fxDelta.put(pair, s);
            }
        }

        // ---- Curvature -----------------------------------------------------
        Map<String, double[]> girrCvr = new LinkedHashMap<>();
        double rwC = params.girrCurvatureRw();
        for (Map.Entry<String, Map<Double, Double>> e : girr.entrySet()) {
            String ccy = e.getKey();
            double slope = 0.0; // sum of delta sensitivities over tenors
            for (double s : e.getValue().values()) {
                slope += s;
            }
            double vUp = Pricers.pricePortfolio(instruments, market.bumpCurveParallel(ccy, rwC));
            double vDn = Pricers.pricePortfolio(instruments, market.bumpCurveParallel(ccy, -rwC));
            girrCvr.put(ccy, new double[] {
                -(vUp - base - rwC * slope), -(vDn - base + rwC * slope)});
        }

        Map<String, double[]> equityCvr = new LinkedHashMap<>();
        for (String name : names) {
            Double s = equityDelta.get(name);
            if (s == null) {
                continue;
            }
            double rw = params.equityBucket(market.equity(name).bucket()).deltaRw();
            double vUp = Pricers.pricePortfolio(instruments, market.bumpEquitySpot(name, rw));
            double vDn = Pricers.pricePortfolio(instruments, market.bumpEquitySpot(name, -rw));
            equityCvr.put(name, new double[] {
                -(vUp - base - rw * s), -(vDn - base + rw * s)});
        }

        Map<String, double[]> fxCvr = new LinkedHashMap<>();
        for (String pair : pairs) {
            Double s = fxDelta.get(pair);
            if (s == null) {
                continue;
            }
            double rw = params.fxDeltaRw();
            double vUp = Pricers.pricePortfolio(instruments, market.bumpFx(pair, rw));
            double vDn = Pricers.pricePortfolio(instruments, market.bumpFx(pair, -rw));
            fxCvr.put(pair, new double[] {
                -(vUp - base - rw * s), -(vDn - base + rw * s)});
        }

        return new Sensitivities(girr, equityDelta, equityVega, fxDelta,
                girrCvr, equityCvr, fxCvr);
    }
}
