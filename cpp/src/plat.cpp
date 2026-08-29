#include "frtb/plat.hpp"

#include <algorithm>
#include <cmath>
#include <stdexcept>

#include "frtb/stats.hpp"

namespace frtb {

std::string plat_zone_from_metrics(double spearman_rho, double ks_stat, const SbmParams& params) {
    if (!(std::isfinite(spearman_rho) && std::isfinite(ks_stat)))
        throw std::invalid_argument("plat_zone_from_metrics: metrics must be finite");
    if (spearman_rho < params.plat_spearman_amber || ks_stat > params.plat_ks_amber) return "red";
    if (spearman_rho >= params.plat_spearman_green && ks_stat <= params.plat_ks_green)
        return "green";
    return "amber";
}

PlatResult plat_test(const std::vector<double>& hypo, const std::vector<double>& rtpl,
                     const SbmParams& params) {
    if (hypo.size() != rtpl.size())
        throw std::invalid_argument("plat_test: series length mismatch");
    if (hypo.size() < 3)
        throw std::invalid_argument("plat_test: need at least 3 observations");
    double rho = 0.0;
    try {
        rho = spearman(hypo, rtpl);
    } catch (const std::invalid_argument&) {
        // constant series -> correlation undefined -> Red (conservative)
        return {std::nullopt, std::nullopt, "red"};
    }
    const double ks = ks_statistic(hypo, rtpl);
    return {rho, ks, plat_zone_from_metrics(rho, ks, params)};
}

double plat_surcharge(const std::string& zone, double sa_capital, double ima_capital_core,
                      const SbmParams& params) {
    if (zone != "green" && zone != "amber" && zone != "red")
        throw std::invalid_argument("plat_surcharge: unknown zone '" + zone + "'");
    if (!std::isfinite(sa_capital) || sa_capital < 0.0)
        throw std::invalid_argument("plat_surcharge: sa_capital must be >= 0 and finite");
    if (!std::isfinite(ima_capital_core) || ima_capital_core < 0.0)
        throw std::invalid_argument("plat_surcharge: ima_capital_core must be >= 0 and finite");
    if (zone != "amber") return 0.0;
    return params.plat_k_surcharge * std::max(0.0, sa_capital - ima_capital_core);
}

}  // namespace frtb
