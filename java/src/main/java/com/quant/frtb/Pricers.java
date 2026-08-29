package com.quant.frtb;

import java.util.List;

/**
 * Self-contained pricer kit: Black-Scholes (with edge cases), CRR binomial
 * benchmark, bond / swap-proxy / FX-forward pricing off zero curves.
 *
 * <p>All prices are deterministic closed-form / lattice computations — no
 * RNG. The elementary functions go through {@link Libm} so every price is
 * bit-identical to the Python reference running on glibc.
 */
public final class Pricers {

    private Pricers() {
    }

    private static final double SQRT2 = Math.sqrt(2.0);

    /** Standard normal CDF via erf (double precision, no external deps). */
    public static double normCdf(double x) {
        return 0.5 * (1.0 + Libm.erf(x / SQRT2));
    }

    /** Standard normal PDF. */
    public static double normPdf(double x) {
        return Libm.exp(-0.5 * x * x) / Math.sqrt(2.0 * Math.PI);
    }

    private static void validateBs(double s, double k, double t, double sigma) {
        checkFinite("spot", s);
        checkFinite("strike", k);
        checkFinite("maturity", t);
        checkFinite("sigma", sigma);
        if (s <= 0.0 || k <= 0.0) {
            throw new IllegalArgumentException(
                    "blackScholes: spot/strike must be positive (s=" + s + ", k=" + k + ")");
        }
        if (t < 0.0) {
            throw new IllegalArgumentException("blackScholes: maturity must be >= 0, got " + t);
        }
        if (sigma < 0.0) {
            throw new IllegalArgumentException("blackScholes: sigma must be >= 0, got " + sigma);
        }
    }

    private static void checkFinite(String name, double v) {
        if (!Double.isFinite(v)) {
            throw new IllegalArgumentException("blackScholes: " + name + " must be finite, got " + v);
        }
    }

    /**
     * Black-Scholes price with continuous dividend yield q.
     *
     * <p>Edge cases: {@code t == 0} returns intrinsic value; {@code sigma == 0}
     * returns the discounted deterministic payoff
     * {@code max(+/-(S e^-qT - K e^-rT), 0)}.
     *
     * @param s     spot
     * @param k     strike
     * @param t     maturity in years
     * @param r     continuously compounded rate
     * @param q     continuous dividend yield
     * @param sigma lognormal volatility
     * @param call  true for a call, false for a put
     * @return the option price
     */
    public static double bsPrice(double s, double k, double t, double r, double q,
                                 double sigma, boolean call) {
        validateBs(s, k, t, sigma);
        double sign = call ? 1.0 : -1.0;
        if (t == 0.0) {
            return Math.max(sign * (s - k), 0.0);
        }
        if (sigma == 0.0) {
            return Math.max(sign * (s * Libm.exp(-q * t) - k * Libm.exp(-r * t)), 0.0);
        }
        double sq = sigma * Math.sqrt(t);
        double d1 = (Libm.log(s / k) + (r - q + 0.5 * sigma * sigma) * t) / sq;
        double d2 = d1 - sq;
        return sign * (s * Libm.exp(-q * t) * normCdf(sign * d1)
                - k * Libm.exp(-r * t) * normCdf(sign * d2));
    }

    /** Analytic Black-Scholes spot delta dV/dS (used by the validation FD check). */
    public static double bsDelta(double s, double k, double t, double r, double q,
                                 double sigma, boolean call) {
        validateBs(s, k, t, sigma);
        double sign = call ? 1.0 : -1.0;
        if (t == 0.0 || sigma == 0.0) {
            // deterministic payoff: delta is a step function; return the a.e. value
            double fwdItm = t > 0.0
                    ? s * Libm.exp(-q * t) - k * Libm.exp(-r * t)
                    : s - k;
            return Libm.exp(-q * t) * (sign * fwdItm > 0.0 ? 1.0 : 0.0) * sign;
        }
        double sq = sigma * Math.sqrt(t);
        double d1 = (Libm.log(s / k) + (r - q + 0.5 * sigma * sigma) * t) / sq;
        return sign * Libm.exp(-q * t) * normCdf(sign * d1);
    }

    /** Analytic Black-Scholes vega dV/dsigma (same for call and put). */
    public static double bsVega(double s, double k, double t, double r, double q, double sigma) {
        validateBs(s, k, t, sigma);
        if (t == 0.0 || sigma == 0.0) {
            return 0.0;
        }
        double sq = sigma * Math.sqrt(t);
        double d1 = (Libm.log(s / k) + (r - q + 0.5 * sigma * sigma) * t) / sq;
        return s * Libm.exp(-q * t) * normPdf(d1) * Math.sqrt(t);
    }

