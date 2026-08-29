package com.quant.frtb;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Independent model validation framework.
 *
 * <p>Checks (all pinned):
 * <ul>
 *   <li>Benchmarking — project BS pricer vs an independent CRR binomial
 *       lattice (501 steps) on a pinned strike/maturity/call-put grid;
 *       PASS iff max abs price diff &le; 0.05.</li>
 *   <li>Sensitivity — analytic BS delta vs central finite difference
 *       (h = 1e-4*S) on the same grid; PASS iff max diff &le; 1e-6.</li>
 *   <li>Stability — SBM capital recomputed with GIRR delta RWs x0.9 / x1.1;
 *       finding if |delta capital| / base capital &gt; 0.25.</li>
 *   <li>Backtesting / PLAT — desk zones from the IMA sketch.</li>
 *   <li>Data quality — staleness (&gt; 15 zero-change days) and gaps (any NaN).</li>
 * </ul>
 *
 * <p>Findings classification (pinned rule table): any High → verdict
 * "reject"; else any Medium → "approve-with-conditions"; else "approve".
 */
public final class Validation {

    private Validation() {
    }

    // ---- pinned check parameters -----------------------------------------
    /** Binomial benchmark lattice steps. */
    public static final int BENCH_STEPS = 501;
    /** Benchmark pass threshold on max |BS - binomial|. */
    public static final double BENCH_TOL = 0.05;
    /** Delta finite-difference pass threshold. */
    public static final double SENS_TOL = 1e-6;
    /** Capital stability threshold on |delta capital| / capital. */
    public static final double STABILITY_THRESHOLD = 0.25;
    /** Staleness threshold in zero-change days. */
    public static final int STALENESS_THRESHOLD = 15;

    private static final double[] BENCH_GRID_STRIKES = {70.0, 85.0, 100.0, 115.0, 130.0};
    private static final double[] BENCH_GRID_MATURITIES = {0.25, 0.5, 1.0, 2.0};
    private static final double BENCH_SPOT = 100.0;
    private static final double BENCH_RATE = 0.03;
    private static final double BENCH_DIV = 0.01;
    private static final double BENCH_VOL = 0.2;

    private static final Set<String> SEVERITIES = Set.of("High", "Medium", "Low");

    /** The ten pinned report section titles, in order. */
    public static final List<String> REPORT_SECTIONS = List.of(
            "1. Scope & Overview",
            "2. Pricing Benchmark",
            "3. Sensitivity Verification",
            "4. Capital Stability",
            "5. VaR Backtesting",
            "6. P&L Attribution (PLAT)",
            "7. Data Quality",
            "8. NMRF / SES",
            "9. Findings",
            "10. Overall Verdict");

