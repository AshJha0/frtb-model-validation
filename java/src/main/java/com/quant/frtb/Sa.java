package com.quant.frtb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Standardised Approach assembly: SBM charges per risk class and scenario,
 * plus DRC-lite and RRAO. SA capital = SBM + DRC + RRAO.
 */
public final class Sa {

    private Sa() {
    }

    /** Risk classes in reporting order. */
    public static final List<String> RISK_CLASSES = List.of("girr", "equity", "fx");

    /** Measures in reporting order. */
    public static final List<String> MEASURES = List.of("delta", "vega", "curvature");

    /**
     * SBM capital with full drill-down.
     *
     * @param charges        {@code riskClass -> measure -> scenario -> charge}
     * @param kbMedium       {@code riskClass -> measure -> bucket -> K_b} (medium scenario)
     * @param scenarioTotals {@code scenario -> sum over risk classes and measures}
     * @param capital        max over scenarios of the scenario totals
     */
    public record SbmResult(Map<String, Map<String, Map<String, Double>>> charges,
                            Map<String, Map<String, Map<String, Double>>> kbMedium,
                            Map<String, Double> scenarioTotals,
                            double capital) {
    }

    /** Format a tenor as its Python {@code "%g"} label ("0.25", "1", "10", ...). */
    static String tenorLabel(double t) {
        if (t == Math.rint(t) && Math.abs(t) < 1e15) {
            return Long.toString((long) t);
        }
        return Double.toString(t);
    }

