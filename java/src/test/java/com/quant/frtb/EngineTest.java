package com.quant.frtb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

/**
 * Bump-and-revalue sensitivities, empty-desk edge cases, engine wiring and
 * runtime determinism (bit-identical repeated runs, no RNG anywhere).
 */
public class EngineTest {

    // ---- GIRR sensitivities ------------------------------------------------

    @Test
    public void bondDv01NegativeAndLocalised() {
        // Long bond: rates up -> price down; sensitivity sits at coupon tenors.
        Engine.Results res = TestData.results();
        Bond bond = new Bond("b", 1e7, 0.03, 5.0, "USD", "X", "AAA", 0.75, null);
        Sensitivities sens = Sensitivities.compute(List.of(bond), res.market, res.params);
        Map<Double, Double> girr = sens.girr().get("USD");
        assertTrue("principal + last coupon", girr.get(5.0) < 0.0);
        assertEquals("beyond maturity", 0.0, girr.get(30.0), 1e-4);
        assertFalse("all-zero currency dropped", sens.girr().containsKey("EUR"));
    }

    @Test
    public void payerSwapPositiveRateSensitivity() {
        Engine.Results res = TestData.results();
        PayerSwap swap = new PayerSwap("s", 2e7, 0.028, 10.0, "USD", null);
        Sensitivities sens = Sensitivities.compute(List.of(swap), res.market, res.params);
        double total = 0.0;
        for (double s : sens.girr().get("USD").values()) {
            total += s;
        }
        assertTrue("payer gains when rates rise", total > 0.0);
    }

    // ---- equity / FX sensitivities ----------------------------------------

    @Test
    public void equityDeltaCloseToAnalytic() {
        Engine.Results res = TestData.results();
        EquityOption opt = new EquityOption("o", "AAA_TECH", "call", 1, 1000.0, 105.0, 1.0,
                "USD", null);
        Market.EquityQuote q = res.market.equity("AAA_TECH");
        double r = res.market.curve("USD").rate(1.0);
        Sensitivities sens = Sensitivities.compute(List.of(opt), res.market, res.params);
        double analytic = q.spot()
                * Pricers.bsDelta(q.spot(), 105.0, 1.0, r, q.divYield(), q.vol(), true) * 1000.0;
        // 1% relative bump: forward-difference convexity error is O(bump)
        assertEquals(analytic, sens.equityDelta().get("AAA_TECH"), Math.abs(analytic) * 0.05);
        double analyticVega = Pricers.bsVega(q.spot(), 105.0, 1.0, r, q.divYield(), q.vol())
                * q.vol() * 1000.0;
        assertEquals(analyticVega, sens.equityVega().get("AAA_TECH"),
                Math.abs(analyticVega) * 0.05);
    }

    @Test
    public void fxForwardDeltaExact() {
        // Linear payoff: relative-bump delta is exact: s = N * S * DF_for(T).
        Engine.Results res = TestData.results();
        FxForward fwd = new FxForward("f", "EURUSD", 1.5e7, 1.10, 1.0, null);
        Sensitivities sens = Sensitivities.compute(List.of(fwd), res.market, res.params);
        double want = 1.5e7 * res.market.fxSpot("EURUSD") * res.market.curve("EUR").df(1.0);
        assertEquals(want, sens.fxDelta().get("EURUSD"), Math.abs(want) * 1e-10);
        // linear payoff -> zero curvature (CVR identically 0)
        double[] cvr = sens.fxCvr().get("EURUSD");
        assertEquals(0.0, cvr[0], 1e-4);
        assertEquals(0.0, cvr[1], 1e-4);
    }

    @Test
    public void shortOptionNegativeGammaPositiveCvr() {
        // Short call (negative gamma): curvature CVR must be a positive loss.
        Engine.Results res = TestData.results();
        EquityOption opt = new EquityOption("o", "GLOBAL_INDEX", "call", -1, 30000.0, 260.0,
                0.75, "USD", null);
        Sensitivities sens = Sensitivities.compute(List.of(opt), res.market, res.params);
        double[] cvr = sens.equityCvr().get("GLOBAL_INDEX");
        assertTrue(cvr[0] > 0.0);
        assertTrue(cvr[1] > 0.0);
    }

    @Test
    public void longOptionNegativeCvr() {
        Engine.Results res = TestData.results();
        EquityOption opt = new EquityOption("o", "AAA_TECH", "call", 1, 1000.0, 105.0, 1.0,
                "USD", null);
        Sensitivities sens = Sensitivities.compute(List.of(opt), res.market, res.params);
        double[] cvr = sens.equityCvr().get("AAA_TECH");
        assertTrue("convexity benefit both ways", cvr[0] < 0.0 && cvr[1] < 0.0);
    }

    // ---- empty desk and errors ---------------------------------------------

