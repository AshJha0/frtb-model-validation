package com.quant.frtb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * SBM aggregation: hand-computable example, scenarios, S_b fallback,
 * curvature psi rule, guards and error paths.
 */
public class SbmTest {

    // ---- hand-computable 2-bucket example (spec: tolerance 1e-12) ---------

    @Test
    public void bucketKbHandExample() {
        // 100 + 25 + 2*0.5*10*(-5) = 75
        double kA = Sbm.bucketKb(List.of(10.0, -5.0), (i, j) -> 0.5);
        assertEquals(Math.sqrt(75.0), kA, 1e-12);
    }

    @Test
    public void fullAggregationHandExample() {
        double kA = Sbm.bucketKb(List.of(10.0, -5.0), (i, j) -> 0.5);
        double kB = Sbm.bucketKb(List.of(8.0), (i, j) -> 0.0);
        Sbm.AggregateResult agg = Sbm.aggregateBuckets(
                Map.of("A", kA, "B", kB), Map.of("A", 5.0, "B", 8.0), (b, c) -> 0.25);
        assertEquals(Math.sqrt(159.0), agg.charge(), 1e-12);
        assertFalse(agg.usedFallback());
    }

    @Test
    public void handExampleViaDeltaVegaCharge() {
        Map<String, Map<String, Double>> ws = new LinkedHashMap<>();
        ws.put("A", new LinkedHashMap<>(Map.of("k1", 10.0, "k2", -5.0)));
        ws.put("B", new LinkedHashMap<>(Map.of("k1", 8.0)));
        Sbm.RiskClassCharge res = Sbm.deltaVegaCharge(ws,
                (b, k, l) -> "A".equals(b) ? 0.5 : 0.0, (b, c) -> 0.25, "medium", 1.25, 0.75);
        assertEquals(Math.sqrt(159.0), res.charge(), 1e-12);
        assertEquals(Math.sqrt(75.0), res.kb().get("A"), 1e-12);
        assertEquals(8.0, res.kb().get("B"), 1e-12);
    }

    // ---- correlation scenarios --------------------------------------------

    @Test
    public void highScalesAndCaps() {
        assertEquals(0.625, Sbm.scaleRho(0.5, "high"), 1e-15);
        assertEquals(1.0, Sbm.scaleRho(0.9, "high"), 0.0); // 1.125 capped at 1
        assertEquals(1.0, Sbm.scaleRho(0.97, "high"), 0.0);
    }

    @Test
    public void lowAndMediumScales() {
        assertEquals(0.375, Sbm.scaleRho(0.5, "low"), 1e-15);
        assertEquals(0.8, Sbm.scaleRho(0.8, "medium"), 0.0);
    }