    /** Assemble the full SBM capital: 3 risk classes x 3 measures x 3 scenarios. */
    public static SbmResult sbmCapital(Sensitivities sens, Market market, SbmParams params) {
        double hi = params.scenarioHigh();
        double lo = params.scenarioLow();
        Map<String, Map<String, Map<String, Double>>> charges = new LinkedHashMap<>();
        Map<String, Map<String, Map<String, Double>>> kbMedium = new LinkedHashMap<>();
        for (String rc : RISK_CLASSES) {
            Map<String, Map<String, Double>> perMeasure = new LinkedHashMap<>();
            for (String m : MEASURES) {
                perMeasure.put(m, new LinkedHashMap<>());
            }
            charges.put(rc, perMeasure);
            kbMedium.put(rc, new LinkedHashMap<>());
        }

        // -- GIRR delta structs ------------------------------------------------
        double[] tenors = params.girrTenors();
        Map<String, Integer> labelIndex = new LinkedHashMap<>();
        for (int i = 0; i < tenors.length; i++) {
            labelIndex.put(tenorLabel(tenors[i]), i);
        }
        Map<String, Map<String, Double>> girrWs = new LinkedHashMap<>();
        for (Map.Entry<String, Map<Double, Double>> e : sens.girr().entrySet()) {
            Map<String, Double> perTenor = new LinkedHashMap<>();
            for (Map.Entry<Double, Double> te : e.getValue().entrySet()) {
                perTenor.put(tenorLabel(te.getKey()),
                        params.girrRw(te.getKey()) * te.getValue());
            }
            girrWs.put(e.getKey(), perTenor);
        }
        Sbm.IntraRho girrRho = (bucket, k, l) ->
                params.girrRhoKl(labelIndex.get(k), labelIndex.get(l));
        Sbm.CrossGamma girrGamma = (b, c) -> params.girrGamma();

        // -- Equity delta/vega structs ----------------------------------------
        Map<String, Map<String, Double>> eqdWs = equityWs(sens.equityDelta(), market, params, false);
        Map<String, Map<String, Double>> eqvWs = equityWs(sens.equityVega(), market, params, true);
        Sbm.IntraRho eqRho = (bucket, k, l) -> params.equityBucket(bucket).rho();
        Sbm.CrossGamma eqGamma = (b, c) -> params.equityGamma();

        // -- FX delta structs (single pinned "FX" bucket) ---------------------
        Map<String, Map<String, Double>> fxWs = new LinkedHashMap<>();
        if (!sens.fxDelta().isEmpty()) {
            Map<String, Double> perPair = new LinkedHashMap<>();
            for (Map.Entry<String, Double> e : sens.fxDelta().entrySet()) {
                perPair.put(e.getKey(), params.fxDeltaRw() * e.getValue());
            }
            fxWs.put("FX", perPair);
        }
        Sbm.IntraRho fxRho = (bucket, k, l) -> params.fxRho();
        Sbm.CrossGamma fxGamma = (b, c) -> params.fxGamma();

        // -- delta / vega ------------------------------------------------------
        record DvSpec(String rc, String measure, Map<String, Map<String, Double>> ws,
                      Sbm.IntraRho rho, Sbm.CrossGamma gamma) {
        }
        List<DvSpec> dvSpecs = List.of(
                new DvSpec("girr", "delta", girrWs, girrRho, girrGamma),
                // no IR-vol instruments in scope => GIRR vega is identically 0
                new DvSpec("girr", "vega", Map.of(), girrRho, girrGamma),
                new DvSpec("equity", "delta", eqdWs, eqRho, eqGamma),
                new DvSpec("equity", "vega", eqvWs, eqRho, eqGamma),
                new DvSpec("fx", "delta", fxWs, fxRho, fxGamma));
        for (DvSpec spec : dvSpecs) {
            for (String scen : Sbm.SCENARIOS) {
                Sbm.RiskClassCharge res = Sbm.deltaVegaCharge(spec.ws(), spec.rho(),
                        spec.gamma(), scen, hi, lo);
                charges.get(spec.rc()).get(spec.measure()).put(scen, res.charge());
                if ("medium".equals(scen)) {
                    kbMedium.get(spec.rc()).put(spec.measure(), new LinkedHashMap<>(res.kb()));
                }
            }
        }

        // -- curvature ---------------------------------------------------------
        Map<String, List<List<Double>>> girrCvr = new LinkedHashMap<>();
        Map<String, List<String>> girrKeys = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> e : sens.girrCvr().entrySet()) {
            girrCvr.put(e.getKey(), List.of(List.of(e.getValue()[0]), List.of(e.getValue()[1])));
            girrKeys.put(e.getKey(), List.of("crv"));
        }
        Map<String, List<List<Double>>> eqCvr = new LinkedHashMap<>();
        Map<String, List<String>> eqKeys = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> e : sens.equityCvr().entrySet()) {
            String b = market.equity(e.getKey()).bucket();
            params.equityBucket(b); // error when the bucket is not pinned
            List<List<Double>> lists = eqCvr.computeIfAbsent(b,
                    key -> List.of(new ArrayList<>(), new ArrayList<>()));
            eqKeys.computeIfAbsent(b, key -> new ArrayList<>()).add(e.getKey());
            lists.get(0).add(e.getValue()[0]);
            lists.get(1).add(e.getValue()[1]);
        }
        Map<String, List<List<Double>>> fxCvr = new LinkedHashMap<>();
        Map<String, List<String>> fxKeys = new LinkedHashMap<>();
        if (!sens.fxCvr().isEmpty()) {
            List<Double> ups = new ArrayList<>();
            List<Double> dns = new ArrayList<>();
            List<String> keys = new ArrayList<>();
            for (Map.Entry<String, double[]> e : sens.fxCvr().entrySet()) {
                ups.add(e.getValue()[0]);
                dns.add(e.getValue()[1]);
                keys.add(e.getKey());
            }
            fxCvr.put("FX", List.of(ups, dns));
            fxKeys.put("FX", keys);
        }

        record CrvSpec(String rc, Map<String, List<List<Double>>> cvr, Sbm.IntraRho rho,
                       Sbm.CrossGamma gamma, Map<String, List<String>> keys) {
        }
        List<CrvSpec> crvSpecs = List.of(
                new CrvSpec("girr", girrCvr, girrRho, girrGamma, girrKeys),
                new CrvSpec("equity", eqCvr, eqRho, eqGamma, eqKeys),
                new CrvSpec("fx", fxCvr, fxRho, fxGamma, fxKeys));
        for (CrvSpec spec : crvSpecs) {
            for (String scen : Sbm.SCENARIOS) {
                Sbm.RiskClassCharge res = Sbm.curvatureCharge(spec.cvr(), spec.rho(),
                        spec.gamma(), scen, spec.keys(), hi, lo);
                charges.get(spec.rc()).get("curvature").put(scen, res.charge());
                if ("medium".equals(scen)) {
                    kbMedium.get(spec.rc()).put("curvature", new LinkedHashMap<>(res.kb()));
                }
            }
        }

        // fx vega not modelled: pin to zero for all scenarios
        for (String scen : Sbm.SCENARIOS) {
            charges.get("fx").get("vega").put(scen, 0.0);
        }

        Map<String, Double> scenarioTotals = new LinkedHashMap<>();
        for (String scen : Sbm.SCENARIOS) {
            double total = 0.0;
            for (String rc : RISK_CLASSES) {
                for (String m : MEASURES) {
                    Double c = charges.get(rc).get(m).get(scen);
                    total += c == null ? 0.0 : c;
                }
            }
            scenarioTotals.put(scen, total);
        }
        double capital = scenarioTotals.values().stream()
                .mapToDouble(Double::doubleValue).max().orElse(0.0);
        return new SbmResult(charges, kbMedium, scenarioTotals, capital);
    }

    /** Equity delta or vega WS per bucket, factor key = underlier name. */
    private static Map<String, Map<String, Double>> equityWs(Map<String, Double> sensMap,
                                                             Market market, SbmParams params,
                                                             boolean vega) {
        Map<String, Map<String, Double>> bucketWs = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : sensMap.entrySet()) {
            String b = market.equity(e.getKey()).bucket();
            SbmParams.EquityBucket p = params.equityBucket(b); // error when not pinned
            double rw = vega ? p.vegaRw() : p.deltaRw();
            bucketWs.computeIfAbsent(b, key -> new LinkedHashMap<>())
                    .put(e.getKey(), rw * e.getValue());
        }
        return bucketWs;
    }

    // ---------------------------------------------------------------------
    // DRC-lite
    // ---------------------------------------------------------------------

    /**
     * One default-risk position: issuer, rating, notional (signed), market
     * value; {@code lgd} pinned at 0.75 for bonds.
     *
     * @param issuer      netting key
     * @param rating      pinned rating bucket
     * @param notional    signed face amount (shorts negative)
     * @param marketValue current mark-to-market
     * @param lgd         loss-given-default
     */
    public record DrcPosition(String issuer, String rating, double notional,
                              double marketValue, double lgd) {

        /** Jump-to-default: {@code JTD = LGD*notional + (MV - notional)} (signed). */
        public double jtd() {
            return lgd * notional + (marketValue - notional);
        }
    }

    /**
     * DRC-lite output: charge plus netting/HBR drill-down.
     *
     * @param charge     the default risk charge
     * @param hbr        hedge benefit ratio (1 when no net shorts)
     * @param netJtd     net JTD per issuer
     * @param grossLong  sum of net long JTDs
     * @param grossShort sum of |net short JTDs|
     */
    public record DrcResult(double charge, double hbr, Map<String, Double> netJtd,
                            double grossLong, double grossShort) {
    }

    /**
     * Default Risk Charge (lite).
     *
     * <ol>
     *   <li>{@code JTD_i = LGD*notional + (MV - notional)} per position.</li>
     *   <li>Net JTD per issuer (long/short netting within the same issuer).</li>
     *   <li>{@code HBR = sum(netLong) / (sum(netLong) + sum(|netShort|))};
     *       HBR = 1 with no net shorts and for an empty book.</li>
     *   <li>{@code DRC = max(0, sum RW*netLong - HBR * sum RW*|netShort|)}
     *       with RW from the pinned rating table (unknown rating raises).</li>
     * </ol>
     */
    public static DrcResult drcCharge(List<DrcPosition> positions, SbmParams params) {
        Map<String, Double> net = new LinkedHashMap<>();
        Map<String, String> ratingOf = new LinkedHashMap<>();
        for (DrcPosition p : positions) {
            net.merge(p.issuer(), p.jtd(), Double::sum);
            String prev = ratingOf.putIfAbsent(p.issuer(), p.rating());
            if (prev != null && !prev.equals(p.rating())) {
                throw new IllegalArgumentException("drcCharge: issuer '" + p.issuer()
                        + "' has inconsistent ratings ('" + prev + "' vs '" + p.rating() + "')");
            }
        }
        double longSum = 0.0;
        double shortSum = 0.0;
        for (double v : net.values()) {
            if (v > 0.0) {
                longSum += v;
            } else if (v < 0.0) {
                shortSum += -v;
            }
        }
        double denom = longSum + shortSum;
        double hbr = denom > 0.0 ? longSum / denom : 1.0;
        double weightedLong = 0.0;
        double weightedShort = 0.0;
        for (Map.Entry<String, Double> e : net.entrySet()) {
            if (e.getValue() > 0.0) {
                weightedLong += params.drcRw(ratingOf.get(e.getKey())) * e.getValue();
            } else if (e.getValue() < 0.0) {
                weightedShort += params.drcRw(ratingOf.get(e.getKey())) * (-e.getValue());
            }
        }
        double charge = Math.max(0.0, weightedLong - hbr * weightedShort);
        return new DrcResult(charge, hbr, net, longSum, shortSum);
    }

    /** Extract DRC positions (bonds only in this educational kit — documented). */
    public static List<DrcPosition> drcPositionsFromInstruments(List<Instrument> instruments,
                                                                Market market) {
        List<DrcPosition> out = new ArrayList<>();
        for (Instrument inst : instruments) {
            if (inst instanceof Bond bond) {
                double mv = Pricers.priceBond(bond, market.curve(bond.currency()));
                out.add(new DrcPosition(bond.issuer(), bond.rating(), bond.notional(),
                        mv, bond.lgd()));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // RRAO
    // ---------------------------------------------------------------------

    /**
     * Residual risk add-on: sum over flagged instruments of rate * notional.
     * Pinned rates: 'exotic' 1.0%, 'other' 0.1% of the flagged notional.
     */
    public static double rraoCharge(List<Instrument> instruments, SbmParams params) {
        double total = 0.0;
        for (Instrument inst : instruments) {
            RraoFlag flag = inst.rrao();
            if (flag != null) {
                total += params.rraoRate(flag.category()) * flag.notional();
            }
        }
        return total;
    }
}
