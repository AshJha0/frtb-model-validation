package com.quant.frtb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Validation framework: rule table firing, verdict mapping, data quality,
 * report generation (section-contains checks).
 */
public class ValidationTest {

    private static Validation.DeskCheckInputs inputs(double bench, double sens, double stab,
                                                     String btZone, String platZone,
                                                     int stale, int gaps) {
        return new Validation.DeskCheckInputs(bench, sens, stab, btZone, platZone, stale, gaps);
    }

    private static Validation.DeskCheckInputs clean() {
        return inputs(0.001, 1e-9, 0.05, "green", "green", 0, 0);
    }

    // ---- finding rules -----------------------------------------------------

    @Test
    public void cleanDeskNoFindings() {
        assertTrue(Validation.classifyFindings(clean()).isEmpty());
    }

    @Test
    public void eachRuleFiresOnConstructedFailure() {
        record Case(Validation.DeskCheckInputs in, String ruleId, String severity) {
        }
        List<Case> cases = List.of(
                new Case(inputs(0.06, 1e-9, 0.05, "green", "green", 0, 0), "BENCH-01", "High"),
                new Case(inputs(0.001, 1e-3, 0.05, "green", "green", 0, 0), "SENS-01", "High"),
                new Case(inputs(0.001, 1e-9, 0.05, "red", "green", 0, 0), "BT-01", "High"),
                new Case(inputs(0.001, 1e-9, 0.05, "amber", "green", 0, 0), "BT-02", "Medium"),
                new Case(inputs(0.001, 1e-9, 0.05, "green", "red", 0, 0), "PLAT-01", "High"),
                new Case(inputs(0.001, 1e-9, 0.05, "green", "amber", 0, 0), "PLAT-02", "Medium"),
                new Case(inputs(0.001, 1e-9, 0.30, "green", "green", 0, 0), "STAB-01", "Medium"),
                new Case(inputs(0.001, 1e-9, 0.05, "green", "green", 16, 0), "DQ-01", "Medium"),
                new Case(inputs(0.001, 1e-9, 0.05, "green", "green", 0, 3), "DQ-02", "Low"));
        for (Case c : cases) {
            List<Validation.Finding> found = Validation.classifyFindings(c.in());
            assertEquals(c.ruleId(), 1, found.size());
            assertEquals(c.ruleId(), found.get(0).ruleId());
            assertEquals(c.severity(), found.get(0).severity());
        }
    }

    @Test
    public void boundariesDoNotFire() {
        // thresholds are strict '>' comparisons
        assertTrue(Validation.classifyFindings(
                inputs(0.05, 1e-9, 0.05, "green", "green", 0, 0)).isEmpty());
        assertTrue(Validation.classifyFindings(
                inputs(0.001, 1e-9, 0.05, "green", "green", 15, 0)).isEmpty());
        assertTrue(Validation.classifyFindings(
                inputs(0.001, 1e-9, 0.25, "green", "green", 0, 0)).isEmpty());
    }

    // ---- verdicts ----------------------------------------------------------

    @Test
    public void verdictRules() {
        assertEquals("approve", Validation.overallVerdict(List.of()));
        assertEquals("approve", Validation.overallVerdict(List.of(finding("Low"))));
        assertEquals("approve-with-conditions",
                Validation.overallVerdict(List.of(finding("Medium"))));
        assertEquals("approve-with-conditions",
                Validation.overallVerdict(List.of(finding("Low"), finding("Medium"))));
        assertEquals("reject",
                Validation.overallVerdict(List.of(finding("Medium"), finding("High"))));
    }

    private static Validation.Finding finding(String severity) {
        return new Validation.Finding("X", severity, "d");
    }

    @Test
    public void badSeverityThrows() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new Validation.Finding("X", "Critical", "d"))
                .getMessage().contains("severity"));
    }

    // ---- checks ------------------------------------------------------------

    @Test
    public void benchmarkWithinTolerance() {
        double d = Validation.benchmarkMaxDiff();
        assertTrue("binomial(501) close to BS but not exact", d > 0.0 && d <= 0.05);
    }

    @Test
    public void sensitivityCheckTight() {
        assertTrue(Validation.sensitivityMaxDiff() <= 1e-6);
    }

    @Test
    public void dataQualityStalenessAndGaps() {
        double[] series = {1.0, 1.0, 1.0, 2.0, Double.NaN, 3.0, 3.0};
        Validation.DataQuality dq = Validation.dataQuality(series);
        assertEquals(3, dq.staleDays()); // 1->1, 1->1, 3->3 (NaN excluded)
        assertEquals(1, dq.gaps());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Validation.dataQuality(new double[] {1.0}))
                .getMessage().contains("at least 2"));
    }

    // ---- report and bundled verdicts ---------------------------------------

    @Test
    public void reportContainsAllSections() {
        String md = TestData.results().reportMd;
        for (String section : Validation.REPORT_SECTIONS) {
            assertTrue("missing section: " + section, md.contains("## " + section));
        }
        assertTrue("missing non-compliance disclaimer", md.contains("Educational"));
    }

    @Test
    public void reportShowsDeskOutcomes() {
        String md = TestData.results().reportMd;
        assertTrue(md.contains("desk1: **approve**"));
        assertTrue(md.contains("desk2: **approve-with-conditions**"));
        assertTrue(md.contains("amber"));
        assertTrue(md.contains("green"));
    }

    @Test
    public void bundledDeskFindings() {
        Engine.ValidationResults val = TestData.results().validation;
        assertTrue(val.findings().get("desk1").isEmpty());
        assertEquals(List.of("BT-02", "PLAT-02"),
                val.findings().get("desk2").stream().map(Validation.Finding::ruleId).toList());
        assertEquals(Map.of("desk1", "approve", "desk2", "approve-with-conditions"),
                val.verdicts());
    }

    @Test
    public void bundledPlatAndBacktestTargets() {
        Map<String, Engine.DeskIma> ima = TestData.results().ima;
        assertEquals("green", ima.get("desk1").plat().zone());
        assertEquals("amber", ima.get("desk2").plat().zone());
        assertEquals("green", ima.get("desk1").backtest().zone());
        assertEquals(5, ima.get("desk2").backtest().exceptions());
        assertEquals(1.70, ima.get("desk2").backtest().multiplier(), 0.0);
        assertTrue(ima.get("desk2").platSurcharge() > 0.0);
        assertEquals(0.0, ima.get("desk1").platSurcharge(), 0.0);
    }
}
