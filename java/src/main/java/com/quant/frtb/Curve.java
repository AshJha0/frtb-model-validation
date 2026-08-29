package com.quant.frtb;

import java.util.Arrays;

/**
 * Continuously-compounded zero curve with linear interpolation in tenor.
 *
 * <p>Rates are interpolated linearly between nodes and extrapolated flat
 * beyond the first/last node. Discount factor: {@code DF(t) = exp(-z(t)*t)},
 * {@code DF(0) = 1}. Instances are immutable; every bump returns a new curve
 * so bump-and-revalue sensitivities cannot leak state.
 */
public final class Curve {

    private final double[] tenors;
    private final double[] rates;

    /**
     * @param tenors strictly increasing positive node tenors (years)
     * @param rates  zero rates per node (continuously compounded)
     * @throws IllegalArgumentException on empty/mismatched/non-increasing input
     */
    public Curve(double[] tenors, double[] rates) {
        if (tenors.length == 0 || tenors.length != rates.length) {
            throw new IllegalArgumentException(
                    "Curve: tenors and rates must be non-empty and equal length");
        }
        for (int i = 1; i < tenors.length; i++) {
            if (tenors[i] <= tenors[i - 1]) {
                throw new IllegalArgumentException("Curve: tenors must be strictly increasing");
            }
        }
        for (int i = 0; i < tenors.length; i++) {
            if (!(Double.isFinite(tenors[i]) && Double.isFinite(rates[i])) || tenors[i] <= 0.0) {
                throw new IllegalArgumentException(
                        "Curve: tenors must be positive finite, rates finite");
            }
        }
        this.tenors = tenors.clone();
        this.rates = rates.clone();
    }

    /** Node tenors (copy). */
    public double[] tenors() {
        return tenors.clone();
    }

    /** Interpolated zero rate at time t (flat extrapolation beyond the ends). */
    public double rate(double t) {
        if (!Double.isFinite(t) || t < 0.0) {
            throw new IllegalArgumentException("Curve.rate: invalid time " + t);
        }
        if (t <= tenors[0]) {
            return rates[0];
        }
        if (t >= tenors[tenors.length - 1]) {
            return rates[rates.length - 1];
        }
        for (int i = 1; i < tenors.length; i++) {
            if (t <= tenors[i]) {
                double w = (t - tenors[i - 1]) / (tenors[i] - tenors[i - 1]);
                return rates[i - 1] * (1.0 - w) + rates[i] * w;
            }
        }
        return rates[rates.length - 1]; // unreachable
    }

    /** Discount factor {@code exp(-z(t)*t)}; {@code DF(0) = 1}. */
    public double df(double t) {
        if (t == 0.0) {
            return 1.0;
        }
        return Libm.exp(-rate(t) * t);
    }

    /**
     * Curve with the zero rate at one node shifted by {@code size} (absolute).
     *
     * @throws IllegalArgumentException if {@code tenor} is not a curve node
     */
    public Curve bumpedNode(double tenor, double size) {
        int idx = -1;
        for (int i = 0; i < tenors.length; i++) {
            if (tenors[i] == tenor) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            throw new IllegalArgumentException(
                    "Curve.bumpedNode: tenor " + tenor + " is not a curve node");
        }
        double[] r = rates.clone();
        r[idx] = r[idx] + size;
        return new Curve(tenors, r);
    }

    /** Curve with every node shifted by {@code size} (absolute). */
    public Curve bumpedParallel(double size) {
        double[] r = rates.clone();
        for (int i = 0; i < r.length; i++) {
            r[i] = r[i] + size;
        }
        return new Curve(tenors, r);
    }

    @Override
    public String toString() {
        return "Curve" + Arrays.toString(tenors);
    }
}
