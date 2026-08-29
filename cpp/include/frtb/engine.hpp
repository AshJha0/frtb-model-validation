/// \file engine.hpp
/// \brief End-to-end orchestration: load the bundled data set, compute SA
/// (SBM + DRC + RRAO), the IMA sketch (ES/IMCC, backtesting, PLAT, SES) and
/// the independent validation results for every desk and for the firm.
///
/// Fully deterministic: pure revaluation and closed-form statistics, no RNG.

#pragma once

#include <map>
#include <string>
#include <vector>

#include "frtb/ima.hpp"
#include "frtb/instruments.hpp"
#include "frtb/market.hpp"
#include "frtb/params.hpp"
#include "frtb/plat.hpp"
#include "frtb/sa.hpp"
#include "frtb/sensitivities.hpp"
#include "frtb/validation.hpp"

namespace frtb {

/// A P&L CSV (date + numeric columns): column names in file order + series.
/// Empty cells become NaN (picked up by the data-quality check).
struct PnlTable {
    std::vector<std::string> columns;                  ///< non-date columns, file order
    std::map<std::string, std::vector<double>> data;   ///< column -> series

    const std::vector<double>& series(const std::string& col) const;
};

/// Load a P&L CSV; \throws std::invalid_argument on I/O or schema errors.
PnlTable load_pnl_csv(const std::string& path);

/// Extract the per-category P&L columns "<desk>_<cat>" for one desk,
/// preserving the file's column order.
CategoryPnl desk_categories(const std::string& desk, const PnlTable& hypo);

/// IMA sketch results for one desk.
struct ImaDeskResult {
    double es_base = 0.0;
    double es_lh = 0.0;
    double imcc = 0.0;
    BacktestResult backtest;
    PlatResult plat;
    double ses = 0.0;
    double capital_core = 0.0;
    double plat_surcharge = 0.0;
    double capital = 0.0;
};

/// Validation block: shared checks + per-desk findings and verdicts.
struct ValidationBlock {
    double benchmark_max_diff = 0.0;
    double sensitivity_max_diff = 0.0;
    double stability_base_capital = 0.0;
    double stability_capital_rw_up10 = 0.0;
    double stability_capital_rw_dn10 = 0.0;
    double stability_rel_change = 0.0;
    std::map<std::string, DataQuality> data_quality;
    std::map<std::string, std::vector<Finding>> findings;
    std::map<std::string, std::string> verdicts;
    std::string report_md;  ///< rendered validation report (markdown)
};

/// The full result tree computed from the bundled data directory.
struct Results {
    SbmParams params;
    Market market;
    std::map<std::string, Desk> desks;
    std::map<std::string, SaScope> sa;  ///< per desk + "firm"
    Sensitivities sens_firm;
    std::map<std::string, ImaDeskResult> ima;  ///< per desk
    ValidationBlock validation;
};

/// Compute the full result tree from the bundled data directory.
/// \throws std::invalid_argument on any data or validation error.
Results compute_results(const std::string& data_dir);

/// Render the validation report markdown from the engine's results.
/// Always emits every section in REPORT_SECTIONS (tested by string-contains).
std::string render_report(const Results& results);

}  // namespace frtb
