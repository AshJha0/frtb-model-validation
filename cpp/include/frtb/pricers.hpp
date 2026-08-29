/// \file pricers.hpp
/// \brief Self-contained pricer kit: Black-Scholes (with edge cases), CRR
/// binomial benchmark, bond / swap-proxy / FX-forward pricing off zero curves.
///
/// All prices are deterministic closed-form / lattice computations — no RNG.

#pragma once

#include <vector>

#include "frtb/instruments.hpp"
#include "frtb/market.hpp"

namespace frtb {

/// Standard normal CDF via erf (double precision).
double norm_cdf(double x);

/// Standard normal PDF.
double norm_pdf(double x);

/// Black-Scholes price with continuous dividend yield q.
///
/// Edge cases: t == 0 returns intrinsic value; sigma == 0 returns the
/// discounted deterministic payoff max(+/-(S e^{-qT} - K e^{-rT}), 0).
/// \throws std::invalid_argument on non-finite/negative-domain inputs.
double bs_price(double s, double k, double t, double r, double q, double sigma, bool call);

/// Analytic Black-Scholes spot delta dV/dS (used by the validation FD check).
double bs_delta(double s, double k, double t, double r, double q, double sigma, bool call);

/// Analytic Black-Scholes vega dV/dsigma (same for call and put).
double bs_vega(double s, double k, double t, double r, double q, double sigma);

/// European option price on a CRR (Cox-Ross-Rubinstein) lattice.
///
/// Used only as the independent benchmark pricer in the validation framework
/// (converges to Black-Scholes as steps -> inf).
/// \throws std::invalid_argument on bad inputs or a risk-neutral probability
/// outside (0,1).
double binomial_price(double s, double k, double t, double r, double q, double sigma, bool call,
                      int steps);

/// Dirty PV of an annual-pay bullet bond: sum c*N*DF(t_i) + N*DF(T).
double price_bond(const Bond& bond, const Curve& curve);

/// Payer swap proxy: V = N*(1 - DF(T)) - c*N*sum_i DF(t_i).
double price_payer_swap(const PayerSwap& swap, const Curve& curve);

/// FX forward value in domestic ccy: N * (S*DF_for(T) - K*DF_dom(T)).
/// \throws std::invalid_argument on a non-positive spot.
double price_fx_forward(const FxForward& fwd, double spot, const Curve& curve_dom,
                        const Curve& curve_for);

/// Position value of a European equity option (BS with dividend yield).
double price_equity_option(const EquityOption& opt, const Market& market);

/// Dispatch: PV of a single instrument under the given market snapshot.
double price_instrument(const Instrument& inst, const Market& market);

/// Sum of instrument PVs (0.0 for an empty portfolio).
double price_portfolio(const std::vector<Instrument>& instruments, const Market& market);

}  // namespace frtb
