#include "frtb/ima.hpp"

#include <algorithm>
#include <cmath>
#include <functional>
#include <stdexcept>

namespace frtb {

double expected_shortfall_daily(const std::vector<double>& pnl, double alpha) {
    const std::size_t n = pnl.size();
    if (n == 0)
        throw std::invalid_argument("expected_shortfall_daily: empty P&L series");
    if (!(0.0 < alpha && alpha < 1.0))
        throw std::invalid_argument("expected_shortfall_daily: alpha must be in (0,1)");
    for (double v : pnl)
        if (!std::isfinite(v))
            throw std::invalid_argument(
                "expected_shortfall_daily: P&L contains non-finite values");
    // The tiny epsilon guards against binary-float artefacts like
    // 0.025 * 40 -> 1.0000000000000009 making ceil jump one bucket.
    const std::size_t k = static_cast<std::size_t>(
        std::max(1.0, std::ceil((1.0 - alpha) * static_cast<double>(n) - 1e-9)));
    std::vector<double> losses;
    losses.reserve(n);
    for (double v : pnl) losses.push_back(-v);
    std::sort(losses.begin(), losses.end(), std::greater<double>());
    double s = 0.0;
    for (std::size_t i = 0; i < k; ++i) s += losses[i];
    return s / static_cast<double>(k);
}

double es_base_10d(const std::vector<double>& pnl, double alpha) {
    return std::sqrt(10.0) * expected_shortfall_daily(pnl, alpha);
}

double es_lh_scaled(const std::vector<double>& full_pnl, const CategoryPnl& category_pnl,
                    const std::map<std::string, int>& category_lh,
                    const std::vector<int>& lh_ladder, double alpha) {
    {
        std::vector<int> sorted_unique = lh_ladder;
        std::sort(sorted_unique.begin(), sorted_unique.end());
        sorted_unique.erase(std::unique(sorted_unique.begin(), sorted_unique.end()),
                            sorted_unique.end());
        if (lh_ladder.empty() || sorted_unique != lh_ladder)
            throw std::invalid_argument("es_lh_scaled: lh_ladder must be strictly increasing");
    }
    if (lh_ladder.front() != 10)
        throw std::invalid_argument("es_lh_scaled: lh_ladder must start at the 10d base horizon");
    const std::size_t n = full_pnl.size();
    for (const auto& [cat, series] : category_pnl) {
        if (!category_lh.count(cat))
            throw std::invalid_argument("es_lh_scaled: no pinned liquidity horizon for category '" +
                                        cat + "'");
        if (series.size() != n)
            throw std::invalid_argument("es_lh_scaled: category '" + cat + "' length mismatch");
    }
    for (std::size_t i = 0; i < n; ++i) {
        double s = 0.0;
        for (const auto& kv : category_pnl) s += kv.second[i];
        if (std::abs(s - full_pnl[i]) > 1e-6)
            throw std::invalid_argument(
                "es_lh_scaled: category P&L does not sum to the full P&L on day " +
                std::to_string(i));
    }

    const double base = es_base_10d(full_pnl, alpha);
    double total_sq = base * base;
    for (std::size_t j = 1; j < lh_ladder.size(); ++j) {
        const int lh_j = lh_ladder[j];
        const int lh_prev = lh_ladder[j - 1];
        std::vector<const std::vector<double>*> cats;
        for (const auto& [cat, series] : category_pnl)
            if (category_lh.at(cat) >= lh_j) cats.push_back(&series);
        if (cats.empty()) continue;
        std::vector<double> subset(n, 0.0);
        for (std::size_t i = 0; i < n; ++i) {
            double s = 0.0;
            for (const auto* series : cats) s += (*series)[i];
            subset[i] = s;
        }
        bool all_zero = true;
        for (double v : subset)
            if (v != 0.0) all_zero = false;
        if (all_zero) continue;
        const double term = es_base_10d(subset, alpha) * std::sqrt((lh_j - lh_prev) / 10.0);
        total_sq += term * term;
    }
    return std::sqrt(total_sq);
}

double imcc(const std::vector<double>& full_pnl, const CategoryPnl& category_pnl,
            const SbmParams& params) {
    const double rho = params.ima_rho;
    const double es_full =
        es_lh_scaled(full_pnl, category_pnl, params.category_lh, params.lh_ladder, params.ima_alpha);
    double es_partials = 0.0;
    for (const auto& [cat, series] : category_pnl) {
        CategoryPnl single = {{cat, series}};
        es_partials +=
            es_lh_scaled(series, single, params.category_lh, params.lh_ladder, params.ima_alpha);
    }
    return rho * es_full + (1.0 - rho) * es_partials;
}

BacktestResult backtest(const std::vector<double>& pnl, const std::vector<double>& var99,
                        const SbmParams& params) {
    if (pnl.size() != var99.size())
        throw std::invalid_argument("backtest: P&L and VaR length mismatch");
    if (pnl.empty())
        throw std::invalid_argument("backtest: empty series");
    for (double v : var99)
        if (!std::isfinite(v) || v < 0.0)
            throw std::invalid_argument("backtest: VaR values must be non-negative and finite");
    int exceptions = 0;
    for (std::size_t i = 0; i < pnl.size(); ++i)
        if (pnl[i] < -var99[i]) ++exceptions;
    return {exceptions, backtest_zone(exceptions), backtest_multiplier(exceptions, params)};
}

std::string backtest_zone(int exceptions) {
    if (exceptions < 0)
        throw std::invalid_argument("backtest_zone: exception count cannot be negative");
    if (exceptions <= 4) return "green";
    if (exceptions <= 9) return "amber";
    return "red";
}

double backtest_multiplier(int exceptions, const SbmParams& params) {
    const std::string zone = backtest_zone(exceptions);
    if (zone == "green") return params.backtest_base_multiplier;
    if (zone == "amber") {
        auto it = params.backtest_amber_multipliers.find(exceptions);
        if (it == params.backtest_amber_multipliers.end())
            throw std::invalid_argument("backtest_multiplier: no amber multiplier pinned for " +
                                        std::to_string(exceptions));
        return it->second;
    }
    return params.backtest_red_multiplier;
}

double ses(const std::vector<NmrfEntry>& nmrf_entries) {
    double total = 0.0;
    for (const NmrfEntry& e : nmrf_entries) {
        if (!std::isfinite(e.stressed_loss) || e.stressed_loss < 0.0)
            throw std::invalid_argument("ses: stressed_loss must be >= 0 and finite (factor '" +
                                        e.factor + "')");
        total += e.stressed_loss;
    }
    return total;
}

double ima_capital(double imcc_value, double multiplier, double ses_value, double plat_surcharge) {
    const std::pair<const char*, double> checks[] = {{"imcc", imcc_value},
                                                     {"multiplier", multiplier},
                                                     {"ses", ses_value},
                                                     {"plat_surcharge", plat_surcharge}};
    for (const auto& [name, v] : checks)
        if (!std::isfinite(v) || v < 0.0)
            throw std::invalid_argument(std::string("ima_capital: ") + name +
                                        " must be >= 0 and finite");
    return multiplier * imcc_value + ses_value + plat_surcharge;
}

}  // namespace frtb
