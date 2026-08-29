/// \file stats.hpp
/// \brief Native statistics used by PLAT: Spearman rank correlation and the
/// two-sample Kolmogorov-Smirnov statistic.
///
/// Implemented from first principles (no external stats dependency); the
/// Python reference cross-checks both against scipy.

#pragma once

#include <vector>

namespace frtb {

/// Ranks 1..n with ties assigned the average rank of the tied block.
std::vector<double> average_ranks(const std::vector<double>& x);

/// Pearson correlation.
/// \throws std::invalid_argument on length mismatch, short/non-finite input,
/// or a constant series (correlation undefined).
double pearson(const std::vector<double>& x, const std::vector<double>& y);

/// Spearman rank correlation: Pearson correlation of average ranks.
/// \throws std::invalid_argument when either series is constant (undefined —
/// PLAT maps this case to the Red zone, see plat.hpp).
double spearman(const std::vector<double>& x, const std::vector<double>& y);

/// Two-sample Kolmogorov-Smirnov statistic sup_t |F_x(t) - F_y(t)|.
///
/// Computed exactly over the pooled sample with a two-pointer sweep (handles
/// ties identically to scipy.stats.ks_2samp).
/// \throws std::invalid_argument on empty or non-finite input.
double ks_statistic(const std::vector<double>& x, const std::vector<double>& y);

}  // namespace frtb
