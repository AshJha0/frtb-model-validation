#include "frtb/sbm.hpp"

#include <algorithm>
#include <cmath>
#include <stdexcept>

namespace frtb {

double scale_rho(double rho, const std::string& scenario, double high, double low) {
    if (scenario == "medium") return rho;
    if (scenario == "high") return std::min(high * rho, 1.0);
    if (scenario == "low") return low * rho;
    throw std::invalid_argument("scale_rho: unknown scenario '" + scenario + "'");
}

double bucket_kb(const std::vector<double>& ws,
                 const std::function<double(std::size_t, std::size_t)>& rho) {
    for (double w : ws)
        if (!std::isfinite(w))
            throw std::invalid_argument("bucket_kb: weighted sensitivities must be finite");
    double total = 0.0;
    for (double w : ws) total += w * w;
    const std::size_t n = ws.size();
    for (std::size_t k = 0; k < n; ++k)
        for (std::size_t l = 0; l < n; ++l)
            if (k != l) total += rho(k, l) * ws[k] * ws[l];
    // The max(0,.) guards against negative rounding of the quadratic form.
    return std::sqrt(std::max(0.0, total));
}

AggregateResult aggregate_buckets(
    const std::map<std::string, double>& kb, const std::map<std::string, double>& ws_sum,
    const std::function<double(const std::string&, const std::string&)>& gamma) {
    if (kb.size() != ws_sum.size())
        throw std::invalid_argument("aggregate_buckets: kb and ws_sum must cover the same buckets");
    std::vector<std::string> buckets;  // std::map keys are already sorted
    for (const auto& kv : kb) {
        if (!ws_sum.count(kv.first))
            throw std::invalid_argument(
                "aggregate_buckets: kb and ws_sum must cover the same buckets");
        buckets.push_back(kv.first);
    }

    auto inner = [&](const std::map<std::string, double>& sb) {
        double total = 0.0;
        for (const std::string& b : buckets) {
            double k = kb.at(b);
            total += k * k;
        }
        for (const std::string& b : buckets)
            for (const std::string& c : buckets)
                if (b != c) total += gamma(b, c) * sb.at(b) * sb.at(c);
        return total;
    };

    std::map<std::string, double> sb0 = ws_sum;
    double in0 = inner(sb0);
    if (in0 >= 0.0) return {std::sqrt(in0), false, sb0};
    // S_b fallback: clamp the bucket sums into [-K_b, K_b] and recompute once.
    std::map<std::string, double> sb1;
    for (const std::string& b : buckets)
        sb1[b] = std::max(std::min(ws_sum.at(b), kb.at(b)), -kb.at(b));
    double in1 = inner(sb1);
    return {std::sqrt(std::max(0.0, in1)), true, sb1};
}

RiskClassCharge delta_vega_charge(const BucketWs& bucket_ws, const IntraRhoFn& intra_rho,
                                  const GammaFn& gamma, const std::string& scenario,
                                  double scenario_high, double scenario_low) {
    std::map<std::string, double> kb;
    std::map<std::string, double> ws_sum;
    for (const auto& [b, factors] : bucket_ws) {
        // std::map iteration = lexicographic key order = Python's sorted().
        std::vector<std::string> keys;
        std::vector<double> ws;
        for (const auto& kv : factors) {
            keys.push_back(kv.first);
            ws.push_back(kv.second);
        }
        const std::string& bucket = b;
        auto rho_fn = [&](std::size_t i, std::size_t j) {
            return scale_rho(intra_rho(bucket, keys[i], keys[j]), scenario, scenario_high,
                             scenario_low);
        };
        kb[b] = bucket_kb(ws, rho_fn);
        double s = 0.0;
        for (double w : ws) s += w;
        ws_sum[b] = s;
    }

    if (kb.empty()) return {0.0, {}, false};

    auto gamma_fn = [&](const std::string& b, const std::string& c) {
        return scale_rho(gamma(b, c), scenario, scenario_high, scenario_low);
    };
    AggregateResult agg = aggregate_buckets(kb, ws_sum, gamma_fn);
    return {agg.charge, kb, agg.used_fallback};
}

double psi(double a, double b) { return (a < 0.0 && b < 0.0) ? 0.0 : 1.0; }

std::pair<double, double> curvature_bucket_kb(
    const std::vector<double>& cvr_up, const std::vector<double>& cvr_dn,
    const std::function<double(std::size_t, std::size_t)>& rho) {
    if (cvr_up.size() != cvr_dn.size())
        throw std::invalid_argument("curvature_bucket_kb: up/down CVR lists must match");

    auto side = [&](const std::vector<double>& cvr) {
        double total = 0.0;
        for (double c : cvr) {
            double m = std::max(c, 0.0);
            total += m * m;
        }
        const std::size_t n = cvr.size();
        for (std::size_t k = 0; k < n; ++k)
            for (std::size_t l = 0; l < n; ++l)
                if (k != l) total += rho(k, l) * cvr[k] * cvr[l] * psi(cvr[k], cvr[l]);
        return std::sqrt(std::max(0.0, total));
    };

    const double k_up = side(cvr_up);
    const double k_dn = side(cvr_dn);
    auto sum = [](const std::vector<double>& v) {
        double s = 0.0;
        for (double x : v) s += x;
        return s;
    };
    if (k_up >= k_dn) return {k_up, sum(cvr_up)};  // ties -> up side (pinned)
    return {k_dn, sum(cvr_dn)};
}

RiskClassCharge curvature_charge(const BucketCvr& bucket_cvr, const IntraRhoFn& intra_rho,
                                 const GammaFn& gamma, const std::string& scenario,
                                 const FactorKeys& factor_keys, double scenario_high,
                                 double scenario_low) {
    std::map<std::string, double> kb;
    std::map<std::string, double> sb;
    for (const auto& [b, updn] : bucket_cvr) {
        const std::vector<std::string>& keys = factor_keys.at(b);
        if (keys.size() != updn.first.size())
            throw std::invalid_argument("curvature_charge: factor keys mismatch in bucket '" + b +
                                        "'");
        const std::string& bucket = b;
        auto rho_fn = [&](std::size_t i, std::size_t j) {
            double r = scale_rho(intra_rho(bucket, keys[i], keys[j]), scenario, scenario_high,
                                 scenario_low);
            return r * r;  // curvature correlation = scenario-scaled delta rho squared
        };
        auto [k, s] = curvature_bucket_kb(updn.first, updn.second, rho_fn);
        kb[b] = k;
        sb[b] = s;
    }

    if (kb.empty()) return {0.0, {}, false};

    std::vector<std::string> buckets;
    for (const auto& kv : kb) buckets.push_back(kv.first);
    double total = 0.0;
    for (const std::string& b : buckets) {
        double k = kb.at(b);
        total += k * k;
    }
    for (const std::string& b : buckets)
        for (const std::string& c : buckets)
            if (b != c) {
                double g = scale_rho(gamma(b, c), scenario, scenario_high, scenario_low);
                total += (g * g) * sb.at(b) * sb.at(c) * psi(sb.at(b), sb.at(c));
            }
    return {std::sqrt(std::max(0.0, total)), kb, false};
}

}  // namespace frtb
