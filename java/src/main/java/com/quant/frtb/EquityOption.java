package com.quant.frtb;

/**
 * European equity option; position value = {@code position * contracts * BS(...)}.
 *
 * @param instId     instrument id
 * @param underlier  equity name (looked up in the market snapshot)
 * @param optionType "call" or "put"
 * @param position   +1 long, -1 short
 * @param contracts  number of contracts (&gt; 0)
 * @param strike     strike (&gt; 0)
 * @param maturity   maturity in years (&ge; 0; 0 means expiry)
 * @param currency   discounting currency (rate read off its zero curve)
 * @param rrao       optional residual-risk add-on flag
 */
public record EquityOption(String instId, String underlier, String optionType, int position,
                           double contracts, double strike, double maturity, String currency,
                           RraoFlag rrao) implements Instrument {

    public EquityOption {
        if (!"call".equals(optionType) && !"put".equals(optionType)) {
            throw new IllegalArgumentException(
                    "EquityOption: optionType must be call/put, got '" + optionType + "'");
        }
        if (position != 1 && position != -1) {
            throw new IllegalArgumentException(
                    "EquityOption: position must be +1 or -1, got " + position);
        }
        if (contracts <= 0.0 || !Double.isFinite(contracts)) {
            throw new IllegalArgumentException("EquityOption: contracts must be positive");
        }
        if (strike <= 0.0 || !Double.isFinite(strike)) {
            throw new IllegalArgumentException("EquityOption: strike must be positive");
        }
        if (maturity < 0.0 || !Double.isFinite(maturity)) {
            throw new IllegalArgumentException("EquityOption: maturity must be >= 0");
        }
    }

    /** True for a call, false for a put. */
    public boolean isCall() {
        return "call".equals(optionType);
    }
}
