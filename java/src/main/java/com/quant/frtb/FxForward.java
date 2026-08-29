package com.quant.frtb;

/**
 * FX forward on {@code pair} = FORDOM (e.g. EURUSD): long N foreign units at
 * strike K. Value in domestic ccy = {@code N * (S*DF_for(T) - K*DF_dom(T))}.
 *
 * @param instId   instrument id
 * @param pair     6-character FORDOM pair (e.g. "EURUSD")
 * @param notional foreign notional (non-zero)
 * @param strike   forward strike (&gt; 0)
 * @param maturity maturity in years (&gt; 0)
 * @param rrao     optional residual-risk add-on flag
 */
public record FxForward(String instId, String pair, double notional, double strike,
                        double maturity, RraoFlag rrao) implements Instrument {

    public FxForward {
        if (!Double.isFinite(notional)) {
            throw new IllegalArgumentException(
                    "FxForward.notional must be finite, got " + notional);
        }
        if (notional == 0.0) {
            throw new IllegalArgumentException("FxForward: notional must be non-zero");
        }
        if (strike <= 0.0 || !Double.isFinite(strike)) {
            throw new IllegalArgumentException("FxForward: strike must be positive");
        }
        if (maturity <= 0.0 || !Double.isFinite(maturity)) {
            throw new IllegalArgumentException("FxForward: maturity must be positive");
        }
        if (pair.length() != 6) {
            throw new IllegalArgumentException(
                    "FxForward: pair must be 6 chars FORDOM, got '" + pair + "'");
        }
    }

    /** Foreign (base) currency, e.g. "EUR" for EURUSD. */
    public String foreign() {
        return pair.substring(0, 3);
    }

    /** Domestic (quote) currency, e.g. "USD" for EURUSD. */
    public String domestic() {
        return pair.substring(3);
    }
}