    /**
     * European option price on a CRR (Cox-Ross-Rubinstein) lattice.
     *
     * <p>Used only as the independent benchmark pricer in the validation
     * framework (converges to Black-Scholes as steps grows).
     *
     * @throws IllegalArgumentException on invalid inputs or a risk-neutral
     *     probability outside (0, 1)
     */
    public static double binomialPrice(double s, double k, double t, double r, double q,
                                       double sigma, boolean call, int steps) {
        validateBs(s, k, t, sigma);
        if (steps < 1) {
            throw new IllegalArgumentException("binomialPrice: steps must be >= 1, got " + steps);
        }
        if (t == 0.0 || sigma == 0.0) {
            return bsPrice(s, k, t, r, q, sigma, call);
        }
        double dt = t / steps;
        double u = Libm.exp(sigma * Math.sqrt(dt));
        double d = 1.0 / u;
        double disc = Libm.exp(-r * dt);
        double p = (Libm.exp((r - q) * dt) - d) / (u - d);
        if (!(0.0 < p && p < 1.0)) {
            throw new IllegalArgumentException(
                    "binomialPrice: risk-neutral probability outside (0,1); increase steps");
        }
        double[] v = new double[steps + 1];
        for (int j = 0; j <= steps; j++) {
            double st = s * Math.pow(u, 2.0 * j - steps);
            v[j] = Math.max(call ? st - k : k - st, 0.0);
        }
        for (int step = 0; step < steps; step++) {
            int m = steps - step; // v shrinks by one node per backward step
            for (int j = 0; j < m; j++) {
                v[j] = disc * (p * v[j + 1] + (1.0 - p) * v[j]);
            }
        }
        return v[0];
    }

    /** Dirty PV of an annual-pay bullet bond: {@code sum c*N*DF(t_i) + N*DF(T)}. */
    public static double priceBond(Bond bond, Curve curve) {
        double pv = bond.notional() * curve.df(bond.maturity());
        for (double ti : bond.couponTimes()) {
            pv += bond.coupon() * bond.notional() * curve.df(ti);
        }
        return pv;
    }

    /** Payer swap proxy: {@code V = N*(1 - DF(T)) - c*N*sum_i DF(t_i)}. */
    public static double pricePayerSwap(PayerSwap swap, Curve curve) {
        double annuity = 0.0;
        for (double ti : swap.fixedTimes()) {
            annuity += curve.df(ti);
        }
        return swap.notional() * (1.0 - curve.df(swap.maturity()))
                - swap.fixedRate() * swap.notional() * annuity;
    }

    /** FX forward value in domestic ccy: {@code N * (S*DF_for(T) - K*DF_dom(T))}. */
    public static double priceFxForward(FxForward fwd, double spot, Curve curveDom,
                                        Curve curveFor) {
        if (spot <= 0.0 || !Double.isFinite(spot)) {
            throw new IllegalArgumentException("priceFxForward: spot must be positive, got " + spot);
        }
        return fwd.notional() * (spot * curveFor.df(fwd.maturity())
                - fwd.strike() * curveDom.df(fwd.maturity()));
    }

    /** Position value of a European equity option (BS with dividend yield). */
    public static double priceEquityOption(EquityOption opt, Market market) {
        Market.EquityQuote quote = market.equity(opt.underlier());
        double r = opt.maturity() > 0.0 ? market.curve(opt.currency()).rate(opt.maturity()) : 0.0;
        double px = bsPrice(quote.spot(), opt.strike(), opt.maturity(), r, quote.divYield(),
                quote.vol(), opt.isCall());
        return opt.position() * opt.contracts() * px;
    }

    /** Dispatch: PV of a single instrument under the given market snapshot. */
    public static double priceInstrument(Instrument inst, Market market) {
        if (inst instanceof Bond bond) {
            return priceBond(bond, market.curve(bond.currency()));
        }
        if (inst instanceof PayerSwap swap) {
            return pricePayerSwap(swap, market.curve(swap.currency()));
        }
        if (inst instanceof EquityOption opt) {
            return priceEquityOption(opt, market);
        }
        if (inst instanceof FxForward fwd) {
            return priceFxForward(fwd, market.fxSpot(fwd.pair()),
                    market.curve(fwd.domestic()), market.curve(fwd.foreign()));
        }
        throw new IllegalArgumentException(
                "priceInstrument: unsupported instrument " + inst.getClass().getSimpleName());
    }

    /** Sum of instrument PVs (0.0 for an empty portfolio). */
    public static double pricePortfolio(List<Instrument> instruments, Market market) {
        double total = 0.0;
        for (Instrument inst : instruments) {
            total += priceInstrument(inst, market);
        }
        return total;
    }
}
