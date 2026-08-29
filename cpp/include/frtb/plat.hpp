/// \file plat.hpp
/// \brief P&L attribution test (PLAT): Spearman + KS metrics, traffic-light
/// zone, Amber capital surcharge.
///
/// Pinned thresholds:
///   Green:  spearman >= 0.85  AND  KS <= 0.09
///   Red:    spearman <  0.80  OR   KS >  0.12
///   Amber:  everything in between.
/// Constant P&L on either side leaves the Spearman correlation undefined; the
/// desk is assigned RED in that case (documented conservative convention).
///
/// Amber surcharge (pinned k = 0.5 interpolation between IMA and SA):
///   surcharge = k * max(0, SA_desk - IMA_desk_core)

#pragma once

#include <optional>
#include <string>
#include <vector>

#include "frtb/params.hpp"

namespace frtb {

/// PLAT outcome; metrics are nullopt when undefined (constant series -> Red).
struct PlatResult {
    std::optional<double> spearman;
    std::optional<double> ks;
    std::string zone;  ///< "green" | "amber" | "red"
};

/// Map (Spearman, KS) to a PLAT zone using the pinned thresholds.
/// \throws std::invalid_argument on non-finite metrics.
std::string plat_zone_from_metrics(double spearman_rho, double ks_stat, const SbmParams& params);

/// Run the PLAT on hypothetical vs risk-theoretical P&L.
///
/// A constant series on either side makes the rank correlation undefined:
/// the result is Red with metrics = nullopt (documented edge case).
/// \throws std::invalid_argument on length mismatch or < 3 observations.
PlatResult plat_test(const std::vector<double>& hypo, const std::vector<double>& rtpl,
                     const SbmParams& params);

/// Amber-zone capital surcharge: k * max(0, SA - IMA_core); 0 otherwise.
///
/// Red-zone desks fall back to SA entirely (handled by the caller/report);
/// the surcharge formula itself applies only to Amber.
/// \throws std::invalid_argument on an unknown zone or negative capital.
double plat_surcharge(const std::string& zone, double sa_capital, double ima_capital_core,
                      const SbmParams& params);

}  // namespace frtb
