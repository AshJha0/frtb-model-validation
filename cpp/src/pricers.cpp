#include "frtb/pricers.hpp"

#include <algorithm>
#include <cmath>
#include <stdexcept>
#include <string>
#include <utility>

namespace frtb {

namespace {
/// Same double as Python's math.pi (M_PI is not guaranteed in strict ISO mode).
constexpr double kPi = 3.141592653589793;
}  // namespace

double norm_cdf(double x) { return 0.5 * (1.0 + std::erf(x / std::sqrt(2.0))); }

double norm_pdf(double x) { return std::exp(-0.5 * x * x) / std::sqrt(2.0 * kPi); }

namespace {

void validate_bs(double s, double k, double t, double sigma) {
    const std::pair<const char*, double> checks[] = {
        {"spot", s}, {"strike", k}, {"maturity", t}, {"sigma", sigma}};
    for (const auto& [name, v] : checks)
        if (!std::isfinite(v))
            throw std::invalid_argument(std::string("black_scholes: ") + name +
                                        " must be finite");
    if (s <= 0.0 || k <= 0.0)
        throw std::invalid_argument("black_scholes: spot/strike must be positive");
    if (t < 0.0)
        throw std::invalid_argument("black_scholes: maturity must be >= 0");
    if (sigma < 0.0)
        throw std::invalid_argument("black_scholes: sigma must be >= 0");
}

}  // namespace

double bs_price(double s, double k, double t, double r, double q, double sigma, bool call) {
    validate_bs(s, k, t, sigma);
    const double sign = call ? 1.0 : -1.0;
    if (t == 0.0) return std::max(sign * (s - k), 0.0);
    if (sigma == 0.0)
        return std::max(sign * (s * std::exp(-q * t) - k * std::exp(-r * t)), 0.0);
    // Same arithmetic ordering as the Python reference (bit-tight goldens).
    const double sq = sigma * std::sqrt(t);
    const double d1 = (std::log(s / k) + (r - q + 0.5 * sigma * sigma) * t) / sq;
    const double d2 = d1 - sq;
    return sign * (s * std::exp(-q * t) * norm_cdf(sign * d1) -
                   k * std::exp(-r * t) * norm_cdf(sign * d2));
}

double bs_delta(double s, double k, double t, double r, double q, double sigma, bool call) {
    validate_bs(s, k, t, sigma);
    const double sign = call ? 1.0 : -1.0;
    if (t == 0.0 || sigma == 0.0) {
        // Deterministic payoff: delta is a step function; return the a.e. value.
        const double fwd_itm =
            (t > 0.0) ? (s * std::exp(-q * t) - k * std::exp(-r * t)) : (s - k);
        return std::exp(-q * t) * ((sign * fwd_itm > 0.0) ? 1.0 : 0.0) * sign;
    }
    const double sq = sigma * std::sqrt(t);
    const double d1 = (std::log(s / k) + (r - q + 0.5 * sigma * sigma) * t) / sq;
    return sign * std::exp(-q * t) * norm_cdf(sign * d1);
}

double bs_vega(double s, double k, double t, double r, double q, double sigma) {
    validate_bs(s, k, t, sigma);
    if (t == 0.0 || sigma == 0.0) return 0.0;
    const double sq = sigma * std::sqrt(t);
    const double d1 = (std::log(s / k) + (r - q + 0.5 * sigma * sigma) * t) / sq;
    return s * std::exp(-q * t) * norm_pdf(d1) * std::sqrt(t);
}

double binomial_price(double s, double k, double t, double r, double q, double sigma, bool call,
                      int steps) {
    validate_bs(s, k, t, sigma);
    if (steps < 1)
        throw std::invalid_argument("binomial_price: steps must be >= 1");
    if (t == 0.0 || sigma == 0.0) return bs_price(s, k, t, r, q, sigma, call);
    const double dt = t / steps;
    const double u = std::exp(sigma * std::sqrt(dt));
    const double d = 1.0 / u;
    const double disc = std::exp(-r * dt);
    const double p = (std::exp((r - q) * dt) - d) / (u - d);
    if (!(0.0 < p && p < 1.0))
        throw std::invalid_argument(
            "binomial_price: risk-neutral probability outside (0,1); increase steps");
    // Terminal payoffs; identical arithmetic to the numpy reference:
    // st = s * u ** (2.0*j - steps), payoff = max((st-k) or (k-st), 0).
    std::vector<double> v(static_cast<std::size_t>(steps) + 1);
    for (int j = 0; j <= steps; ++j) {
        const double st = s * std::pow(u, 2.0 * j - steps);
        v[static_cast<std::size_t>(j)] = std::max(call ? (st - k) : (k - st), 0.0);
    }
    // Backward induction: v = disc * (p*v[1:] + (1-p)*v[:-1]).
    const double one_minus_p = 1.0 - p;
    for (int step = 0; step < steps; ++step) {
        const std::size_t len = v.size() - 1;
        for (std::size_t j = 0; j < len; ++j)
            v[j] = disc * (p * v[j + 1] + one_minus_p * v[j]);
        v.resize(len);
    }
    return v[0];
}

double price_bond(const Bond& bond, const Curve& curve) {
    double pv = bond.notional * curve.df(bond.maturity);
    for (double ti : bond.coupon_times()) pv += bond.coupon * bond.notional * curve.df(ti);
    return pv;
}

double price_payer_swap(const PayerSwap& swap, const Curve& curve) {
    double annuity = 0.0;
    for (double ti : swap.fixed_times()) annuity += curve.df(ti);
    return swap.notional * (1.0 - curve.df(swap.maturity)) -
           swap.fixed_rate * swap.notional * annuity;
}

double price_fx_forward(const FxForward& fwd, double spot, const Curve& curve_dom,
                        const Curve& curve_for) {
    if (spot <= 0.0 || !std::isfinite(spot))
        throw std::invalid_argument("price_fx_forward: spot must be positive");
    return fwd.notional *
           (spot * curve_for.df(fwd.maturity) - fwd.strike * curve_dom.df(fwd.maturity));
}

double price_equity_option(const EquityOption& opt, const Market& market) {
    const EquityQuote& quote = market.equity(opt.underlier);
    const double r = (opt.maturity > 0.0) ? market.curve(opt.currency).rate(opt.maturity) : 0.0;
    const double px = bs_price(quote.spot, opt.strike, opt.maturity, r, quote.div_yield,
                               quote.vol, opt.option_type == "call");
    return opt.position * opt.contracts * px;
}

double price_instrument(const Instrument& inst, const Market& market) {
    struct Visitor {
        const Market& m;
        double operator()(const Bond& b) const { return price_bond(b, m.curve(b.currency)); }
        double operator()(const PayerSwap& s) const {
            return price_payer_swap(s, m.curve(s.currency));
        }
        double operator()(const EquityOption& o) const { return price_equity_option(o, m); }
        double operator()(const FxForward& f) const {
            return price_fx_forward(f, m.fx_spot(f.pair), m.curve(f.domestic()),
                                    m.curve(f.foreign()));
        }
    };
    return std::visit(Visitor{market}, inst);
}

double price_portfolio(const std::vector<Instrument>& instruments, const Market& market) {
    double total = 0.0;
    for (const Instrument& i : instruments) total += price_instrument(i, market);
    return total;
}

}  // namespace frtb
