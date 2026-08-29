#include "frtb/validation.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <stdexcept>
#include <string>

#include "frtb/pricers.hpp"

namespace frtb {

const std::vector<std::string> REPORT_SECTIONS = {
    "1. Scope & Overview",     "2. Pricing Benchmark", "3. Sensitivity Verification",
    "4. Capital Stability",    "5. VaR Backtesting",   "6. P&L Attribution (PLAT)",
    "7. Data Quality",         "8. NMRF / SES",        "9. Findings",
    "10. Overall Verdict",
};

double benchmark_max_diff() {
    double worst = 0.0;
    for (double k : BENCH_GRID_STRIKES)
        for (double t : BENCH_GRID_MATURITIES)
            for (bool call : {true, false}) {
                double a = bs_price(BENCH_SPOT, k, t, BENCH_RATE, BENCH_DIV, BENCH_VOL, call);
                double b = binomial_price(BENCH_SPOT, k, t, BENCH_RATE, BENCH_DIV, BENCH_VOL,
                                          call, BENCH_STEPS);
                worst = std::max(worst, std::abs(a - b));
            }
    return worst;
}

double sensitivity_max_diff() {
    const double h = 1e-4 * BENCH_SPOT;
    double worst = 0.0;
    for (double k : BENCH_GRID_STRIKES)
        for (double t : BENCH_GRID_MATURITIES)
            for (bool call : {true, false}) {
                double analytic =
                    bs_delta(BENCH_SPOT, k, t, BENCH_RATE, BENCH_DIV, BENCH_VOL, call);
                double up = bs_price(BENCH_SPOT + h, k, t, BENCH_RATE, BENCH_DIV, BENCH_VOL, call);
                double dn = bs_price(BENCH_SPOT - h, k, t, BENCH_RATE, BENCH_DIV, BENCH_VOL, call);
                worst = std::max(worst, std::abs(analytic - (up - dn) / (2.0 * h)));
            }
    return worst;
}

DataQuality data_quality(const std::vector<double>& series) {
    if (series.size() < 2)
        throw std::invalid_argument("data_quality: need at least 2 observations");
    DataQuality dq;
    std::vector<double> clean;
    for (double v : series) {
        if (std::isnan(v))
            ++dq.gaps;
        else
            clean.push_back(v);
    }
    for (std::size_t i = 1; i < clean.size(); ++i)
        if (clean[i] - clean[i - 1] == 0.0) ++dq.stale_days;
    return dq;
}

namespace {

std::string fmt(const char* spec, double v) {
    char buf[64];
    std::snprintf(buf, sizeof buf, spec, v);
    return buf;
}

}  // namespace

std::vector<Finding> classify_findings(const DeskCheckInputs& c) {
    std::vector<Finding> out;
    // Pinned rule table, evaluated in order; all comparisons are strict '>'.
    if (c.benchmark_max_diff > BENCH_TOL)
        out.push_back({"BENCH-01", "High",
                       "Pricing benchmark max diff " + fmt("%.6g", c.benchmark_max_diff) +
                           " exceeds tolerance " + fmt("%g", BENCH_TOL)});
    if (c.sensitivity_max_diff > SENS_TOL)
        out.push_back({"SENS-01", "High",
                       "Analytic vs FD delta max diff " + fmt("%.6g", c.sensitivity_max_diff) +
                           " exceeds " + fmt("%g", SENS_TOL)});
    if (c.backtest_zone == "red") out.push_back({"BT-01", "High", "VaR backtest in RED zone"});
    if (c.backtest_zone == "amber")
        out.push_back({"BT-02", "Medium", "VaR backtest in AMBER zone"});
    if (c.plat_zone == "red") out.push_back({"PLAT-01", "High", "PLAT in RED zone"});
    if (c.plat_zone == "amber") out.push_back({"PLAT-02", "Medium", "PLAT in AMBER zone"});
    if (c.stability_rel_change > STABILITY_THRESHOLD)
        out.push_back({"STAB-01", "Medium",
                       "Capital moves " + fmt("%.1f", c.stability_rel_change * 100.0) +
                           "% under +/-10% GIRR RW (threshold " +
                           fmt("%.0f", STABILITY_THRESHOLD * 100.0) + "%)"});
    if (c.stale_days > STALENESS_THRESHOLD)
        out.push_back({"DQ-01", "Medium",
                       std::to_string(c.stale_days) + " zero-change days exceed staleness " +
                           "threshold " + std::to_string(STALENESS_THRESHOLD)});
    if (c.gaps > 0)
        out.push_back(
            {"DQ-02", "Low", std::to_string(c.gaps) + " missing values in the P&L series"});
    return out;
}

std::string overall_verdict(const std::vector<Finding>& findings) {
    bool high = false;
    bool medium = false;
    for (const Finding& f : findings) {
        if (f.severity == "High") high = true;
        if (f.severity == "Medium") medium = true;
    }
    if (high) return "reject";
    if (medium) return "approve-with-conditions";
    return "approve";
}

}  // namespace frtb
