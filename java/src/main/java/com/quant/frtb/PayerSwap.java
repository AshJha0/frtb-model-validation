package com.quant.frtb;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Payer interest-rate swap proxy (pay fixed, receive float).
 *
 * <p>Value = {@code N*(1 - DF(T)) - fixedRate*N*sum_i DF(t_i)} with annual
 * fixed payments; the floating leg is worth {@code N*(1 - DF(T))}. Its GIRR
 * sensitivity is the "DV01 ladder" of the spec.
 *
 * @param instId    instrument id
 * @param notional  notional (non-zero)
 * @param fixedRate fixed leg rate
 * @param maturity  maturity in whole-ish years (&ge; 1)
 * @param currency  discounting currency
 * @param rrao      optional residual-risk add-on flag
 */
public record PayerSwap(String instId, double notional, double fixedRate, double maturity,
                        String currency, RraoFlag rrao) implements Instrument {

    public PayerSwap {
        if (!Double.isFinite(notional)) {
            throw new IllegalArgumentException(
                    "PayerSwap.notional must be finite, got " + notional);
        }
        if (!Double.isFinite(fixedRate)) {
            throw new IllegalArgumentException(
                    "PayerSwap.fixedRate must be finite, got " + fixedRate);
        }
        if (notional == 0.0) {
            throw new IllegalArgumentException("PayerSwap: notional must be non-zero");
        }
        if (maturity < 1.0 || !Double.isFinite(maturity)) {
            throw new IllegalArgumentException(
                    "PayerSwap: maturity must be >= 1y, got " + maturity);
        }
    }

    /** Annual fixed-leg payment times T, T-1, ... (&gt; 0), ascending. */
    public List<Double> fixedTimes() {
        List<Double> times = new ArrayList<>();
        double t = maturity;
        while (t > 1e-9) {
            times.add(t);
            t -= 1.0;
        }
        Collections.sort(times);
        return times;
    }
}