    /**
     * One validation finding: pinned rule id, severity, human description.
     *
     * @param ruleId      pinned rule id, e.g. "BENCH-01"
     * @param severity    "High" | "Medium" | "Low"
     * @param description human-readable description
     */
    public record Finding(String ruleId, String severity, String description) {
        public Finding {
            if (!SEVERITIES.contains(severity)) {
                throw new IllegalArgumentException(
                        "Finding: severity must be one of " + SEVERITIES);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Checks
    // ---------------------------------------------------------------------

    /** Max abs diff |BS - binomial(501)| over the pinned option grid. */
    public static double benchmarkMaxDiff() {
        double worst = 0.0;
        for (double k : BENCH_GRID_STRIKES) {
            for (double t : BENCH_GRID_MATURITIES) {
                for (boolean call : new boolean[] {true, false}) {
                    double a = Pricers.bsPrice(BENCH_SPOT, k, t, BENCH_RATE, BENCH_DIV,
                            BENCH_VOL, call);
                    double b = Pricers.binomialPrice(BENCH_SPOT, k, t, BENCH_RATE, BENCH_DIV,
                            BENCH_VOL, call, BENCH_STEPS);
                    worst = Math.max(worst, Math.abs(a - b));
                }
            }
        }
        return worst;
    }

    /** Max abs diff between analytic BS delta and a central finite difference. */
    public static double sensitivityMaxDiff() {
        double h = 1e-4 * BENCH_SPOT;
        double worst = 0.0;
        for (double k : BENCH_GRID_STRIKES) {
            for (double t : BENCH_GRID_MATURITIES) {
                for (boolean call : new boolean[] {true, false}) {
                    double analytic = Pricers.bsDelta(BENCH_SPOT, k, t, BENCH_RATE, BENCH_DIV,
                            BENCH_VOL, call);
                    double up = Pricers.bsPrice(BENCH_SPOT + h, k, t, BENCH_RATE, BENCH_DIV,
                            BENCH_VOL, call);
                    double dn = Pricers.bsPrice(BENCH_SPOT - h, k, t, BENCH_RATE, BENCH_DIV,
                            BENCH_VOL, call);
                    worst = Math.max(worst, Math.abs(analytic - (up - dn) / (2.0 * h)));
                }
            }
        }
        return worst;
    }

    /**
     * Data-quality stats of one series.
     *
     * @param staleDays number of zero-change days (NaNs excluded)
     * @param gaps      number of missing (NaN) values
     */
    public record DataQuality(int staleDays, int gaps) {
    }

    /** Staleness (# zero-change days) and gaps (# NaN values) of one series. */
    public static DataQuality dataQuality(double[] series) {
        if (series.length < 2) {
            throw new IllegalArgumentException("dataQuality: need at least 2 observations");
        }
        int gaps = 0;
        List<Double> clean = new ArrayList<>();
        for (double v : series) {
            if (Double.isNaN(v)) {
                gaps++;
            } else {
                clean.add(v);
            }
        }
        int stale = 0;
        for (int i = 1; i < clean.size(); i++) {
            if (clean.get(i) - clean.get(i - 1) == 0.0) {
                stale++;
            }
        }
        return new DataQuality(stale, gaps);
    }

    // ---------------------------------------------------------------------
    // Findings classification (pinned rule table)
    // ---------------------------------------------------------------------

    /**
     * Everything the pinned rule table needs to classify one desk.
     *
     * @param benchmarkMaxDiff   max |BS - binomial| over the pinned grid
     * @param sensitivityMaxDiff max |analytic - FD| delta over the grid
     * @param stabilityRelChange max(|dCap x1.1|, |dCap x0.9|) / base capital
     * @param backtestZone       the desk's VaR backtest zone
     * @param platZone           the desk's PLAT zone
     * @param staleDays          zero-change days in the desk P&amp;L
     * @param gaps               missing values in the desk P&amp;L
     */
    public record DeskCheckInputs(double benchmarkMaxDiff, double sensitivityMaxDiff,
                                  double stabilityRelChange, String backtestZone,
                                  String platZone, int staleDays, int gaps) {
    }

    /** Apply the pinned rule table; returns findings in table order. */
    public static List<Finding> classifyFindings(DeskCheckInputs c) {
        List<Finding> out = new ArrayList<>();
        if (c.benchmarkMaxDiff() > BENCH_TOL) {
            out.add(new Finding("BENCH-01", "High", "Pricing benchmark max diff "
                    + g6(c.benchmarkMaxDiff()) + " exceeds tolerance " + BENCH_TOL));
        }
        if (c.sensitivityMaxDiff() > SENS_TOL) {
            out.add(new Finding("SENS-01", "High", "Analytic vs FD delta max diff "
                    + g6(c.sensitivityMaxDiff()) + " exceeds " + SENS_TOL));
        }
        if ("red".equals(c.backtestZone())) {
            out.add(new Finding("BT-01", "High", "VaR backtest in RED zone"));
        }
        if ("amber".equals(c.backtestZone())) {
            out.add(new Finding("BT-02", "Medium", "VaR backtest in AMBER zone"));
        }
        if ("red".equals(c.platZone())) {
            out.add(new Finding("PLAT-01", "High", "PLAT in RED zone"));
        }
        if ("amber".equals(c.platZone())) {
            out.add(new Finding("PLAT-02", "Medium", "PLAT in AMBER zone"));
        }
        if (c.stabilityRelChange() > STABILITY_THRESHOLD) {
            out.add(new Finding("STAB-01", "Medium", String.format(Locale.US,
                    "Capital moves %.1f%% under +/-10%% GIRR RW (threshold %.0f%%)",
                    c.stabilityRelChange() * 100.0, STABILITY_THRESHOLD * 100.0)));
        }
        if (c.staleDays() > STALENESS_THRESHOLD) {
            out.add(new Finding("DQ-01", "Medium", c.staleDays()
                    + " zero-change days exceed staleness threshold " + STALENESS_THRESHOLD));
        }
        if (c.gaps() > 0) {
            out.add(new Finding("DQ-02", "Low",
                    c.gaps() + " missing values in the P&L series"));
        }
        return out;
    }

    /** Pinned verdict rule: any High → reject; any Medium → approve-with-conditions. */
    public static String overallVerdict(List<Finding> findings) {
        boolean high = false;
        boolean medium = false;
        for (Finding f : findings) {
            high |= "High".equals(f.severity());
            medium |= "Medium".equals(f.severity());
        }
        if (high) {
            return "reject";
        }
        if (medium) {
            return "approve-with-conditions";
        }
        return "approve";
    }

    // ---------------------------------------------------------------------
    // Report generation (structured results -> markdown)
    // ---------------------------------------------------------------------

    private static String fmt(double x) {
        return String.format(Locale.US, "%,.2f", x);
    }

    /** Python-style {@code %.6g} (trailing zeros stripped). */
    static String g6(double v) {
        String s = String.format(Locale.US, "%.6g", v);
        String exp = "";
        int e = s.indexOf('e');
        if (e >= 0) {
            exp = s.substring(e);
            s = s.substring(0, e);
        }
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return s + exp;
    }

    /**
     * Render the validation report markdown from the engine's results.
     * Always emits every section in {@link #REPORT_SECTIONS} (tested by
     * string-contains checks).
     */
    public static String renderReport(Map<String, Engine.DeskIma> ima,
                                      Engine.ValidationResults val) {
        List<String> desks = new ArrayList<>(new TreeSet<>(ima.keySet()));
        StringBuilder sb = new StringBuilder();

        sb.append("# Independent Model Validation Report\n\n");
        sb.append("> Educational FRTB implementation — Basel-2019-flavored pinned parameter set.\n");
        sb.append("> NOT a compliant capital engine; for teaching and testing only.\n\n");
        sb.append("## ").append(REPORT_SECTIONS.get(0)).append("\n\n");
        sb.append("Desks in scope: ").append(String.join(", ", desks))
                .append(". Framework: SBM + DRC + RRAO (SA) and ")
                .append("ES/IMCC + PLAT + backtesting + SES (IMA sketch).\n\n");

        sb.append("## ").append(REPORT_SECTIONS.get(1)).append("\n\n");
        sb.append("| metric | value | threshold | result |\n");
        sb.append("|---|---|---|---|\n");
        double bmd = val.benchmarkMaxDiff();
        sb.append(String.format(Locale.US,
                "| max abs diff BS vs binomial(%d) | %.3e | %s | %s |\n",
                BENCH_STEPS, bmd, BENCH_TOL, bmd <= BENCH_TOL ? "PASS" : "FAIL"));
        sb.append("\n");

        sb.append("## ").append(REPORT_SECTIONS.get(2)).append("\n\n");
        double smd = val.sensitivityMaxDiff();
        sb.append(String.format(Locale.US,
                "Analytic BS delta vs central finite difference: max abs diff %.3e "
                        + "(threshold %.0e) — %s.\n\n",
                smd, SENS_TOL, smd <= SENS_TOL ? "PASS" : "FAIL"));

        sb.append("## ").append(REPORT_SECTIONS.get(3)).append("\n\n");
        sb.append("| scenario | SBM capital | change vs base |\n");
        sb.append("|---|---|---|\n");
        double baseCap = val.stabilityBaseCapital();
        double upCap = val.stabilityCapitalRwUp10();
        double dnCap = val.stabilityCapitalRwDn10();
        sb.append("| base | ").append(fmt(baseCap)).append(" | — |\n");
        sb.append("| GIRR delta RW x1.1 | ").append(fmt(upCap)).append(" | ")
                .append(fmt(upCap - baseCap)).append(" |\n");
        sb.append("| GIRR delta RW x0.9 | ").append(fmt(dnCap)).append(" | ")
                .append(fmt(dnCap - baseCap)).append(" |\n\n");

        sb.append("## ").append(REPORT_SECTIONS.get(4)).append("\n\n");
        sb.append("| desk | exceptions | zone | multiplier |\n");
        sb.append("|---|---|---|---|\n");
        for (String d : desks) {
            Ima.BacktestResult bt = ima.get(d).backtest();
            sb.append(String.format(Locale.US, "| %s | %d | %s | %.2f |\n",
                    d, bt.exceptions(), bt.zone(), bt.multiplier()));
        }
        sb.append("\n");

        sb.append("## ").append(REPORT_SECTIONS.get(5)).append("\n\n");
        sb.append("| desk | spearman | KS | zone | surcharge |\n");
        sb.append("|---|---|---|---|---|\n");
        for (String d : desks) {
            Plat.PlatResult pl = ima.get(d).plat();
            String sp = pl.spearman() == null ? "n/a"
                    : String.format(Locale.US, "%.4f", pl.spearman());
            String ks = pl.ks() == null ? "n/a"
                    : String.format(Locale.US, "%.4f", pl.ks());
            sb.append("| ").append(d).append(" | ").append(sp).append(" | ").append(ks)
                    .append(" | ").append(pl.zone()).append(" | ")
                    .append(fmt(ima.get(d).platSurcharge())).append(" |\n");
        }
        sb.append("\n");

        sb.append("## ").append(REPORT_SECTIONS.get(6)).append("\n\n");
        sb.append("| desk | zero-change days | gaps |\n");
        sb.append("|---|---|---|\n");
        for (String d : desks) {
            DataQuality dq = val.dataQuality().get(d);
            sb.append("| ").append(d).append(" | ").append(dq.staleDays()).append(" | ")
                    .append(dq.gaps()).append(" |\n");
        }
        sb.append("\n");

        sb.append("## ").append(REPORT_SECTIONS.get(7)).append("\n\n");
        sb.append("| desk | SES |\n");
        sb.append("|---|---|\n");
        for (String d : desks) {
            sb.append("| ").append(d).append(" | ").append(fmt(ima.get(d).ses())).append(" |\n");
        }
        sb.append("\n");

        sb.append("## ").append(REPORT_SECTIONS.get(8)).append("\n\n");
        boolean anyFinding = false;
        for (String d : desks) {
            for (Finding f : val.findings().get(d)) {
                sb.append("- **").append(f.severity()).append("** [").append(f.ruleId())
                        .append("] (").append(d).append("): ").append(f.description())
                        .append("\n");
                anyFinding = true;
            }
        }
        if (!anyFinding) {
            sb.append("- No findings.\n");
        }
        sb.append("\n");

        sb.append("## ").append(REPORT_SECTIONS.get(9)).append("\n\n");
        for (String d : desks) {
            sb.append("- ").append(d).append(": **").append(val.verdicts().get(d))
                    .append("**\n");
        }
        return sb.toString();
    }
}
