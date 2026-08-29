package com.quant.frtb;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * End-to-end orchestration: load the bundled data set, compute SA (SBM +
 * DRC + RRAO), the IMA sketch (ES/IMCC, backtesting, PLAT, SES) and the
 * independent validation results for every desk and for the firm.
 *
 * <p>Fully deterministic: pure revaluation and closed-form statistics, no RNG.
 */
public final class Engine {

    private Engine() {
    }

    /**
     * SA results for one scope (a desk or the whole firm).
     *
     * @param sbm    SBM drill-down
     * @param drc    DRC-lite charge
     * @param drcHbr hedge benefit ratio
     * @param rrao   residual risk add-on
     */
    public record SaScope(Sa.SbmResult sbm, double drc, double drcHbr, double rrao) {

        /** SA capital = SBM + DRC + RRAO. */
        public double capital() {
            return sbm.capital() + drc + rrao;
        }
    }

    /**
     * IMA sketch results for one desk.
     *
     * @param esBase        base 10d ES
     * @param esLh          liquidity-horizon-scaled ES
     * @param imcc          IMCC blend
     * @param backtest      VaR backtest outcome
     * @param plat          PLAT outcome
     * @param ses           NMRF stress capital for the desk
     * @param capitalCore   multiplier * IMCC + SES
     * @param platSurcharge amber-zone surcharge
     * @param capital       core + surcharge
     */
    public record DeskIma(double esBase, double esLh, double imcc,
                          Ima.BacktestResult backtest, Plat.PlatResult plat, double ses,
                          double capitalCore, double platSurcharge, double capital) {
    }

    /**
     * Validation check results shared across desks plus per-desk findings.
     *
     * @param benchmarkMaxDiff       max |BS - binomial(501)| over the pinned grid
     * @param sensitivityMaxDiff     max |analytic - FD| delta over the grid
     * @param stabilityBaseCapital   firm SBM capital with pinned RWs
     * @param stabilityCapitalRwUp10 firm SBM capital with GIRR delta RW x1.1
     * @param stabilityCapitalRwDn10 firm SBM capital with GIRR delta RW x0.9
     * @param stabilityRelChange     max abs relative capital move
     * @param dataQuality            per-desk staleness/gap counts
     * @param findings               per-desk findings (rule-table order)
     * @param verdicts               per-desk verdict strings
     */
    public record ValidationResults(double benchmarkMaxDiff, double sensitivityMaxDiff,
                                    double stabilityBaseCapital,
                                    double stabilityCapitalRwUp10,
                                    double stabilityCapitalRwDn10,
                                    double stabilityRelChange,
                                    Map<String, Validation.DataQuality> dataQuality,
                                    Map<String, List<Validation.Finding>> findings,
                                    Map<String, String> verdicts) {
    }

    /** The full result tree of one engine run over the bundled data set. */
    public static final class Results {
        /** Pinned parameter set. */
        public final SbmParams params;
        /** Market snapshot. */
        public final Market market;
        /** Desks by name. */
        public final Map<String, Desk> desks;
        /** SA results per scope ("desk1", "desk2", "firm"). */
        public final Map<String, SaScope> sa;
        /** Firm-wide sensitivities. */
        public final Sensitivities sensFirm;
        /** IMA sketch per desk. */
        public final Map<String, DeskIma> ima;
        /** Validation checks, findings and verdicts. */
        public final ValidationResults validation;
        /** Rendered validation report markdown. */
        public final String reportMd;

        Results(SbmParams params, Market market, Map<String, Desk> desks,
                Map<String, SaScope> sa, Sensitivities sensFirm, Map<String, DeskIma> ima,
                ValidationResults validation, String reportMd) {
            this.params = params;
            this.market = market;
            this.desks = desks;
            this.sa = sa;
            this.sensFirm = sensFirm;
            this.ima = ima;
            this.validation = validation;
            this.reportMd = reportMd;
        }
    }

