package com.quant.frtb;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

/**
 * End-to-end FRTB demo: SBM capital breakdown per desk / risk class /
 * scenario, DRC-lite, RRAO, the IMA sketch (ES, IMCC, PLAT, backtesting,
 * SES) and the generated independent validation report.
 *
 * <p>Run: {@code cd java && ./demo.sh} (writes {@code java/validation_report.md}).
 */
public final class Demo {

    private Demo() {
    }

    private static String fmt(double x) {
        return String.format(Locale.US, "%,14.0f", x);
    }

    /**
     * Entry point; expects the bundled data directory at {@code ../data}
     * (or the path given as the first argument).
     *
     * @param args optional: data directory path
     * @throws IOException when the report file cannot be written
     */
    public static void main(String[] args) throws IOException {
        Path dataDir = args.length > 0 ? Paths.get(args[0]) : Paths.get("..", "data");
        Path reportPath = Paths.get("validation_report.md");

        System.out.println("=".repeat(76));
        System.out.println("FRTB & Model Validation demo  --  EDUCATIONAL parameter set "
                + "(Basel-2019-flavored)");
        System.out.println("=".repeat(76));
        Engine.Results res = Engine.computeResults(dataDir);

        // ---- SBM breakdown --------------------------------------------------
        for (String scope : List.of("desk1", "desk2", "firm")) {
            String label = res.desks.containsKey(scope)
                    ? res.desks.get(scope).display() : "FIRM (all desks)";
            System.out.println("\n--- SBM: " + scope + " (" + label + ") "
                    + "-".repeat(Math.max(0, 44 - scope.length() - label.length())));
            System.out.printf(Locale.US, "%-10s %-10s %14s %14s %14s%n",
                    "risk class", "measure", "high", "medium", "low");
            Sa.SbmResult s = res.sa.get(scope).sbm();
            for (String rc : Sa.RISK_CLASSES) {
                for (String m : Sa.MEASURES) {
                    var row = s.charges().get(rc).get(m);
                    System.out.printf(Locale.US, "%-10s %-10s %s %s %s%n", rc, m,
                            fmt(row.get("high")), fmt(row.get("medium")), fmt(row.get("low")));
                }
            }
            var st = s.scenarioTotals();
            System.out.printf(Locale.US, "%-10s %-10s %s %s %s%n", "TOTAL", "",
                    fmt(st.get("high")), fmt(st.get("medium")), fmt(st.get("low")));
            System.out.printf(Locale.US, "SBM capital (max over scenarios): %,.2f%n",
                    s.capital());
            System.out.printf(Locale.US, "DRC-lite: %,.2f   (HBR = %.4f)   RRAO: %,.2f%n",
                    res.sa.get(scope).drc(), res.sa.get(scope).drcHbr(),
                    res.sa.get(scope).rrao());
            System.out.printf(Locale.US, "SA capital (SBM + DRC + RRAO):    %,.2f%n",
                    res.sa.get(scope).capital());
        }

        // ---- IMA sketch -----------------------------------------------------
        System.out.println("\n--- IMA sketch (per desk) " + "-".repeat(49));
        System.out.printf(Locale.US,
                "%-7s %12s %12s %12s %4s %6s %5s %6s %10s %10s %12s%n",
                "desk", "ES base10d", "ES LH", "IMCC", "exc", "zone", "mult", "PLAT",
                "SES", "surchg", "capital");
        for (String d : List.of("desk1", "desk2")) {
            Engine.DeskIma i = res.ima.get(d);
            Ima.BacktestResult bt = i.backtest();
            Plat.PlatResult pl = i.plat();
            System.out.printf(Locale.US,
                    "%-7s %,12.0f %,12.0f %,12.0f %4d %6s %5.2f %6s %,10.0f %,10.0f %,12.0f%n",
                    d, i.esBase(), i.esLh(), i.imcc(), bt.exceptions(), bt.zone(),
                    bt.multiplier(), pl.zone(), i.ses(), i.platSurcharge(), i.capital());
            String sp = pl.spearman() == null ? "n/a"
                    : String.format(Locale.US, "%.4f", pl.spearman());
            String ks = pl.ks() == null ? "n/a" : String.format(Locale.US, "%.4f", pl.ks());
            System.out.println("        PLAT metrics: spearman = " + sp + ", KS = " + ks);
        }

        // ---- validation -----------------------------------------------------
        System.out.println("\n--- Independent validation " + "-".repeat(48));
        System.out.printf(Locale.US,
                "benchmark BS vs binomial(501): max diff = %.3e (tol 0.05)%n",
                res.validation.benchmarkMaxDiff());
        System.out.printf(Locale.US,
                "delta vs finite difference:    max diff = %.3e (tol 1e-06)%n",
                res.validation.sensitivityMaxDiff());
        System.out.printf(Locale.US,
                "stability: capital %,.0f -> %,.0f under +10%% GIRR RW (%.2f%% max move)%n",
                res.validation.stabilityBaseCapital(),
                res.validation.stabilityCapitalRwUp10(),
                res.validation.stabilityRelChange() * 100.0);
        for (String d : List.of("desk1", "desk2")) {
            List<Validation.Finding> fs = res.validation.findings().get(d);
            String rules = fs.isEmpty() ? "none"
                    : String.join(", ", fs.stream().map(Validation.Finding::ruleId).toList());
            System.out.println(d + ": findings = " + rules + "  ->  verdict: "
                    + res.validation.verdicts().get(d).toUpperCase(Locale.US));
        }

        Files.writeString(reportPath, res.reportMd);
        System.out.println("\nvalidation_report.md written to " + reportPath.toAbsolutePath());
        System.out.println("Overall verdicts: desk1=" + res.validation.verdicts().get("desk1")
                + ", desk2=" + res.validation.verdicts().get("desk2"));
    }
}
