/// \file params.hpp
/// \brief Pinned regulatory parameter set (loaded from data/sbm_params.json).
///
/// IMPORTANT: the parameter values are an EDUCATIONAL, Basel-2019-flavored set
/// — simplified bucket structure, pinned correlations, no securitisation
/// buckets.  They are NOT the official Basel text and must not be used for
/// real capital.

#pragma once

#include <map>
#include <string>
#include <vector>

namespace frtb {

/// Per-bucket equity parameters: delta RW, vega RW, intra-bucket rho.
struct EquityBucketParams {
    double delta_rw = 0.0;
    double vega_rw = 0.0;
    double rho = 0.0;
};

/// Full pinned parameter set for SBM + DRC + RRAO + IMA + PLAT.
///
/// Loaded from data/sbm_params.json; all lookups throw std::invalid_argument
/// with a clear message when a bucket / rating / tenor is missing (spec edge
/// case).
struct SbmParams {
    // GIRR
    std::vector<double> girr_tenors;
    std::map<double, double> girr_delta_rw;
    std::vector<std::vector<double>> girr_rho;  ///< tenor x tenor correlations
    double girr_vega_rw = 0.0;
    double girr_curvature_rw = 0.0;
    double girr_gamma = 0.0;  ///< cross-currency gamma
    // Equity
    std::map<std::string, EquityBucketParams> equity_buckets;
    double equity_gamma = 0.0;
    // FX
    double fx_delta_rw = 0.0;
    double fx_rho = 0.0;
    double fx_gamma = 0.0;
    // scenario scalers
    double scenario_high = 1.25;
    double scenario_low = 0.75;
    // DRC
    std::map<std::string, double> drc_rw_by_rating;
    // RRAO
    std::map<std::string, double> rrao_rates;
    // IMA
    double ima_alpha = 0.975;
    double ima_rho = 0.5;
    std::vector<int> lh_ladder;
    std::map<std::string, int> category_lh;
    std::map<int, double> backtest_amber_multipliers;  ///< exceptions 5..9
    double backtest_base_multiplier = 1.5;
    double backtest_red_multiplier = 2.0;
    // PLAT
    double plat_spearman_green = 0.85;
    double plat_spearman_amber = 0.80;
    double plat_ks_green = 0.09;
    double plat_ks_amber = 0.12;
    double plat_k_surcharge = 0.5;

    /// GIRR delta risk weight for one pinned tenor.
    /// \throws std::invalid_argument when the tenor is not pinned.
    double girr_rw(double tenor) const;

    /// Tenor-index correlation lookup (medium scenario).
    double girr_rho_kl(std::size_t i, std::size_t j) const { return girr_rho[i][j]; }

    /// Equity bucket parameters; throws std::invalid_argument when unknown.
    const EquityBucketParams& equity_bucket(const std::string& bucket) const;

    /// DRC risk weight by rating; throws std::invalid_argument when unknown.
    double drc_rw(const std::string& rating) const;

    /// RRAO rate by category; throws std::invalid_argument when unknown.
    double rrao_rate(const std::string& category) const;

    /// Copy with every GIRR delta RW scaled by \p factor (stability check).
    /// \throws std::invalid_argument on non-positive/non-finite factor.
    SbmParams with_girr_delta_rw_scaled(double factor) const;
};

/// Load and validate the pinned parameter file.
/// \throws std::invalid_argument on any missing key or malformed table.
SbmParams load_params(const std::string& path);

}  // namespace frtb
