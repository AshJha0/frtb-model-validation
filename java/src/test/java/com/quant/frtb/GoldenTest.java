package com.quant.frtb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Golden-value suite: every case in {@code data/golden/golden.json} is
 * recomputed from the bundled inputs and must agree within its tolerance
 * (cross-language contract shared with the Python/C++/Rust suites).
 */
public class GoldenTest {

    private static List<Object> loadCases() throws IOException {
        String text = Files.readString(TestData.DATA_DIR.resolve("golden").resolve("golden.json"));
        Map<String, Object> doc = Json.asObject(Json.parse(text), "golden root");
        List<Object> cases = Json.asArray(doc.get("cases"), "cases");
        assertTrue("expected >= 20 golden cases", cases.size() >= 20);
        return cases;
    }

    /** Recompute the expectation map for one golden case. */
    private static Map<String, Object> computedValues(String name, Map<String, Object> inputs,
                                                      Engine.Results res) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Engine.SaScope> sa = res.sa;
        Map<String, Engine.DeskIma> ima = res.ima;
        Engine.ValidationResults val = res.validation;
        switch (name) {
            case "sbm_agg_hand_2bucket": {
                double wsA1 = (Double) inputs.get("ws_a1");
                double wsA2 = (Double) inputs.get("ws_a2");
                double rhoA = (Double) inputs.get("rho_a");
                double wsB1 = (Double) inputs.get("ws_b1");
                double gamma = (Double) inputs.get("gamma");
                double kA = Sbm.bucketKb(List.of(wsA1, wsA2), (i, j) -> rhoA);
                double kB = Sbm.bucketKb(List.of(wsB1), (i, j) -> 0.0);
                Sbm.AggregateResult agg = Sbm.aggregateBuckets(
                        Map.of("A", kA, "B", kB),
                        Map.of("A", wsA1 + wsA2, "B", wsB1),
                        (b, c) -> gamma);
                out.put("k_a", kA);
                out.put("k_b", kB);
                out.put("total", agg.charge());
                return out;
            }
            case "girr_ws_rates_desk": {
                Desk desk = res.desks.get((String) inputs.get("desk"));
                Sensitivities sens = Sensitivities.compute(desk.instruments(), res.market,
                        res.params);
                Map<Double, Double> perTenor = sens.girr().get((String) inputs.get("currency"));
                for (Map.Entry<Double, Double> e : perTenor.entrySet()) {
                    out.put("ws_" + Sa.tenorLabel(e.getKey()),
                            res.params.girrRw(e.getKey()) * e.getValue());
                }
                return out;
            }
            case "girr_kb_usd":
                out.put("kb", sa.get("firm").sbm().kbMedium().get("girr").get("delta").get("USD"));
                return out;
            case "equity_kb_bucket1":
                out.put("kb", sa.get("firm").sbm().kbMedium().get("equity").get("delta").get("1"));
                return out;
            case "sbm_firm_scenarios":
                out.putAll(sa.get("firm").sbm().scenarioTotals());
                out.put("capital", sa.get("firm").sbm().capital());
                return out;
            case "sbm_desk_capitals":
                out.put("desk1", sa.get("desk1").sbm().capital());
                out.put("desk2", sa.get("desk2").sbm().capital());
                return out;
            case "curvature_equity_desk2":
                out.put("charge", sa.get("desk2").sbm().charges().get("equity")
                        .get("curvature").get("medium"));
                return out;
            case "drc_firm":
                out.put("charge", sa.get("firm").drc());
                out.put("hbr", sa.get("firm").drcHbr());
                return out;
            case "rrao_firm":
                out.put("charge", sa.get("firm").rrao());
                return out;
            case "es_desk1":
            case "es_desk2": {
                Engine.DeskIma d = ima.get((String) inputs.get("desk"));
                out.put("es_base", d.esBase());
                out.put("es_lh", d.esLh());
                return out;
            }
            case "imcc_desks":
                out.put("desk1", ima.get("desk1").imcc());
                out.put("desk2", ima.get("desk2").imcc());
                return out;
            case "plat_desk1":
            case "plat_desk2": {
                Plat.PlatResult pl = ima.get((String) inputs.get("desk")).plat();
                out.put("spearman", pl.spearman());
                out.put("ks", pl.ks());
                out.put("zone", pl.zone());
                return out;
            }
            case "backtest_desk1":
            case "backtest_desk2": {
                Ima.BacktestResult bt = ima.get((String) inputs.get("desk")).backtest();
                out.put("exceptions", (double) bt.exceptions());
                out.put("multiplier", bt.multiplier());
                return out;
            }
            case "ses_firm":
                out.put("charge", ima.get("desk1").ses() + ima.get("desk2").ses());
                return out;
            case "benchmark_max_diff":
                out.put("value", val.benchmarkMaxDiff());
                return out;
            case "stability_girr_rw_up10":
                out.put("delta_capital",
                        val.stabilityCapitalRwUp10() - val.stabilityBaseCapital());
                return out;
            case "verdict_desk1":
            case "verdict_desk2":
                out.put("verdict", val.verdicts().get((String) inputs.get("desk")));
                return out;
            default:
                fail("golden case '" + name + "' has no recompute mapping");
                return out; // unreachable
        }
    }

    @Test
    public void allGoldenCases() throws IOException {
        Engine.Results res = TestData.results();
        for (Object caseObj : loadCases()) {
            Map<String, Object> c = Json.asObject(caseObj, "case");
            String name = (String) c.get("name");
            Map<String, Object> inputs = Json.asObject(c.get("inputs"), "inputs");
            Map<String, Object> expect = Json.asObject(c.get("expect"), "expect");
            double tol = (Double) c.get("tol");
            Map<String, Object> got = computedValues(name, inputs, res);
            assertEquals(name + ": key sets differ", expect.keySet(), got.keySet());
            for (Map.Entry<String, Object> e : expect.entrySet()) {
                Object want = e.getValue();
                Object g = got.get(e.getKey());
                String label = name + "." + e.getKey();
                if (want instanceof String s) {
                    assertEquals(label, s, g);
                } else {
                    double wantD = (Double) want;
                    double gotD = (Double) g;
                    assertTrue(label + " not finite", Double.isFinite(gotD));
                    assertEquals(label + " (tol " + tol + ")", wantD, gotD, tol);
                }
            }
        }
    }

    @Test
    public void goldenSchemaFlatScalars() throws IOException {
        // cross-language contract: inputs/expect values are flat scalars only
        for (Object caseObj : loadCases()) {
            Map<String, Object> c = Json.asObject(caseObj, "case");
            for (String section : new String[] {"inputs", "expect"}) {
                for (Map.Entry<String, Object> e
                        : Json.asObject(c.get(section), section).entrySet()) {
                    Object v = e.getValue();
                    assertTrue(c.get("name") + "." + e.getKey(),
                            v instanceof Double || v instanceof String);
                }
            }
        }
    }
}
