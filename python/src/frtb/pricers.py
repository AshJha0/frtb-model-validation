"""Self-contained pricer kit: Black-Scholes (with edge cases), CRR binomial
benchmark, bond / swap-proxy / FX-forward pricing off zero curves.

All prices are deterministic closed-form / lattice computations — no RNG.
"""
from __future__ import annotations

import math
from typing import Sequence

import numpy as np

from .instruments import Bond, EquityOption, FxForward, Instrument, PayerSwap
from .market import Curve, Market


def norm_cdf(x: float) -> float:
    """Standard normal CDF via erf (double precision, no scipy dependency)."""
    return 0.5 * (1.0 + math.erf(x / math.sqrt(2.0)))


def norm_pdf(x: float) -> float:
    """Standard normal PDF."""
    return math.exp(-0.5 * x * x) / math.sqrt(2.0 * math.pi)


def _validate_bs(s: float, k: float, t: float, sigma: float) -> None:
    for name, v in (("spot", s), ("strike", k), ("maturity", t), ("sigma", sigma)):
        if not math.isfinite(v):
            raise ValueError(f"black_scholes: {name} must be finite, got {v}")
    if s <= 0.0 or k <= 0.0:
        raise ValueError(f"black_scholes: spot/strike must be positive (s={s}, k={k})")
    if t < 0.0:
        raise ValueError(f"black_scholes: maturity must be >= 0, got {t}")
    if sigma < 0.0:
        raise ValueError(f"black_scholes: sigma must be >= 0, got {sigma}")


def bs_price(s: float, k: float, t: float, r: float, q: float, sigma: float, call: bool) -> float:
    """Black-Scholes price with continuous dividend yield q.

    Edge cases: t == 0 returns intrinsic value; sigma == 0 returns the
    discounted deterministic payoff max(+/-(S e^{-qT} - K e^{-rT}), 0).
    """
    _validate_bs(s, k, t, sigma)
    sign = 1.0 if call else -1.0
    if t == 0.0:
        return max(sign * (s - k), 0.0)
    if sigma == 0.0:
        return max(sign * (s * math.exp(-q * t) - k * math.exp(-r * t)), 0.0)
    sq = sigma * math.sqrt(t)
    d1 = (math.log(s / k) + (r - q + 0.5 * sigma * sigma) * t) / sq
    d2 = d1 - sq
    return sign * (s * math.exp(-q * t) * norm_cdf(sign * d1)
                   - k * math.exp(-r * t) * norm_cdf(sign * d2))


def bs_delta(s: float, k: float, t: float, r: float, q: float, sigma: float, call: bool) -> float:
    """Analytic Black-Scholes spot delta dV/dS (used by the validation FD check)."""
    _validate_bs(s, k, t, sigma)
    sign = 1.0 if call else -1.0
    if t == 0.0 or sigma == 0.0:
        # deterministic payoff: delta is a step function; return the a.e. value
        fwd_itm = (s * math.exp(-q * t) - k * math.exp(-r * t)) if t > 0 else (s - k)
        return math.exp(-q * t) * (1.0 if sign * fwd_itm > 0 else 0.0) * sign
    sq = sigma * math.sqrt(t)
    d1 = (math.log(s / k) + (r - q + 0.5 * sigma * sigma) * t) / sq
    return sign * math.exp(-q * t) * norm_cdf(sign * d1)


def bs_vega(s: float, k: float, t: float, r: float, q: float, sigma: float) -> float:
    """Analytic Black-Scholes vega dV/dsigma (same for call and put)."""
    _validate_bs(s, k, t, sigma)
    if t == 0.0 or sigma == 0.0:
        return 0.0
    sq = sigma * math.sqrt(t)
    d1 = (math.log(s / k) + (r - q + 0.5 * sigma * sigma) * t) / sq
    return s * math.exp(-q * t) * norm_pdf(d1) * math.sqrt(t)


def binomial_price(s: float, k: float, t: float, r: float, q: float, sigma: float,
                   call: bool, steps: int) -> float:
    """European option price on a CRR (Cox-Ross-Rubinstein) lattice.

    Used only as the independent benchmark pricer in the validation framework
    (converges to Black-Scholes as steps -> inf).
    """
    _validate_bs(s, k, t, sigma)
    if steps < 1:
        raise ValueError(f"binomial_price: steps must be >= 1, got {steps}")
    if t == 0.0 or sigma == 0.0:
        return bs_price(s, k, t, r, q, sigma, call)
    dt = t / steps
    u = math.exp(sigma * math.sqrt(dt))
    d = 1.0 / u
    disc = math.exp(-r * dt)
    p = (math.exp((r - q) * dt) - d) / (u - d)
    if not (0.0 < p < 1.0):
        raise ValueError("binomial_price: risk-neutral probability outside (0,1); increase steps")
    j = np.arange(steps + 1)
    st = s * u ** (2.0 * j - steps)
    v = np.maximum((st - k) if call else (k - st), 0.0)
    for _ in range(steps):
        v = disc * (p * v[1:] + (1.0 - p) * v[:-1])
    return float(v[0])


def price_bond(bond: Bond, curve: Curve) -> float:
    """Dirty PV of an annual-pay bullet bond: sum c*N*DF(t_i) + N*DF(T)."""
    pv = bond.notional * curve.df(bond.maturity)
    for ti in bond.coupon_times():
        pv += bond.coupon * bond.notional * curve.df(ti)
    return pv


def price_payer_swap(swap: PayerSwap, curve: Curve) -> float:
    """Payer swap proxy: V = N*(1 - DF(T)) - c*N*sum_i DF(t_i)."""
    annuity = sum(curve.df(ti) for ti in swap.fixed_times())
    return swap.notional * (1.0 - curve.df(swap.maturity)) - swap.fixed_rate * swap.notional * annuity


def price_fx_forward(fwd: FxForward, spot: float, curve_dom: Curve, curve_for: Curve) -> float:
    """FX forward value in domestic ccy: N * (S*DF_for(T) - K*DF_dom(T))."""
    if spot <= 0.0 or not math.isfinite(spot):
        raise ValueError(f"price_fx_forward: spot must be positive, got {spot}")
    return fwd.notional * (spot * curve_for.df(fwd.maturity) - fwd.strike * curve_dom.df(fwd.maturity))


def price_equity_option(opt: EquityOption, market: Market) -> float:
    """Position value of a European equity option (BS with dividend yield)."""
    quote = market.equity(opt.underlier)
    r = market.curve(opt.currency).rate(opt.maturity) if opt.maturity > 0 else 0.0
    px = bs_price(quote.spot, opt.strike, opt.maturity, r, quote.div_yield, quote.vol,
                  opt.option_type == "call")
    return opt.position * opt.contracts * px


def price_instrument(inst: Instrument, market: Market) -> float:
    """Dispatch: PV of a single instrument under the given market snapshot."""
    if isinstance(inst, Bond):
        return price_bond(inst, market.curve(inst.currency))
    if isinstance(inst, PayerSwap):
        return price_payer_swap(inst, market.curve(inst.currency))
    if isinstance(inst, EquityOption):
        return price_equity_option(inst, market)
    if isinstance(inst, FxForward):
        return price_fx_forward(inst, market.fx_spot(inst.pair),
                                market.curve(inst.domestic), market.curve(inst.foreign))
    raise ValueError(f"price_instrument: unsupported instrument {type(inst).__name__}")


def price_portfolio(instruments: Sequence[Instrument], market: Market) -> float:
    """Sum of instrument PVs (0.0 for an empty portfolio)."""
    return sum(price_instrument(i, market) for i in instruments)