    /**
     * Load a P&amp;L CSV (date + numeric columns) into {@code column -> series}.
     * Empty cells become NaN (picked up by the data-quality check).
     */
    public static Map<String, double[]> loadPnlCsv(Path path) {
        List<Map<String, String>> rows = Market.readCsv(path);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("loadPnlCsv: " + path + " contains no data rows");
        }
        List<String> cols = new ArrayList<>(rows.get(0).keySet());
        if (!cols.contains("date")) {
            throw new IllegalArgumentException("loadPnlCsv: " + path + " must have a 'date' column");
        }
        cols.remove("date");
        Map<String, double[]> out = new LinkedHashMap<>();
        for (String c : cols) {
            double[] series = new double[rows.size()];
            for (int i = 0; i < rows.size(); i++) {
                String cell = rows.get(i).get(c).strip();
                series[i] = cell.isEmpty() ? Double.NaN : Double.parseDouble(cell);
            }
            out.put(c, series);
        }
        return out;
    }

    /** Extract the per-category P&amp;L columns {@code <desk>_<cat>} for one desk. */
    public static Map<String, double[]> deskCategories(String desk, Map<String, double[]> hypo) {
        String prefix = desk + "_";
        Map<String, double[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> e : hypo.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                out.put(e.getKey().substring(prefix.length()), e.getValue());
            }
        }
        return out;
    }

    /** SA capital for one instrument scope: SBM + DRC-lite + RRAO. */
    public static SaScope computeSa(List<Instrument> instruments, Market market,
                                    SbmParams params) {
        Sensitivities sens = Sensitivities.compute(instruments, market, params);
        Sa.SbmResult sbm = Sa.sbmCapital(sens, market, params);
        Sa.DrcResult drc = Sa.drcCharge(Sa.drcPositionsFromInstruments(instruments, market),
                params);
        return new SaScope(sbm, drc.charge(), drc.hbr(), Sa.rraoCharge(instruments, params));
    }

    /** Load {@code nmrf.json} into a factor list. */
    public static List<Ima.NmrfEntry> loadNmrf(Path path) {
        String text;
        try {
            text = Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Object> raw = Json.asObject(Json.parse(text), "nmrf root");
        List<Ima.NmrfEntry> out = new ArrayList<>();
        for (Object o : Json.asArray(Json.require(raw, "factors", "nmrf root"), "factors")) {
            Map<String, Object> m = Json.asObject(o, "nmrf factor");
            out.add(new Ima.NmrfEntry(
                    Json.asString(m.get("factor"), "nmrf.factor"),
                    Json.asString(m.get("desk"), "nmrf.desk"),
                    Json.asNumber(m.get("stressed_loss"), "nmrf.stressed_loss")));
        }
        return out;
    }

    /** Compute the full result tree from the bundled data directory. */
    public static Results computeResults(Path dataDir) {
        SbmParams params = SbmParams.load(dataDir.resolve("sbm_params.json"));
        Market market = Market.load(dataDir.resolve("curves.csv"), dataDir.resolve("spots.csv"));
        Map<String, Desk> desks = Portfolio.load(dataDir.resolve("portfolio.json"));
        Map<String, double[]> hypo = loadPnlCsv(dataDir.resolve("pnl_hypo.csv"));
        Map<String, double[]> rtpl = loadPnlCsv(dataDir.resolve("pnl_rtpl.csv"));
        Map<String, double[]> var99 = loadPnlCsv(dataDir.resolve("pnl_var.csv"));
        List<Ima.NmrfEntry> nmrf = loadNmrf(dataDir.resolve("nmrf.json"));

        List<String> deskNames = new ArrayList<>(new TreeSet<>(desks.keySet()));
        List<Instrument> allInstruments = new ArrayList<>();
        for (String d : deskNames) {
            allInstruments.addAll(desks.get(d).instruments());
        }

        // ---- SA per desk + firm --------------------------------------------
        Map<String, SaScope> sa = new LinkedHashMap<>();
        for (String d : deskNames) {
            sa.put(d, computeSa(desks.get(d).instruments(), market, params));
        }
        sa.put("firm", computeSa(allInstruments, market, params));
        Sensitivities sensFirm = Sensitivities.compute(allInstruments, market, params);

        // ---- IMA per desk ---------------------------------------------------
        Map<String, DeskIma> ima = new LinkedHashMap<>();
        for (String d : deskNames) {
            Map<String, double[]> cats = deskCategories(d, hypo);
            if (cats.isEmpty()) {
                throw new IllegalArgumentException(
                        "computeResults: no category P&L columns for desk '" + d + "'");
            }
            double[] full = hypo.get(d);
            double esB = Ima.esBase10d(full, params.imaAlpha());
            double esLh = Ima.esLhScaled(full, cats, params.categoryLh(), params.lhLadder(),
                    params.imaAlpha());
            double imccD = Ima.imcc(full, cats, params);
            Ima.BacktestResult bt = Ima.backtest(full, var99.get(d), params);
            Plat.PlatResult pl = Plat.platTest(full, rtpl.get(d), params);
            List<Ima.NmrfEntry> deskNmrf = new ArrayList<>();
            for (Ima.NmrfEntry e : nmrf) {
                if (e.desk().equals(d)) {
                    deskNmrf.add(e);
                }
            }
            double sesD = Ima.ses(deskNmrf);
            double core = Ima.imaCapital(imccD, bt.multiplier(), sesD, 0.0);
            double surcharge = Plat.platSurcharge(pl.zone(), sa.get(d).capital(), core, params);
            ima.put(d, new DeskIma(esB, esLh, imccD, bt, pl, sesD, core, surcharge,
                    core + surcharge));
        }

        // ---- validation checks ----------------------------------------------
        double bench = Validation.benchmarkMaxDiff();
        double sensDiff = Validation.sensitivityMaxDiff();
        double baseCap = sa.get("firm").sbm().capital();
        double capUp = Sa.sbmCapital(sensFirm, market,
                params.withGirrDeltaRwScaled(1.1)).capital();
        double capDn = Sa.sbmCapital(sensFirm, market,
                params.withGirrDeltaRwScaled(0.9)).capital();
        double stabilityRel = baseCap > 0.0
                ? Math.max(Math.abs(capUp - baseCap), Math.abs(capDn - baseCap)) / baseCap
                : 0.0;
        Map<String, Validation.DataQuality> dq = new LinkedHashMap<>();
        for (String d : deskNames) {
            dq.put(d, Validation.dataQuality(hypo.get(d)));
        }

        Map<String, List<Validation.Finding>> findings = new LinkedHashMap<>();
        Map<String, String> verdicts = new LinkedHashMap<>();
        for (String d : deskNames) {
            Validation.DeskCheckInputs inputs = new Validation.DeskCheckInputs(
                    bench, sensDiff, stabilityRel,
                    ima.get(d).backtest().zone(), ima.get(d).plat().zone(),
                    dq.get(d).staleDays(), dq.get(d).gaps());
            findings.put(d, Validation.classifyFindings(inputs));
            verdicts.put(d, Validation.overallVerdict(findings.get(d)));
        }

        ValidationResults validation = new ValidationResults(bench, sensDiff, baseCap,
                capUp, capDn, stabilityRel, dq, findings, verdicts);
        String reportMd = Validation.renderReport(ima, validation);
        return new Results(params, market, desks, sa, sensFirm, ima, validation, reportMd);
    }
}
