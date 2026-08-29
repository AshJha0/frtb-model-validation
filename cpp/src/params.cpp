#include "frtb/params.hpp"

#include <cmath>
#include <cstdlib>
#include <stdexcept>

#include "frtb/json.hpp"

namespace frtb {

double SbmParams::girr_rw(double tenor) const {
    auto it = girr_delta_rw.find(tenor);
    if (it == girr_delta_rw.end())
        throw std::invalid_argument("SbmParams: no GIRR delta risk weight for tenor " +
                                    std::to_string(tenor));
    return it->second;
}

const EquityBucketParams& SbmParams::equity_bucket(const std::string& bucket) const {
    auto it = equity_buckets.find(bucket);
    if (it == equity_buckets.end())
        throw std::invalid_argument("SbmParams: unknown equity bucket '" + bucket + "'");
    return it->second;
}

double SbmParams::drc_rw(const std::string& rating) const {
    auto it = drc_rw_by_rating.find(rating);
    if (it == drc_rw_by_rating.end())
        throw std::invalid_argument("SbmParams: no DRC risk weight for rating '" + rating + "'");
    return it->second;
}

double SbmParams::rrao_rate(const std::string& category) const {
    auto it = rrao_rates.find(category);
    if (it == rrao_rates.end())
        throw std::invalid_argument("SbmParams: unknown RRAO category '" + category + "'");
    return it->second;
}

SbmParams SbmParams::with_girr_delta_rw_scaled(double factor) const {
    if (factor <= 0.0 || !std::isfinite(factor))
        throw std::invalid_argument("with_girr_delta_rw_scaled: bad factor");
    SbmParams p = *this;
    for (auto& kv : p.girr_delta_rw) kv.second = girr_delta_rw.at(kv.first) * factor;
    return p;
}

namespace {

/// Returns a pointer (not a reference) so GCC's -Wdangling-reference heuristic
/// does not misfire on the temporary string arguments.
const json::Value* require_ptr(const json::Value& d, const std::string& key,
                               const std::string& ctx) {
    if (!d.has(key))
        throw std::invalid_argument("sbm_params.json: missing '" + key + "' in " + ctx);
    return &d.at(key);
}

#define require(d, key, ctx) (*require_ptr((d), (key), (ctx)))

double key_to_double(const std::string& k) {
    char* end = nullptr;
    double v = std::strtod(k.c_str(), &end);
    if (k.empty() || end != k.c_str() + k.size())
        throw std::invalid_argument("sbm_params.json: bad numeric key '" + k + "'");
    return v;
}

}  // namespace

SbmParams load_params(const std::string& path) {
    json::Value raw = json::parse_file(path);
    SbmParams p;

    const json::Value& girr = require(raw, "girr", "root");
    for (const json::Value& t : require(girr, "tenors", "girr").array)
        p.girr_tenors.push_back(t.as_number());
    for (const auto& [k, v] : require(girr, "delta_rw", "girr").object)
        p.girr_delta_rw[key_to_double(k)] = v.as_number();
    for (double t : p.girr_tenors)
        if (!p.girr_delta_rw.count(t))
            throw std::invalid_argument("sbm_params.json: girr.delta_rw missing tenor " +
                                        std::to_string(t));
    const json::Value& rho_raw = require(girr, "delta_rho", "girr");
    const std::size_t n = p.girr_tenors.size();
    if (rho_raw.array.size() != n)
        throw std::invalid_argument(
            "sbm_params.json: girr.delta_rho must be a square tenor x tenor matrix");
    for (const json::Value& row : rho_raw.array) {
        if (row.array.size() != n)
            throw std::invalid_argument(
                "sbm_params.json: girr.delta_rho must be a square tenor x tenor matrix");
        std::vector<double> r;
        for (const json::Value& x : row.array) r.push_back(x.as_number());
        p.girr_rho.push_back(std::move(r));
    }
    for (std::size_t i = 0; i < n; ++i)
        if (std::abs(p.girr_rho[i][i] - 1.0) > 1e-12)
            throw std::invalid_argument("sbm_params.json: girr.delta_rho diagonal must be 1");
    p.girr_vega_rw = require(girr, "vega_rw", "girr").as_number();
    p.girr_curvature_rw = require(girr, "curvature_rw", "girr").as_number();
    p.girr_gamma = require(girr, "gamma", "girr").as_number();

    const json::Value& eq = require(raw, "equity", "root");
    for (const auto& [b, bp] : require(eq, "buckets", "equity").object) {
        EquityBucketParams e;
        e.delta_rw = require(bp, "delta_rw", "equity bucket " + b).as_number();
        e.vega_rw = require(bp, "vega_rw", "equity bucket " + b).as_number();
        e.rho = require(bp, "rho", "equity bucket " + b).as_number();
        p.equity_buckets[b] = e;
    }
    p.equity_gamma = require(eq, "gamma", "equity").as_number();

    const json::Value& fx = require(raw, "fx", "root");
    p.fx_delta_rw = require(fx, "delta_rw", "fx").as_number();
    p.fx_rho = require(fx, "rho", "fx").as_number();
    p.fx_gamma = require(fx, "gamma", "fx").as_number();

    const json::Value& scen = require(raw, "scenarios", "root");
    p.scenario_high = require(scen, "high", "scenarios").as_number();
    p.scenario_low = require(scen, "low", "scenarios").as_number();

    const json::Value& drc = require(raw, "drc", "root");
    for (const auto& [k, v] : require(drc, "rw_by_rating", "drc").object)
        p.drc_rw_by_rating[k] = v.as_number();

    const json::Value& rrao = require(raw, "rrao", "root");
    for (const auto& [k, v] : rrao.object)
        if (v.is_number()) p.rrao_rates[k] = v.as_number();

    const json::Value& ima = require(raw, "ima", "root");
    p.ima_alpha = require(ima, "alpha", "ima").as_number();
    p.ima_rho = require(ima, "rho", "ima").as_number();
    for (const json::Value& x : require(ima, "lh_ladder", "ima").array)
        p.lh_ladder.push_back(static_cast<int>(x.as_number()));
    for (const auto& [k, v] : require(ima, "category_lh", "ima").object)
        p.category_lh[k] = static_cast<int>(v.as_number());
    const json::Value& bt = require(ima, "backtest_multiplier", "ima");
    for (const auto& [k, v] : require(bt, "amber", "backtest").object)
        p.backtest_amber_multipliers[std::stoi(k)] = v.as_number();
    p.backtest_base_multiplier = require(bt, "base", "backtest").as_number();
    p.backtest_red_multiplier = require(bt, "red", "backtest").as_number();
    const json::Value& plat = require(ima, "plat", "ima");
    p.plat_spearman_green = require(plat, "spearman_green", "plat").as_number();
    p.plat_spearman_amber = require(plat, "spearman_amber", "plat").as_number();
    p.plat_ks_green = require(plat, "ks_green", "plat").as_number();
    p.plat_ks_amber = require(plat, "ks_amber", "plat").as_number();
    p.plat_k_surcharge = require(plat, "k_surcharge", "plat").as_number();
    return p;
}

}  // namespace frtb
