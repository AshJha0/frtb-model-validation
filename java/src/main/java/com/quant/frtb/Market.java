package com.quant.frtb;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Immutable market snapshot: zero curves per currency, equity quotes per
 * name, FX spots per pair. All bump helpers return <em>new</em> Market
 * objects so bump-and-revalue sensitivities cannot leak state.
 *
 * @param curves   zero curve per currency
 * @param equities equity quote per name
 * @param fx       spot per FORDOM pair (e.g. "EURUSD")
 */
public record Market(Map<String, Curve> curves,
                     Map<String, EquityQuote> equities,
                     Map<String, Double> fx) {

    /**
     * Equity market data: spot, flat lognormal vol, dividend yield, SBM bucket.
     *
     * @param spot     spot price (&gt; 0)
     * @param vol      flat lognormal volatility (&ge; 0)
     * @param divYield continuous dividend yield
     * @param bucket   pinned SBM equity bucket id
     */
    public record EquityQuote(double spot, double vol, double divYield, String bucket) {
        public EquityQuote {
            if (!Double.isFinite(spot) || spot <= 0.0) {
                throw new IllegalArgumentException(
                        "EquityQuote: spot must be positive finite, got " + spot);
            }
            if (!Double.isFinite(vol) || vol < 0.0) {
                throw new IllegalArgumentException("EquityQuote: vol must be >= 0, got " + vol);
            }
            if (!Double.isFinite(divYield)) {
                throw new IllegalArgumentException("EquityQuote: divYield must be finite");
            }
        }
    }

    /** Curve for a currency; {@link IllegalArgumentException} when missing. */
    public Curve curve(String ccy) {
        Curve c = curves.get(ccy);
        if (c == null) {
            throw new IllegalArgumentException("Market: no curve for currency '" + ccy + "'");
        }
        return c;
    }

    /** Equity quote by name; {@link IllegalArgumentException} when missing. */
    public EquityQuote equity(String name) {
        EquityQuote q = equities.get(name);
        if (q == null) {
            throw new IllegalArgumentException("Market: no equity quote for '" + name + "'");
        }
        return q;
    }

    /** FX spot by pair; {@link IllegalArgumentException} when missing. */
    public double fxSpot(String pair) {
        Double s = fx.get(pair);
        if (s == null) {
            throw new IllegalArgumentException("Market: no FX spot for pair '" + pair + "'");
        }
        return s;
    }

    // ------------------- bump helpers (all return new Market objects) ------

    /** Market with one currency's curve replaced. */
    public Market withCurve(String ccy, Curve curve) {
        Map<String, Curve> c = new LinkedHashMap<>(curves);
        c.put(ccy, curve);
        return new Market(c, equities, fx);
    }

    /** Absolute bump of one curve node: {@code z(tenor) -> z(tenor) + size}. */
    public Market bumpCurveNode(String ccy, double tenor, double size) {
        return withCurve(ccy, curve(ccy).bumpedNode(tenor, size));
    }

    /** Absolute parallel bump of one currency's curve. */
    public Market bumpCurveParallel(String ccy, double size) {
        return withCurve(ccy, curve(ccy).bumpedParallel(size));
    }

    /** Relative equity spot bump: {@code S -> S * (1 + rel)}. */
    public Market bumpEquitySpot(String name, double rel) {
        EquityQuote q = equity(name);
        Map<String, EquityQuote> eqs = new LinkedHashMap<>(equities);
        eqs.put(name, new EquityQuote(q.spot() * (1.0 + rel), q.vol(), q.divYield(), q.bucket()));
        return new Market(curves, eqs, fx);
    }

    /** Absolute equity vol bump: {@code sigma -> sigma + size}. */
    public Market bumpEquityVol(String name, double size) {
        EquityQuote q = equity(name);
        Map<String, EquityQuote> eqs = new LinkedHashMap<>(equities);
        eqs.put(name, new EquityQuote(q.spot(), q.vol() + size, q.divYield(), q.bucket()));
        return new Market(curves, eqs, fx);
    }

    /** Relative FX spot bump: {@code S -> S * (1 + rel)}. */
    public Market bumpFx(String pair, double rel) {
        double s = fxSpot(pair);
        Map<String, Double> f = new LinkedHashMap<>(fx);
        f.put(pair, s * (1.0 + rel));
        return new Market(curves, equities, f);
    }

    // ------------------------------------------------------------- loading --

    /**
     * Load a Market from {@code curves.csv} (currency,tenor,zero_rate) and
     * {@code spots.csv} (kind,name,spot,vol,div_yield,eq_bucket).
     *
     * @throws IllegalArgumentException on schema errors or empty curve data
     */
    public static Market load(Path curvesCsv, Path spotsCsv) {
        Map<String, TreeMap<Double, Double>> byCcy = new LinkedHashMap<>();
        for (Map<String, String> row : readCsv(curvesCsv)) {
            byCcy.computeIfAbsent(row.get("currency"), k -> new TreeMap<>())
                    .put(Double.parseDouble(row.get("tenor")),
                            Double.parseDouble(row.get("zero_rate")));
        }
        Map<String, Curve> curves = new LinkedHashMap<>();
        for (Map.Entry<String, TreeMap<Double, Double>> e : byCcy.entrySet()) {
            double[] ts = e.getValue().keySet().stream().mapToDouble(Double::doubleValue).toArray();
            double[] rs = e.getValue().values().stream().mapToDouble(Double::doubleValue).toArray();
            curves.put(e.getKey(), new Curve(ts, rs));
        }
        if (curves.isEmpty()) {
            throw new IllegalArgumentException("Market.load: no curves in " + curvesCsv);
        }

        Map<String, EquityQuote> equities = new LinkedHashMap<>();
        Map<String, Double> fx = new LinkedHashMap<>();
        for (Map<String, String> row : readCsv(spotsCsv)) {
            String kind = row.get("kind");
            if ("equity".equals(kind)) {
                equities.put(row.get("name"), new EquityQuote(
                        Double.parseDouble(row.get("spot")),
                        Double.parseDouble(row.get("vol")),
                        Double.parseDouble(row.get("div_yield")),
                        row.get("eq_bucket")));
            } else if ("fx".equals(kind)) {
                fx.put(row.get("name"), Double.parseDouble(row.get("spot")));
            } else {
                throw new IllegalArgumentException(
                        "Market.load: unknown kind '" + kind + "' in " + spotsCsv);
            }
        }
        return new Market(curves, equities, fx);
    }

    /** Tiny header-keyed CSV reader (the bundled files have no quoting). */
    static List<Map<String, String>> readCsv(Path path) {
        List<String> lines;
        try {
            lines = Files.readAllLines(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("readCsv: empty file " + path);
        }
        String[] header = lines.get(0).split(",", -1);
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) {
                continue;
            }
            String[] cells = lines.get(i).split(",", -1);
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < header.length; c++) {
                row.put(header[c], c < cells.length ? cells[c] : "");
            }
            rows.add(row);
        }
        return rows;
    }
}