    @Test
    public void emptyDeskAllZero() {
        Engine.Results res = TestData.results();
        Engine.SaScope sa = Engine.computeSa(List.of(), res.market, res.params);
        assertEquals(0.0, sa.sbm().capital(), 0.0);
        assertEquals(0.0, sa.drc(), 0.0);
        assertEquals(0.0, sa.rrao(), 0.0);
        assertEquals(0.0, sa.capital(), 0.0);
        for (double total : sa.sbm().scenarioTotals().values()) {
            assertEquals(0.0, total, 0.0);
        }
    }

    @Test
    public void missingEquityBucketParamThrows() {
        // Sensitivity mapped to a bucket absent from the pinned set -> error.
        Engine.Results res = TestData.results();
        Market bad = new Market(res.market.curves(),
                Map.of("ROGUE", new Market.EquityQuote(50.0, 0.2, 0.0, "99")),
                res.market.fx());
        EquityOption opt = new EquityOption("o", "ROGUE", "call", 1, 100.0, 50.0, 1.0,
                "USD", null);
        // the bump-and-revalue pass already needs the curvature RW -> raises there
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Sensitivities.compute(List.of(opt), bad, res.params));
        assertTrue(e.getMessage().contains("unknown equity bucket '99'"));
    }

    @Test
    public void deskCategoriesExtraction() {
        Map<String, double[]> hypo = new LinkedHashMap<>();
        hypo.put("desk1", new double[] {1.0});
        hypo.put("desk1_ir", new double[] {1.0});
        hypo.put("desk2", new double[] {2.0});
        hypo.put("desk2_eq", new double[] {1.5});
        hypo.put("desk2_fx", new double[] {0.5});
        assertEquals(Set.of("ir"), Engine.deskCategories("desk1", hypo).keySet());
        assertEquals(Set.of("eq", "fx"), Engine.deskCategories("desk2", hypo).keySet());
    }

    // ---- portfolio-level structure -----------------------------------------

    @Test
    public void firmCapitalPositiveAndScenariosReported() {
        Engine.SaScope sa = TestData.results().sa.get("firm");
        assertTrue(sa.sbm().capital() > 0.0);
        assertEquals(Set.of("high", "medium", "low"), sa.sbm().scenarioTotals().keySet());
        double max = sa.sbm().scenarioTotals().values().stream()
                .mapToDouble(Double::doubleValue).max().orElseThrow();
        assertEquals(max, sa.sbm().capital(), 0.0);
    }

    @Test
    public void girrVegaZeroNoIrOptions() {
        // No IR-vol instruments in scope -> GIRR vega charge exactly 0.
        for (String scen : Sbm.SCENARIOS) {
            assertEquals(0.0, TestData.results().sa.get("firm").sbm()
                    .charges().get("girr").get("vega").get(scen), 0.0);
        }
    }

    @Test
    public void deskHbrAllLong() {
        // bundled portfolio holds only long bonds -> HBR = 1 on every scope
        for (String scope : new String[] {"desk1", "desk2", "firm"}) {
            assertEquals(1.0, TestData.results().sa.get(scope).drcHbr(), 0.0);
        }
    }

    // ---- determinism -------------------------------------------------------

    @Test
    public void repeatedRunsBitIdentical() {
        // A second full engine run must reproduce every number exactly
        // (no RNG anywhere at runtime — spec requirement).
        Engine.Results first = TestData.results();
        Engine.Results again = Engine.computeResults(TestData.DATA_DIR);
        for (String scope : new String[] {"desk1", "desk2", "firm"}) {
            assertEquals(first.sa.get(scope).sbm().capital(),
                    again.sa.get(scope).sbm().capital(), 0.0);
            assertEquals(first.sa.get(scope).sbm().scenarioTotals(),
                    again.sa.get(scope).sbm().scenarioTotals());
            assertEquals(first.sa.get(scope).drc(), again.sa.get(scope).drc(), 0.0);
            assertEquals(first.sa.get(scope).rrao(), again.sa.get(scope).rrao(), 0.0);
        }
        for (String desk : new String[] {"desk1", "desk2"}) {
            assertEquals(first.ima.get(desk).esBase(), again.ima.get(desk).esBase(), 0.0);
            assertEquals(first.ima.get(desk).esLh(), again.ima.get(desk).esLh(), 0.0);
            assertEquals(first.ima.get(desk).imcc(), again.ima.get(desk).imcc(), 0.0);
            assertEquals(first.ima.get(desk).ses(), again.ima.get(desk).ses(), 0.0);
            assertEquals(first.ima.get(desk).capital(), again.ima.get(desk).capital(), 0.0);
            assertEquals(first.ima.get(desk).plat(), again.ima.get(desk).plat());
            assertEquals(first.ima.get(desk).backtest(), again.ima.get(desk).backtest());
        }
        assertEquals(first.reportMd, again.reportMd);
    }

    @Test
    public void pnlCsvLoadingErrors() {
        assertThrows(RuntimeException.class,
                () -> Engine.loadPnlCsv(TestData.DATA_DIR.resolve("no_such_file.csv")));
    }
}