    @Test
    public void unknownScenarioThrows() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Sbm.scaleRho(0.5, "extreme"));
        assertTrue(e.getMessage().contains("scenario"));
    }

    @Test
    public void highCapCollapsesToPerfectCorrelation() {
        // rho = 0.9 -> high scenario rho = 1.0 -> K_b = |ws1 + ws2| exactly
        Map<String, Map<String, Double>> ws = new LinkedHashMap<>();
        ws.put("A", new LinkedHashMap<>(Map.of("k1", 3.0, "k2", 4.0)));
        Sbm.RiskClassCharge res = Sbm.deltaVegaCharge(ws, (b, k, l) -> 0.9,
                (b, c) -> 0.0, "high", 1.25, 0.75);
        assertEquals(7.0, res.charge(), 1e-12);
    }

    @Test
    public void scenarioMonotonicitySameSignWs() {
        // same-sign WS: higher correlation -> higher charge (property loop)
        Map<String, Map<String, Double>> ws = new LinkedHashMap<>();
        ws.put("A", new LinkedHashMap<>(Map.of("k1", 3.0, "k2", 4.0)));
        Map<String, Double> charges = new LinkedHashMap<>();
        for (String s : Sbm.SCENARIOS) {
            charges.put(s, Sbm.deltaVegaCharge(ws, (b, k, l) -> 0.5, (b, c) -> 0.0,
                    s, 1.25, 0.75).charge());
        }
        assertTrue(charges.get("low") < charges.get("medium"));
        assertTrue(charges.get("medium") < charges.get("high"));
    }

    // ---- guards and the S_b fallback --------------------------------------

    @Test
    public void negativeRoundingGuard() {
        // quadratic form pushed (unphysically) below zero -> max(0, .) guard
        assertEquals(0.0, Sbm.bucketKb(List.of(1.0, 1.0), (i, j) -> -1.0000001), 0.0);
    }

    @Test
    public void sbFallbackTriggered() {
        // K = 5 both buckets, S = +7 / -7, gamma 0.9: 50 - 88.2 < 0 -> fallback
        Sbm.AggregateResult agg = Sbm.aggregateBuckets(
                Map.of("A", 5.0, "B", 5.0), Map.of("A", 7.0, "B", -7.0), (b, c) -> 0.9);
        assertTrue(agg.usedFallback());
        assertEquals(5.0, agg.sb().get("A"), 0.0);  // clamped to [-K_b, K_b]
        assertEquals(-5.0, agg.sb().get("B"), 0.0);
        assertEquals(Math.sqrt(5.0), agg.charge(), 1e-12);
    }

    @Test
    public void fallbackNotTriggeredWhenPositive() {
        Sbm.AggregateResult agg = Sbm.aggregateBuckets(
                Map.of("A", 5.0, "B", 5.0), Map.of("A", 7.0, "B", 7.0), (b, c) -> 0.9);
        assertFalse(agg.usedFallback());
    }

    @Test
    public void bucketMismatchThrows() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Sbm.aggregateBuckets(Map.of("A", 1.0), Map.of("B", 1.0), (b, c) -> 0.0));
        assertTrue(e.getMessage().contains("same buckets"));
    }

    @Test
    public void zeroSensitivitiesZeroCapital() {
        assertEquals(0.0, Sbm.deltaVegaCharge(Map.of(), (b, k, l) -> 0.5,
                (b, c) -> 0.25, "medium", 1.25, 0.75).charge(), 0.0);
        Map<String, Map<String, Double>> ws = new LinkedHashMap<>();
        ws.put("A", new LinkedHashMap<>(Map.of("k1", 0.0)));
        assertEquals(0.0, Sbm.deltaVegaCharge(ws, (b, k, l) -> 0.5,
                (b, c) -> 0.25, "medium", 1.25, 0.75).charge(), 0.0);
    }

    @Test
    public void nonFiniteWsThrows() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Sbm.bucketKb(List.of(1.0, Double.NaN), (i, j) -> 0.0));
        assertTrue(e.getMessage().contains("finite"));
    }

    // ---- curvature ---------------------------------------------------------

    @Test
    public void psiRule() {
        assertEquals(0.0, Sbm.psi(-1.0, -2.0), 0.0);
        assertEquals(1.0, Sbm.psi(-1.0, 2.0), 0.0);
        assertEquals(1.0, Sbm.psi(1.0, 2.0), 0.0);
        assertEquals(1.0, Sbm.psi(0.0, -1.0), 0.0); // zero is not negative
    }

    @Test
    public void curvatureBucketHandCase() {
        // CVR+ = [3, -1], curvature rho = 0.25 (= delta rho 0.5 squared):
        // K+^2 = 9 + max(-1,0)^2 + 2*0.25*3*(-1)*psi(3,-1) = 9 - 1.5 = 7.5
        // CVR- = [-2, -2]: all negative -> K- = 0.  K_b = sqrt(7.5), S_b = 2.
        Sbm.KbSb res = Sbm.curvatureBucketKb(List.of(3.0, -1.0), List.of(-2.0, -2.0),
                (i, j) -> 0.25);
        assertEquals(Math.sqrt(7.5), res.kb(), 1e-12);
        assertEquals(2.0, res.sb(), 1e-12);
    }

    @Test
    public void negativeGammaExposureAllNegativeCvr() {
        // long-option book: curvature benefit on both sides -> zero charge
        Sbm.KbSb res = Sbm.curvatureBucketKb(List.of(-4.0), List.of(-2.0), (i, j) -> 0.0);
        assertEquals(0.0, res.kb(), 0.0);
        assertEquals(-4.0, res.sb(), 0.0); // up side selected on tie (0 == 0)
    }

    @Test
    public void crossBucketPsiZeroesNegativePairs() {
        Map<String, List<List<Double>>> cvr = new LinkedHashMap<>();
        cvr.put("A", List.of(List.of(-1.0), List.of(2.0)));
        cvr.put("B", List.of(List.of(-3.0), List.of(1.5)));
        Map<String, List<String>> keys = Map.of("A", List.of("x"), "B", List.of("y"));
        Sbm.RiskClassCharge res = Sbm.curvatureCharge(cvr, (b, k, l) -> 0.5, (b, c) -> 0.8,
                "medium", keys, 1.25, 0.75);
        // K_A = 2 (down side, S_A = 2), K_B = 1.5 (down side, S_B = 1.5)
        // both S positive -> cross term active with gamma^2 = 0.64
        double expected = Math.sqrt(4.0 + 2.25 + 2 * 0.64 * 2.0 * 1.5);
        assertEquals(expected, res.charge(), 1e-12);

        Map<String, List<List<Double>>> cvr2 = new LinkedHashMap<>();
        cvr2.put("A", List.of(List.of(-1.0), List.of(-2.0)));
        cvr2.put("B", List.of(List.of(-3.0), List.of(-1.5)));
        Sbm.RiskClassCharge res2 = Sbm.curvatureCharge(cvr2, (b, k, l) -> 0.5, (b, c) -> 0.8,
                "medium", keys, 1.25, 0.75);
        assertEquals(0.0, res2.charge(), 0.0); // all CVRs negative everywhere
    }

    @Test
    public void curvatureEmpty() {
        Sbm.RiskClassCharge res = Sbm.curvatureCharge(Map.of(), (b, k, l) -> 0.0,
                (b, c) -> 0.0, "medium", Map.of(), 1.25, 0.75);
        assertEquals(0.0, res.charge(), 0.0);
    }

    @Test
    public void mismatchedCvrLengthsThrow() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Sbm.curvatureBucketKb(List.of(1.0), List.of(1.0, 2.0), (i, j) -> 0.0));
        assertTrue(e.getMessage().contains("up/down"));
    }
}
