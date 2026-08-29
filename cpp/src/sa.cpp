#include "frtb/sa.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <stdexcept>

#include "frtb/pricers.hpp"

namespace frtb {

namespace {

/// Format a tenor like Python's f"{t:g}" (used as the GIRR factor key).
std::string fmt_g(double t) {
    char buf[32];
    std::snprintf(buf, sizeof buf, "%g", t);
    return buf;
}

double key_to_double(const std::string& k) {
    return std::strtod(k.c_str(), nullptr);
}

/// GIRR delta WS per currency bucket + tenor-index correlation lookups.
struct GirrStructs {
    BucketWs bucket_ws;
    IntraRhoFn intra_rho;
    GammaFn gamma;
};

GirrStructs girr_structs(const Sensitivities& sens, const SbmParams& params) {
    GirrStructs g;
    std::map<double, std::size_t> tenor_index;
    for (std::size_t i = 0; i < params.girr_tenors.size(); ++i)
        tenor_index[params.girr_tenors[i]] = i;
    for (const auto& [ccy, per_tenor] : sens.girr) {
        std::map<std::string, double>& ws = g.bucket_ws[ccy];
        for (const auto& [t, s] : per_tenor) ws[fmt_g(t)] = params.girr_rw(t) * s;
    }
    g.intra_rho = [&params, tenor_index](const std::string&, const std::string& k,
                                         const std::string& l) {
        return params.girr_rho_kl(tenor_index.at(key_to_double(k)),
                                  tenor_index.at(key_to_double(l)));
    };
    g.gamma = [&params](const std::string&, const std::string&) { return params.girr_gamma; };
    return g;
}

/// Equity delta or vega WS per bucket, factor key = underlier name.
struct EquityStructs {
    BucketWs bucket_ws;
    IntraRhoFn intra_rho;
    GammaFn gamma;
};

EquityStructs equity_structs(const std::map<std::string, double>& sens_map, const Market& market,
                             const SbmParams& params, bool vega) {
    EquityStructs e;
    for (const auto& [name, s] : sens_map) {
        const std::string& b = market.equity(name).bucket;
        const EquityBucketParams& p = params.equity_bucket(b);  // throws if not pinned
        const double rw = vega ? p.vega_rw : p.delta_rw;
        e.bucket_ws[b][name] = rw * s;
    }
    e.intra_rho = [&params](const std::string& bucket, const std::string&, const std::string&) {
        return params.equity_bucket(bucket).rho;
    };
    e.gamma = [&params](const std::string&, const std::string&) { return params.equity_gamma; };
    return e;
}

}  // namespace

SbmResult sbm_capital(const Sensitivities& sens, const Market& market, const SbmParams& params) {
    const double hi = params.scenario_high;
    const double lo = params.scenario_low;
    SbmResult result;

    // -- delta / vega -------------------------------------------------------
    GirrStructs girr = girr_structs(sens, params);
    EquityStructs eqd = equity_structs(sens.equity_delta, market, params, /*vega=*/false);
    EquityStructs eqv = equity_structs(sens.equity_vega, market, params, /*vega=*/true);
    BucketWs fx_ws;
    if (!sens.fx_delta.empty()) {
        std::map<std::string, double>& ws = fx_ws["FX"];  // single pinned bucket
        for (const auto& [pair, s] : sens.fx_delta) ws[pair] = params.fx_delta_rw * s;
    }
    IntraRhoFn fx_rho = [&params](const std::string&, const std::string&, const std::string&) {
        return params.fx_rho;
    };
    GammaFn fx_gamma = [&params](const std::string&, const std::string&) {
        return params.fx_gamma;
    };

    struct DvSpec {
        std::string rc, measure;
        const BucketWs* ws;
        const IntraRhoFn* rho;
        const GammaFn* gamma;
    };
    const BucketWs empty_ws;
    const DvSpec dv_specs[] = {
        {"girr", "delta", &girr.bucket_ws, &girr.intra_rho, &girr.gamma},
        // no IR-vol instruments in the bundled scope => GIRR vega == 0
        {"girr", "vega", &empty_ws, &girr.intra_rho, &girr.gamma},
        {"equity", "delta", &eqd.bucket_ws, &eqd.intra_rho, &eqd.gamma},
        {"equity", "vega", &eqv.bucket_ws, &eqv.intra_rho, &eqv.gamma},
        {"fx", "delta", &fx_ws, &fx_rho, &fx_gamma},
    };
    for (const DvSpec& spec : dv_specs) {
        for (const std::string& scen : SCENARIOS) {
            RiskClassCharge res = delta_vega_charge(*spec.ws, *spec.rho, *spec.gamma, scen, hi, lo);
            result.charges[spec.rc][spec.measure][scen] = res.charge;
            if (scen == "medium") result.kb_medium[spec.rc][spec.measure] = res.kb;
        }
    }

    // -- curvature ----------------------------------------------------------
    BucketCvr girr_cvr;
    FactorKeys girr_keys;
    for (const auto& [ccy, updn] : sens.girr_cvr) {
        girr_cvr[ccy] = {{updn.first}, {updn.second}};
        girr_keys[ccy] = {"crv"};  // one whole-curve factor per currency
    }
    BucketCvr eq_cvr;
    FactorKeys eq_keys;
    for (const auto& [name, updn] : sens.equity_cvr) {
        const std::string& b = market.equity(name).bucket;
        params.equity_bucket(b);  // validate the bucket is pinned
        eq_cvr[b].first.push_back(updn.first);
        eq_cvr[b].second.push_back(updn.second);
        eq_keys[b].push_back(name);
    }
    BucketCvr fx_cvr;
    FactorKeys fx_keys;
    fx_keys["FX"] = {};
    if (!sens.fx_cvr.empty()) {
        auto& [ups, dns] = fx_cvr["FX"];
        for (const auto& [pair, updn] : sens.fx_cvr) {
            ups.push_back(updn.first);
            dns.push_back(updn.second);
            fx_keys["FX"].push_back(pair);
        }
    }

    struct CrvSpec {
        std::string rc;
        const BucketCvr* cvr;
        const IntraRhoFn* rho;
        const GammaFn* gamma;
        const FactorKeys* keys;
    };
    const CrvSpec crv_specs[] = {
        {"girr", &girr_cvr, &girr.intra_rho, &girr.gamma, &girr_keys},
        {"equity", &eq_cvr, &eqd.intra_rho, &eqd.gamma, &eq_keys},
        {"fx", &fx_cvr, &fx_rho, &fx_gamma, &fx_keys},
    };
    for (const CrvSpec& spec : crv_specs) {
        for (const std::string& scen : SCENARIOS) {
            RiskClassCharge res =
                curvature_charge(*spec.cvr, *spec.rho, *spec.gamma, scen, *spec.keys, hi, lo);
            result.charges[spec.rc]["curvature"][scen] = res.charge;
            if (scen == "medium") result.kb_medium[spec.rc]["curvature"] = res.kb;
        }
    }

    // fx vega not modelled: pin to zero for all scenarios
    for (const std::string& scen : SCENARIOS) result.charges["fx"]["vega"][scen] = 0.0;

    // Scenario totals sum risk classes x measures in the pinned order.
    for (const std::string& scen : SCENARIOS) {
        double total = 0.0;
        for (const std::string& rc : RISK_CLASSES)
            for (const std::string& m : MEASURES) total += result.charges[rc][m][scen];
        result.scenario_totals[scen] = total;
    }
    result.capital = 0.0;
    bool first = true;
    for (const auto& kv : result.scenario_totals) {
        if (first || kv.second > result.capital) result.capital = kv.second;
        first = false;
    }
    return result;
}

DrcResult drc_charge(const std::vector<DrcPosition>& positions, const SbmParams& params) {
    // Net JTD per issuer in first-occurrence order (mirrors dict insertion
    // order in the reference — the sums below depend on it bit-wise).
    std::vector<std::pair<std::string, double>> net;
    std::vector<std::pair<std::string, std::string>> rating_of;
    auto find_net = [&](const std::string& issuer) -> double* {
        for (auto& kv : net)
            if (kv.first == issuer) return &kv.second;
        return nullptr;
    };
    auto find_rating = [&](const std::string& issuer) -> const std::string* {
        for (const auto& kv : rating_of)
            if (kv.first == issuer) return &kv.second;
        return nullptr;
    };
    for (const DrcPosition& p : positions) {
        if (double* slot = find_net(p.issuer))
            *slot += p.jtd();
        else
            net.emplace_back(p.issuer, p.jtd());
        if (const std::string* prev = find_rating(p.issuer)) {
            if (*prev != p.rating)
                throw std::invalid_argument("drc_charge: issuer '" + p.issuer +
                                            "' has inconsistent ratings ('" + *prev + "' vs '" +
                                            p.rating + "')");
        } else {
            rating_of.emplace_back(p.issuer, p.rating);
        }
    }
    double long_sum = 0.0;
    double short_sum = 0.0;
    for (const auto& kv : net)
        if (kv.second > 0.0) long_sum += kv.second;
    for (const auto& kv : net)
        if (kv.second < 0.0) short_sum += -kv.second;
    const double denom = long_sum + short_sum;
    const double hbr = denom > 0.0 ? long_sum / denom : 1.0;
    double weighted_long = 0.0;
    double weighted_short = 0.0;
    for (const auto& kv : net)
        if (kv.second > 0.0) weighted_long += params.drc_rw(*find_rating(kv.first)) * kv.second;
    for (const auto& kv : net)
        if (kv.second < 0.0) weighted_short += params.drc_rw(*find_rating(kv.first)) * (-kv.second);
    const double charge = std::max(0.0, weighted_long - hbr * weighted_short);
    return {charge, hbr, net, long_sum, short_sum};
}

std::vector<DrcPosition> drc_positions_from_instruments(
    const std::vector<Instrument>& instruments, const Market& market) {
    std::vector<DrcPosition> out;
    for (const Instrument& inst : instruments)
        if (const auto* bond = std::get_if<Bond>(&inst)) {
            double mv = price_bond(*bond, market.curve(bond->currency));
            out.push_back({bond->issuer, bond->rating, bond->notional, mv, bond->lgd});
        }
    return out;
}

double rrao_charge(const std::vector<Instrument>& instruments, const SbmParams& params) {
    double total = 0.0;
    for (const Instrument& inst : instruments) {
        const std::optional<RraoFlag>& flag = instrument_rrao(inst);
        if (flag) total += params.rrao_rate(flag->category) * flag->notional;
    }
    return total;
}

SaScope compute_sa(const std::vector<Instrument>& instruments, const Market& market,
                   const SbmParams& params) {
    Sensitivities sens = compute_sensitivities(instruments, market, params);
    SaScope scope;
    scope.sbm = sbm_capital(sens, market, params);
    DrcResult drc = drc_charge(drc_positions_from_instruments(instruments, market), params);
    scope.drc = drc.charge;
    scope.drc_hbr = drc.hbr;
    scope.rrao = rrao_charge(instruments, params);
    return scope;
}

}  // namespace frtb
