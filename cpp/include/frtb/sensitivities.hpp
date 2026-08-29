/// \file sensitivities.hpp
/// \brief Bump-and-revalue sensitivities with pinned bump sizes.
///
/// Pinned bumps (documented in API_SPEC.md):
///  * GIRR delta:   +1bp absolute bump of one curve node;  s = (V+ - V) / 1e-4
///                  (sensitivity is expressed per unit of rate, dV/dr).
///  * Equity delta: +1% relative spot bump;                s = (V+ - V) / 0.01
///                  (S * dV/dS, the FRTB relative-shift convention).
///  * Equity vega:  +1 vol point absolute bump;            raw = (V+ - V)/0.01,
///                  WS uses s = raw * sigma (FRTB vega = vega * implied vol).
///  * FX delta:     +1% relative spot bump;                s = (V+ - V) / 0.01.
///  * Curvature:    full risk-weight shock up/down (parallel for GIRR curves,
///                  relative for equity/FX spots) with the delta term stripped:
///                  CVR+ = -(V_up - V - RW*s),  CVR- = -(V_dn - V + RW*s).

#pragma once

#include <map>
#include <string>
#include <utility>
#include <vector>

#include "frtb/instruments.hpp"
#include "frtb/market.hpp"
#include "frtb/params.hpp"

namespace frtb {

/// Pinned bump sizes.
inline constexpr double GIRR_BUMP = 1e-4;   ///< 1bp absolute zero-rate bump
inline constexpr double EQ_SPOT_BUMP = 0.01;  ///< 1% relative spot bump
inline constexpr double VOL_BUMP = 0.01;    ///< 1 vol point absolute bump
inline constexpr double FX_BUMP = 0.01;     ///< 1% relative FX spot bump

/// All raw (unweighted) sensitivities of one instrument scope.
///
/// girr:          {currency: {tenor: dV/dr}} (only non-zero currencies kept;
///                a kept currency stores every pinned tenor, zeros included)
/// equity_delta:  {name: S * dV/dS}
/// equity_vega:   {name: vega * sigma}
/// fx_delta:      {pair: S * dV/dS}
/// *_cvr:         curvature (CVR+, CVR-) per curve / name / pair.
struct Sensitivities {
    std::map<std::string, std::map<double, double>> girr;
    std::map<std::string, double> equity_delta;
    std::map<std::string, double> equity_vega;
    std::map<std::string, double> fx_delta;
    std::map<std::string, std::pair<double, double>> girr_cvr;
    std::map<std::string, std::pair<double, double>> equity_cvr;
    std::map<std::string, std::pair<double, double>> fx_cvr;
};

/// Full bump-and-revalue pass over one instrument scope (desk or firm).
///
/// Deterministic: pure revaluation under bumped market snapshots, no RNG.
/// An empty scope returns all-empty maps (capital 0 downstream).
Sensitivities compute_sensitivities(const std::vector<Instrument>& instruments,
                                    const Market& market, const SbmParams& params);

}  // namespace frtb
