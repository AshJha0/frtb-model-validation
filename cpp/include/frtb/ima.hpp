/// \file ima.hpp
/// \brief Internal Models Approach sketch: ES 97.5% with liquidity-horizon
/// scaling, IMCC, backtesting zones/multipliers, NMRF stress capital (SES).
///
/// Pinned conventions (see API_SPEC.md):
///  * ES 97.5 (daily): losses L = -PnL sorted descending;
///    k = max(1, ceil((1-alpha)*n - 1e-9)); ES_daily = mean of k worst losses.
///    Base 10d ES = sqrt(10) * ES_daily.
///  * LH ladder (10, 20, 40, 60, 120):
///      ES_LH = sqrt(ES1(P)^2 + sum_{j>=2} (ES1(P_j)*sqrt((LH_j-LH_{j-1})/10))^2)
///    with P_j the sum of category P&L whose pinned horizon is >= LH_j.
///  * IMCC = rho * ES_LH(full) + (1-rho) * sum_c ES_LH(category c), rho = 0.5.
///  * Backtesting (99% VaR): exception when PnL_t < -VaR_t (strict);
///    zones green 0-4 / amber 5-9 / red >= 10; pinned multiplier table.
///  * SES = sum of pinned NMRF stressed losses, zero diversification benefit.

#pragma once

#include <map>
#include <string>
#include <utility>
#include <vector>

#include "frtb/params.hpp"

namespace frtb {

/// Ordered category P&L: (category name, series), preserving input order —
/// the LH ladder sums categories in this order (bit-tight goldens).
using CategoryPnl = std::vector<std::pair<std::string, std::vector<double>>>;

/// Daily ES at level alpha: mean of the k = ceil((1-alpha)*n) worst losses.
/// \throws std::invalid_argument on empty/non-finite input or bad alpha.
double expected_shortfall_daily(const std::vector<double>& pnl, double alpha = 0.975);

/// Base 10-day ES: sqrt(10) * daily ES (pinned square-root-of-time scaling).
double es_base_10d(const std::vector<double>& pnl, double alpha = 0.975);

/// Liquidity-horizon-scaled ES (Basel ladder formula, see file docstring).
///
/// Category series must sum to the full P&L (validated to 1e-6) and every
/// category must have a pinned liquidity horizon.
/// \throws std::invalid_argument on validation failure.
double es_lh_scaled(const std::vector<double>& full_pnl, const CategoryPnl& category_pnl,
                    const std::map<std::string, int>& category_lh,
                    const std::vector<int>& lh_ladder, double alpha = 0.975);

/// IMCC = rho * ES_LH(full) + (1-rho) * sum over categories of ES_LH(category).
double imcc(const std::vector<double>& full_pnl, const CategoryPnl& category_pnl,
            const SbmParams& params);

/// VaR backtest outcome: exception count, Basel zone, capital multiplier.
struct BacktestResult {
    int exceptions = 0;
    std::string zone;  ///< "green" | "amber" | "red"
    double multiplier = 1.5;
};

/// Count 99% VaR exceptions (PnL_t < -VaR_t) and map to zone/multiplier.
/// \throws std::invalid_argument on length mismatch, empty series or
/// negative/non-finite VaR values.
BacktestResult backtest(const std::vector<double>& pnl, const std::vector<double>& var99,
                        const SbmParams& params);

/// Basel traffic-light zone: green 0-4, amber 5-9, red >= 10.
/// \throws std::invalid_argument on a negative count.
std::string backtest_zone(int exceptions);

/// Pinned multiplier: 1.5 green; amber table 5..9; 2.0 red (cap, also > 12).
double backtest_multiplier(int exceptions, const SbmParams& params);

/// One pinned non-modellable risk factor entry.
struct NmrfEntry {
    std::string factor;
    std::string desk;
    double stressed_loss = 0.0;
};

/// Stress scenario capital: sum of stressed losses, zero diversification.
/// \throws std::invalid_argument on a negative or non-finite stressed loss.
double ses(const std::vector<NmrfEntry>& nmrf_entries);

/// IMA capital = multiplier * IMCC + SES + PLAT surcharge.
///
/// Simplification (documented): avg60(IMCC) = IMCC for the static bundled
/// portfolio, so max(IMCC, m*avg60(IMCC)) = m*IMCC since m >= 1.5.
/// \throws std::invalid_argument on negative or non-finite inputs.
double ima_capital(double imcc_value, double multiplier, double ses_value,
                   double plat_surcharge = 0.0);

}  // namespace frtb
