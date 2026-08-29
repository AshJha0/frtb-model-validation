/// \file sa.hpp
/// \brief Standardised Approach assembly: SBM charges per risk class and
/// scenario, plus DRC-lite and RRAO.  SA capital = SBM + DRC + RRAO.

#pragma once

#include <map>
#include <string>
#include <utility>
#include <vector>

#include "frtb/instruments.hpp"
#include "frtb/market.hpp"
#include "frtb/params.hpp"
#include "frtb/sbm.hpp"
#include "frtb/sensitivities.hpp"

namespace frtb {

/// Risk classes and measures in the pinned summation order.
inline const std::vector<std::string> RISK_CLASSES = {"girr", "equity", "fx"};
inline const std::vector<std::string> MEASURES = {"delta", "vega", "curvature"};

/// SBM capital with full drill-down.
///
/// charges[risk_class][measure][scenario] -> charge;
/// kb_medium[risk_class][measure] -> {bucket: K_b} (medium scenario);
/// scenario_totals[scenario] -> sum over risk classes and measures;
/// capital = max over scenarios of scenario_totals.
struct SbmResult {
    std::map<std::string, std::map<std::string, std::map<std::string, double>>> charges;
    std::map<std::string, std::map<std::string, std::map<std::string, double>>> kb_medium;
    std::map<std::string, double> scenario_totals;
    double capital = 0.0;
};

/// Assemble the full SBM capital: 3 risk classes x 3 measures x 3 scenarios.
SbmResult sbm_capital(const Sensitivities& sens, const Market& market, const SbmParams& params);

/// One default-risk position: issuer, rating, notional (signed), market value.
struct DrcPosition {
    std::string issuer;
    std::string rating;
    double notional = 0.0;
    double market_value = 0.0;
    double lgd = 0.75;

    /// Jump-to-default: JTD = LGD*notional + (MV - notional) (signed).
    double jtd() const { return lgd * notional + (market_value - notional); }
};

/// DRC-lite output: charge + netting/HBR drill-down.
struct DrcResult {
    double charge = 0.0;
    double hbr = 1.0;
    /// Net JTD per issuer, in first-occurrence order (mirrors the reference).
    std::vector<std::pair<std::string, double>> net_jtd;
    double gross_long = 0.0;
    double gross_short = 0.0;
};

/// Default Risk Charge (lite).
///
/// 1. JTD_i = LGD*notional + (MV - notional) per position (signed; shorts
///    have negative notional).
/// 2. Net JTD per issuer (long/short netting within the same issuer).
/// 3. HBR = sum(netLong) / (sum(netLong) + sum(|netShort|)); HBR = 1 when
///    there are no net shorts (all-long edge case) and when the book is empty.
/// 4. DRC = max(0, sum RW_i*netLong_i - HBR * sum RW_i*|netShort_i|)
///    with RW from the pinned rating table.
/// \throws std::invalid_argument on an unknown rating or an issuer with two
/// different ratings.
DrcResult drc_charge(const std::vector<DrcPosition>& positions, const SbmParams& params);

/// Extract DRC positions (bonds only in this educational kit — documented).
std::vector<DrcPosition> drc_positions_from_instruments(
    const std::vector<Instrument>& instruments, const Market& market);

/// Residual risk add-on: sum over flagged instruments of rate * notional.
/// Pinned rates: "exotic" 1.0%, "other" 0.1% of the flagged notional.
/// \throws std::invalid_argument on an unknown RRAO category.
double rrao_charge(const std::vector<Instrument>& instruments, const SbmParams& params);

/// SA results for one scope (a desk or the whole firm).
struct SaScope {
    SbmResult sbm;
    double drc = 0.0;
    double drc_hbr = 1.0;
    double rrao = 0.0;

    /// SA capital = SBM + DRC + RRAO.
    double capital() const { return sbm.capital + drc + rrao; }
};

/// SA capital for one instrument scope: SBM + DRC-lite + RRAO.
SaScope compute_sa(const std::vector<Instrument>& instruments, const Market& market,
                   const SbmParams& params);

}  // namespace frtb
