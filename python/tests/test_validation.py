"""Validation framework: rule table firing, verdict mapping, data quality,
report generation (section-contains checks)."""
import math

import pytest

from frtb.validation import (REPORT_SECTIONS, DeskCheckInputs, Finding,
                             benchmark_max_diff, classify_findings,
                             data_quality, overall_verdict,
                             sensitivity_max_diff)


def inputs(**overrides) -> DeskCheckInputs:
    base = dict(benchmark_max_diff=0.001, sensitivity_max_diff=1e-9,
                stability_rel_change=0.05, backtest_zone="green",
                plat_zone="green", stale_days=0, gaps=0)
    base.update(overrides)
    return DeskCheckInputs(**base)


class TestFindingRules:
    def test_clean_desk_no_findings(self):
        assert classify_findings(inputs()) == []

    def test_each_rule_fires_on_constructed_failure(self):
        cases = [
            ({"benchmark_max_diff": 0.06}, "BENCH-01", "High"),
            ({"sensitivity_max_diff": 1e-3}, "SENS-01", "High"),
            ({"backtest_zone": "red"}, "BT-01", "High"),
            ({"backtest_zone": "amber"}, "BT-02", "Medium"),
            ({"plat_zone": "red"}, "PLAT-01", "High"),
            ({"plat_zone": "amber"}, "PLAT-02", "Medium"),
            ({"stability_rel_change": 0.30}, "STAB-01", "Medium"),
            ({"stale_days": 16}, "DQ-01", "Medium"),
            ({"gaps": 3}, "DQ-02", "Low"),
        ]
        for override, rule_id, severity in cases:
            found = classify_findings(inputs(**override))
            assert [f.rule_id for f in found] == [rule_id], override
            assert found[0].severity == severity

    def test_boundaries_do_not_fire(self):
        # thresholds are strict '>' comparisons
        assert classify_findings(inputs(benchmark_max_diff=0.05)) == []
        assert classify_findings(inputs(stale_days=15)) == []
        assert classify_findings(inputs(stability_rel_change=0.25)) == []


class TestVerdict:
    def test_verdict_rules(self):
        f = lambda sev: Finding(rule_id="X", severity=sev, description="d")
        assert overall_verdict([]) == "approve"
        assert overall_verdict([f("Low")]) == "approve"
        assert overall_verdict([f("Medium")]) == "approve-with-conditions"
        assert overall_verdict([f("Low"), f("Medium")]) == "approve-with-conditions"
        assert overall_verdict([f("Medium"), f("High")]) == "reject"

    def test_bad_severity_raises(self):
        with pytest.raises(ValueError, match="severity"):
            Finding(rule_id="X", severity="Critical", description="d")


class TestChecks:
    def test_benchmark_within_tolerance(self):
        d = benchmark_max_diff()
        assert 0.0 < d <= 0.05  # binomial(501) close to BS but not exact

    def test_sensitivity_check_tight(self):
        assert sensitivity_max_diff() <= 1e-6

    def test_data_quality_staleness_and_gaps(self):
        series = [1.0, 1.0, 1.0, 2.0, float("nan"), 3.0, 3.0]
        dq = data_quality(series)
        assert dq["stale_days"] == 3   # 1->1, 1->1, 3->3 (NaN excluded)
        assert dq["gaps"] == 1
        with pytest.raises(ValueError, match="at least 2"):
            data_quality([1.0])


class TestReportAndBundledVerdicts:
    def test_report_contains_all_sections(self, results):
        md = results["validation"]["report_md"]
        for section in REPORT_SECTIONS:
            assert f"## {section}" in md, section
        assert "Educational" in md  # non-compliance disclaimer

    def test_report_shows_desk_outcomes(self, results):
        md = results["validation"]["report_md"]
        assert "desk1: **approve**" in md
        assert "desk2: **approve-with-conditions**" in md
        assert "amber" in md and "green" in md

    def test_bundled_desk_findings(self, results):
        val = results["validation"]
        assert [f.rule_id for f in val["findings"]["desk1"]] == []
        assert {f.rule_id for f in val["findings"]["desk2"]} == {"BT-02", "PLAT-02"}
        assert val["verdicts"] == {"desk1": "approve",
                                   "desk2": "approve-with-conditions"}

    def test_bundled_plat_and_backtest_targets(self, results):
        ima = results["ima"]
        assert ima["desk1"]["plat"].zone == "green"
        assert ima["desk2"]["plat"].zone == "amber"
        assert ima["desk1"]["backtest"].zone == "green"
        assert ima["desk2"]["backtest"].exceptions == 5
        assert ima["desk2"]["backtest"].multiplier == 1.70
        assert ima["desk2"]["plat_surcharge"] > 0.0
        assert ima["desk1"]["plat_surcharge"] == 0.0
