package com.quant.frtb;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fixed-coupon annual-pay bullet bond (also the DRC vehicle).
 *
 * <p>{@code notional} may be negative (short position, used by DRC netting).
 * Coupons are paid at T, T-1, ... (annual, stub-free by construction of the
 * bundled data).
 *
 * @param instId   instrument id
 * @param notional signed face amount (non-zero)
 * @param coupon   annual coupon rate
 * @param maturity maturity in years (&gt; 0)
 * @param currency discounting currency
 * @param issuer   issuer id (DRC netting key)
 * @param rating   pinned rating bucket (DRC risk weight lookup)
 * @param lgd      loss-given-default in [0, 1] (bonds pinned at 0.75)
 * @param rrao     optional residual-risk add-on flag
 */
public record Bond(String instId, double notional, double coupon, double maturity,
                   String currency, String issuer, String rating, double lgd,
                   RraoFlag rrao) implements Instrument {

    public Bond {
        if (!Double.isFinite(notional)) {
            throw new IllegalArgumentException("Bond.notional must be finite, got " + notional);
        }
        if (!Double.isFinite(coupon)) {
            throw new IllegalArgumentException("Bond.coupon must be finite, got " + coupon);
        }
        if (notional == 0.0) {
            throw new IllegalArgumentException("Bond: notional must be non-zero");
        }
        if (maturity <= 0.0 || !Double.isFinite(maturity)) {
            throw new IllegalArgumentException("Bond: maturity must be positive, got " + maturity);
        }
        if (!(0.0 <= lgd && lgd <= 1.0)) {
            throw new IllegalArgumentException("Bond: LGD must be in [0,1], got " + lgd);
        }
    }

    /** Annual coupon payment times T, T-1, ... (&gt; 0), ascending. */
    public List<Double> couponTimes() {
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
