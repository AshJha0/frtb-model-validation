package com.quant.frtb;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loading of {@code portfolio.json} into {@link Desk}/{@link Instrument}
 * objects. Schema errors raise {@link IllegalArgumentException}.
 */
public final class Portfolio {

    private Portfolio() {
    }

    /** Parse one instrument object from the {@code portfolio.json} schema. */
    public static Instrument instrumentFromJson(Map<String, Object> d) {
        Object typ = d.get("type");
        if ("bond".equals(typ)) {
            return new Bond(
                    Json.asString(d.get("id"), "bond.id"),
                    Json.asNumber(d.get("notional"), "bond.notional"),
                    Json.asNumber(d.get("coupon"), "bond.coupon"),
                    Json.asNumber(d.get("maturity"), "bond.maturity"),
                    Json.asString(d.get("currency"), "bond.currency"),
                    Json.asString(d.get("issuer"), "bond.issuer"),
                    Json.asString(d.get("rating"), "bond.rating"),
                    d.containsKey("lgd") ? Json.asNumber(d.get("lgd"), "bond.lgd") : 0.75,
                    parseRrao(d));
        }
        if ("payer_swap".equals(typ)) {
            return new PayerSwap(
                    Json.asString(d.get("id"), "swap.id"),
                    Json.asNumber(d.get("notional"), "swap.notional"),
                    Json.asNumber(d.get("fixed_rate"), "swap.fixed_rate"),
                    Json.asNumber(d.get("maturity"), "swap.maturity"),
                    Json.asString(d.get("currency"), "swap.currency"),
                    parseRrao(d));
        }
        if ("equity_option".equals(typ)) {
            return new EquityOption(
                    Json.asString(d.get("id"), "option.id"),
                    Json.asString(d.get("underlier"), "option.underlier"),
                    Json.asString(d.get("option_type"), "option.option_type"),
                    (int) Json.asNumber(d.get("position"), "option.position"),
                    Json.asNumber(d.get("contracts"), "option.contracts"),
                    Json.asNumber(d.get("strike"), "option.strike"),
                    Json.asNumber(d.get("maturity"), "option.maturity"),
                    Json.asString(d.get("currency"), "option.currency"),
                    parseRrao(d));
        }
        if ("fx_forward".equals(typ)) {
            return new FxForward(
                    Json.asString(d.get("id"), "fwd.id"),
                    Json.asString(d.get("pair"), "fwd.pair"),
                    Json.asNumber(d.get("notional"), "fwd.notional"),
                    Json.asNumber(d.get("strike"), "fwd.strike"),
                    Json.asNumber(d.get("maturity"), "fwd.maturity"),
                    parseRrao(d));
        }
        throw new IllegalArgumentException(
                "Portfolio: unknown instrument type '" + typ + "'");
    }

    private static RraoFlag parseRrao(Map<String, Object> d) {
        Object r = d.get("rrao");
        if (r == null) {
            return null;
        }
        Map<String, Object> m = Json.asObject(r, "rrao");
        return new RraoFlag(Json.asString(m.get("category"), "rrao.category"),
                Json.asNumber(m.get("notional"), "rrao.notional"));
    }

    /**
     * Load {@code portfolio.json} into {@code {deskName -> Desk}} preserving
     * file order; duplicate desk names raise {@link IllegalArgumentException}.
     */
    public static Map<String, Desk> load(Path path) {
        String text;
        try {
            text = Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Object> raw = Json.asObject(Json.parse(text), "portfolio root");
        if (!(raw.get("desks") instanceof List)) {
            throw new IllegalArgumentException(
                    "Portfolio.load: portfolio.json must contain a 'desks' list");
        }
        Map<String, Desk> desks = new LinkedHashMap<>();
        for (Object dObj : Json.asArray(raw.get("desks"), "desks")) {
            Map<String, Object> d = Json.asObject(dObj, "desk");
            String name = Json.asString(d.get("name"), "desk.name");
            if (desks.containsKey(name)) {
                throw new IllegalArgumentException(
                        "Portfolio.load: duplicate desk name '" + name + "'");
            }
            List<Instrument> insts = new ArrayList<>();
            if (d.containsKey("instruments")) {
                for (Object i : Json.asArray(d.get("instruments"), "instruments")) {
                    insts.add(instrumentFromJson(Json.asObject(i, "instrument")));
                }
            }
            String display = d.containsKey("display")
                    ? Json.asString(d.get("display"), "desk.display") : name;
            desks.put(name, new Desk(name, display, insts));
        }
        return desks;
    }
}
