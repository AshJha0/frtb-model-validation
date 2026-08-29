package com.quant.frtb;

/**
 * The (deliberately small) instrument universe of this educational FRTB kit:
 * bullet bond, payer-swap proxy, European equity option, FX forward.
 *
 * <p>Instances are immutable records; construction validates inputs and
 * throws {@link IllegalArgumentException} on bad data (the Java analogue of
 * the Python reference's {@code ValueError}).
 */
public sealed interface Instrument permits Bond, PayerSwap, EquityOption, FxForward {

    /** Instrument identifier from {@code portfolio.json}. */
    String instId();

    /** Residual-risk add-on flag, or {@code null} when the trade is not flagged. */
    RraoFlag rrao();
}
