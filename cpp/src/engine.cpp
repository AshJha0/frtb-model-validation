#include "frtb/engine.hpp"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <fstream>
#include <limits>
#include <sstream>
#include <stdexcept>

#include "frtb/json.hpp"

namespace frtb {

const std::vector<double>& PnlTable::series(const std::string& col) const {
    auto it = data.find(col);
    if (it == data.end())
        throw std::invalid_argument("PnlTable: no column '" + col + "'");
    return it->second;
}

namespace {

std::vector<std::string> split_csv(const std::string& line) {
    std::vector<std::string> out;
    std::string cell;
    std::istringstream ss(line);
    while (std::getline(ss, cell, ',')) out.push_back(cell);
    if (!line.empty() && line.back() == ',') out.emplace_back();
    return out;
}

std::string strip(const std::string& s) {
    std::size_t a = s.find_first_not_of(" \t\r\n");
    if (a == std::string::npos) return "";
    std::size_t b = s.find_last_not_of(" \t\r\n");
    return s.substr(a, b - a + 1);
}

}  // namespace

PnlTable load_pnl_csv(const std::string& path) {
    std::ifstream f(path);
    if (!f) throw std::invalid_argument("load_pnl_csv: cannot open " + path);
    std::string line;
    if (!std::getline(f, line))
        throw std::invalid_argument("load_pnl_csv: " + path + " is empty");
    auto header = split_csv(strip(line));
    PnlTable table;
    int date_idx = -1;
    for (std::size_t i = 0; i < header.size(); ++i) {
        std::string h = strip(header[i]);
        if (h == "date")
            date_idx = static_cast<int>(i);
        else
            table.columns.push_back(h);
    }
    if (date_idx < 0)
        throw std::invalid_argument("load_pnl_csv: " + path + " must have a 'date' column");
    for (const std::string& c : table.columns) table.data[c] = {};
    while (std::getline(f, line)) {
        if (strip(line).empty()) continue;
        auto cells = split_csv(line);
        cells.resize(header.size());
        std::size_t k = 0;
        for (std::size_t i = 0; i < header.size(); ++i) {
            if (static_cast<int>(i) == date_idx) continue;
            std::string cell = strip(cells[i]);
            double v;
            if (cell.empty()) {
                v = std::numeric_limits<double>::quiet_NaN();
            } else {
                char* end = nullptr;
                v = std::strtod(cell.c_str(), &end);
                if (end != cell.c_str() + cell.size())
                    throw std::invalid_argument("load_pnl_csv: bad number '" + cell + "' in " +
                                                path);
            }
            table.data[table.columns[k]].push_back(v);
            ++k;
        }
    }
    if (table.columns.empty() || table.data[table.columns.front()].empty())
        throw std::invalid_argument("load_pnl_csv: " + path + " contains no data rows");
    return table;
}

CategoryPnl desk_categories(const std::string& desk, const PnlTable& hypo) {
    const std::string prefix = desk + "_";
    CategoryPnl out;
    for (const std::string& c : hypo.columns)
        if (c.rfind(prefix, 0) == 0) out.emplace_back(c.substr(prefix.size()), hypo.series(c));
    return out;
}

Results compute_results(const std::string& data_dir) {
    Results res;
    res.params = load_params(data_dir + "/sbm_params.json");
    res.market = load_market(data_dir + "/curves.csv", data_dir + "/spots.csv");
    res.desks = load_portfolio(data_dir + "/portfolio.json");
    PnlTable hypo = load_pnl_csv(data_dir + "/pnl_hypo.csv");
    PnlTable rtpl = load_pnl_csv(data_dir + "/pnl_rtpl.csv");
    PnlTable var99 = load_pnl_csv(data_dir + "/pnl_var.csv");
    std::vector<NmrfEntry> nmrf;
    {
        json::Value doc = json::parse_file(data_dir + "/nmrf.json");
        for (const json::Value& e : doc.at("factors").array)
            nmrf.push_back({e.at("factor").as_string(), e.at("desk").as_string(),
                            e.at("stressed_loss").as_number()});
    }

    std::vector<std::string> desk_names;  // std::map keys are already sorted
    for (const auto& kv : res.desks) desk_names.push_back(kv.first);
    std::vector<Instrument> all_instruments;
    for (const std::string& d : desk_names)
        for (const Instrument& i : res.desks.at(d).instruments) all_instruments.push_back(i);

    // ---- SA per desk + firm ----------------------------------------------
    for (const std::string& d : desk_names)
        res.sa[d] = compute_sa(res.desks.at(d).instruments, res.market, res.params);
    res.sa["firm"] = compute_sa(all_instruments, res.market, res.params);
    res.sens_firm = compute_sensitivities(all_instruments, res.market, res.params);

    // ---- IMA per desk -----------------------------------------------------
    for (const std::string& d : desk_names) {
        CategoryPnl cats = desk_categories(d, hypo);
        if (cats.empty())
            throw std::invalid_argument("compute_results: no category P&L columns for desk '" +
                                        d + "'");
        const std::vector<double>& full = hypo.series(d);
        ImaDeskResult r;
        r.es_base = es_base_10d(full, res.params.ima_alpha);
        r.es_lh = es_lh_scaled(full, cats, res.params.category_lh, res.params.lh_ladder,
                               res.params.ima_alpha);
        r.imcc = imcc(full, cats, res.params);
        r.backtest = backtest(full, var99.series(d), res.params);
        r.plat = plat_test(full, rtpl.series(d), res.params);
        std::vector<NmrfEntry> desk_nmrf;
        for (const NmrfEntry& e : nmrf)
            if (e.desk == d) desk_nmrf.push_back(e);
        r.ses = ses(desk_nmrf);
        r.capital_core = ima_capital(r.imcc, r.backtest.multiplier, r.ses);
        r.plat_surcharge =
            plat_surcharge(r.plat.zone, res.sa.at(d).capital(), r.capital_core, res.params);
        r.capital = r.capital_core + r.plat_surcharge;
        res.ima[d] = r;
    }

    // ---- validation checks ------------------------------------------------
    ValidationBlock& val = res.validation;
    val.benchmark_max_diff = benchmark_max_diff();
    val.sensitivity_max_diff = sensitivity_max_diff();
    val.stability_base_capital = res.sa.at("firm").sbm.capital;
    val.stability_capital_rw_up10 =
        sbm_capital(res.sens_firm, res.market, res.params.with_girr_delta_rw_scaled(1.1)).capital;
    val.stability_capital_rw_dn10 =
        sbm_capital(res.sens_firm, res.market, res.params.with_girr_delta_rw_scaled(0.9)).capital;
    val.stability_rel_change =
        (val.stability_base_capital > 0.0)
            ? std::max(std::abs(val.stability_capital_rw_up10 - val.stability_base_capital),
                       std::abs(val.stability_capital_rw_dn10 - val.stability_base_capital)) /
                  val.stability_base_capital
            : 0.0;
    for (const std::string& d : desk_names) val.data_quality[d] = data_quality(hypo.series(d));

    for (const std::string& d : desk_names) {
        DeskCheckInputs inputs;
        inputs.benchmark_max_diff = val.benchmark_max_diff;
        inputs.sensitivity_max_diff = val.sensitivity_max_diff;
        inputs.stability_rel_change = val.stability_rel_change;
        inputs.backtest_zone = res.ima.at(d).backtest.zone;
        inputs.plat_zone = res.ima.at(d).plat.zone;
        inputs.stale_days = val.data_quality.at(d).stale_days;
        inputs.gaps = val.data_quality.at(d).gaps;
        val.findings[d] = classify_findings(inputs);
        val.verdicts[d] = overall_verdict(val.findings.at(d));
    }

    val.report_md = render_report(res);
    return res;
}

}  // namespace frtb
