/// \file validation.hpp
/// \brief Independent model validation framework.
///
/// Checks (all pinned):
///  * Benchmarking — project BS pricer vs an independent CRR binomial lattice
///    (501 steps) on a pinned strike/maturity/call-put grid; PASS iff
///    max abs price diff <= 0.05.
///  * Sensitivity  — analytic BS delta vs central finite difference
///    (h = 1e-4 * S) on the same grid; PASS iff max diff <= 1e-6.
///  * Stability    — SBM capital recomputed with GIRR delta RWs x0.9 / x1.1;
///    finding if |delta capital| / base capital > 0.25.
///  * Backtesting / PLAT — desk zones from ima.hpp / plat.hpp.
///  * Data quality — staleness: # of zero-change days > 15 -> finding;
///    gaps: any missing (NaN) value -> finding.
///
/// Findings classification (pinned rule table): High -> "reject";
/// Medium -> "approve-with-conditions" (if no High); else "approve".

#pragma once

#include <string>
#include <vector>

namespace frtb {

// ---- pinned check parameters ---------------------------------------------
inline constexpr int BENCH_STEPS = 501;
inline constexpr double BENCH_TOL = 0.05;
inline constexpr double SENS_TOL = 1e-6;
inline constexpr double STABILITY_THRESHOLD = 0.25;  ///< |delta capital| / capital
inline constexpr int STALENESS_THRESHOLD = 15;       ///< zero-change days
inline constexpr double BENCH_GRID_STRIKES[] = {70.0, 85.0, 100.0, 115.0, 130.0};
inline constexpr double BENCH_GRID_MATURITIES[] = {0.25, 0.5, 1.0, 2.0};
inline constexpr double BENCH_SPOT = 100.0;
inline constexpr double BENCH_RATE = 0.03;
inline constexpr double BENCH_DIV = 0.01;
inline constexpr double BENCH_VOL = 0.2;

/// One validation finding: pinned rule id, severity, human description.
struct Finding {
    std::string rule_id;
    std::string severity;  ///< "High" | "Medium" | "Low"
    std::string description;
};

/// Max abs diff |BS - binomial(501)| over the pinned option grid.
double benchmark_max_diff();

/// Max abs diff between analytic BS delta and a central finite difference.
double sensitivity_max_diff();

/// Staleness (# zero-change days) and gaps (# NaN values) of one series.
struct DataQuality {
    int stale_days = 0;
    int gaps = 0;
};

/// \throws std::invalid_argument when the series has fewer than 2 points.
DataQuality data_quality(const std::vector<double>& series);

/// Everything the pinned rule table needs to classify one desk.
struct DeskCheckInputs {
    double benchmark_max_diff = 0.0;
    double sensitivity_max_diff = 0.0;
    double stability_rel_change = 0.0;  ///< max(|dCap x1.1|,|dCap x0.9|)/base
    std::string backtest_zone;
    std::string plat_zone;
    int stale_days = 0;
    int gaps = 0;
};

/// Apply the pinned rule table; returns findings in table order.
std::vector<Finding> classify_findings(const DeskCheckInputs& inputs);

/// Pinned verdict rule: any High -> "reject"; any Medium ->
/// "approve-with-conditions"; else "approve" (exact strings).
std::string overall_verdict(const std::vector<Finding>& findings);

/// The ten report section titles, always emitted (prefixed "## ").
extern const std::vector<std::string> REPORT_SECTIONS;

}  // namespace frtb
