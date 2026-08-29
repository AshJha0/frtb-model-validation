#include "frtb/sensitivities.hpp"

#include <cmath>
#include <set>

#include "frtb/pricers.hpp"

namespace frtb {

namespace {

/// Sensitivities below this (absolute) are treated as zero.
constexpr double kZeroTol = 1e-9;

}  // namespace

Sensitivities compute_sensitivities(const std::vector<Instrument>& instruments,
                                    const Market& market, const SbmParams& params) {
    const double base = price_portfolio(instruments, market);
    Sensitivities out;

    // ---- GIRR delta: bump each curve node of each currency by 1bp --------
    for (const auto& [ccy, curve] : market.curves) {
        (void)curve;
        std::map<double, double> per_tenor;
        bool any_nonzero = false;
        for (double tenor : params.girr_tenors) {
            Market bumped = market.bump_curve_node(ccy, tenor, GIRR_BUMP);
            double s = (price_portfolio(instruments, bumped) - base) / GIRR_BUMP;
            if (std::abs(s) > kZeroTol) any_nonzero = true;
            per_tenor[tenor] = s;
        }
        if (any_nonzero) out.girr[ccy] = std::move(per_tenor);
    }

    // ---- Equity delta & vega ---------------------------------------------
    std::set<std::string> names;
    std::set<std::string> pairs;
    for (const Instrument& inst : instruments) {
        if (const auto* opt = std::get_if<EquityOption>(&inst)) names.insert(opt->underlier);
        if (const auto* fwd = std::get_if<FxForward>(&inst)) pairs.insert(fwd->pair);
    }
    for (const std::string& name : names) {
        const EquityQuote& q = market.equity(name);
        double s_d =
            (price_portfolio(instruments, market.bump_equity_spot(name, EQ_SPOT_BUMP)) - base) /
            EQ_SPOT_BUMP;
        double raw_vega =
            (price_portfolio(instruments, market.bump_equity_vol(name, VOL_BUMP)) - base) /
            VOL_BUMP;
        double s_v = raw_vega * q.vol;
        if (std::abs(s_d) > kZeroTol) out.equity_delta[name] = s_d;
        if (std::abs(s_v) > kZeroTol) out.equity_vega[name] = s_v;
    }

    // ---- FX delta ---------------------------------------------------------
    for (const std::string& pair : pairs) {
        double s = (price_portfolio(instruments, market.bump_fx(pair, FX_BUMP)) - base) / FX_BUMP;
        if (std::abs(s) > kZeroTol) out.fx_delta[pair] = s;
    }

    // ---- Curvature --------------------------------------------------------
    const double rw_c = params.girr_curvature_rw;
    for (const auto& [ccy, per_tenor] : out.girr) {
        double slope = 0.0;  // sum of delta sensitivities over tenors
        for (const auto& kv : per_tenor) slope += kv.second;
        double v_up = price_portfolio(instruments, market.bump_curve_parallel(ccy, rw_c));
        double v_dn = price_portfolio(instruments, market.bump_curve_parallel(ccy, -rw_c));
        out.girr_cvr[ccy] = {-(v_up - base - rw_c * slope), -(v_dn - base + rw_c * slope)};
    }

    for (const std::string& name : names) {
        auto it = out.equity_delta.find(name);
        if (it == out.equity_delta.end()) continue;
        const double rw = params.equity_bucket(market.equity(name).bucket).delta_rw;
        const double s = it->second;
        double v_up = price_portfolio(instruments, market.bump_equity_spot(name, rw));
        double v_dn = price_portfolio(instruments, market.bump_equity_spot(name, -rw));
        out.equity_cvr[name] = {-(v_up - base - rw * s), -(v_dn - base + rw * s)};
    }

    for (const std::string& pair : pairs) {
        auto it = out.fx_delta.find(pair);
        if (it == out.fx_delta.end()) continue;
        const double rw = params.fx_delta_rw;
        const double s = it->second;
        double v_up = price_portfolio(instruments, market.bump_fx(pair, rw));
        double v_dn = price_portfolio(instruments, market.bump_fx(pair, -rw));
        out.fx_cvr[pair] = {-(v_up - base - rw * s), -(v_dn - base + rw * s)};
    }

    return out;
}

}  // namespace frtb
