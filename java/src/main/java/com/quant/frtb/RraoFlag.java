package com.quant.frtb;

/**
 * Residual-risk add-on flag: pinned category plus the notional base the
 * RRAO rate applies to.
 *
 * @param category "exotic" (1.0%) or "other" (0.1%)
 * @param notional non-negative notional base for the add-on
 */
public record RraoFlag(String category, double notional) {

    public RraoFlag {
        if (!"exotic".equals(category) && !"other".equals(category)) {
            throw new IllegalArgumentException(
                    "RraoFlag: category must be 'exotic' or 'other', got '" + category + "'");
        }
        if (!Double.isFinite(notional) || notional < 0.0) {
            throw new IllegalArgumentException(
                    "RraoFlag: notional must be a non-negative finite number");
        }
    }
}
