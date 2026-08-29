#include "frtb/market.hpp"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <fstream>
#include <sstream>
#include <stdexcept>
#include <utility>

namespace frtb {

Curve::Curve(std::vector<double> tenors, std::vector<double> rates)
    : tenors_(std::move(tenors)), rates_(std::move(rates)) {
    if (tenors_.empty() || tenors_.size() != rates_.size())
        throw std::invalid_argument("Curve: tenors and rates must be non-empty and equal length");
    for (std::size_t i = 1; i < tenors_.size(); ++i)
        if (tenors_[i] <= tenors_[i - 1])
            throw std::invalid_argument("Curve: tenors must be strictly increasing");
    for (std::size_t i = 0; i < tenors_.size(); ++i)
        if (!(std::isfinite(tenors_[i]) && std::isfinite(rates_[i])) || tenors_[i] <= 0.0)
            throw std::invalid_argument("Curve: tenors must be positive finite, rates finite");
}

double Curve::rate(double t) const {
    if (!std::isfinite(t) || t < 0.0)
        throw std::invalid_argument("Curve::rate: invalid time " + std::to_string(t));
    const auto& ts = tenors_;
    const auto& rs = rates_;
    if (t <= ts.front()) return rs.front();
    if (t >= ts.back()) return rs.back();
    for (std::size_t i = 1; i < ts.size(); ++i) {
        if (t <= ts[i]) {
            // Same interpolation arithmetic as the Python reference.
            double w = (t - ts[i - 1]) / (ts[i] - ts[i - 1]);
            return rs[i - 1] * (1.0 - w) + rs[i] * w;
        }
    }
    return rs.back();  // unreachable
}

double Curve::df(double t) const {
    if (t == 0.0) return 1.0;
    return std::exp(-rate(t) * t);
}

Curve Curve::bumped_node(double tenor, double size) const {
    bool found = false;
    for (double tt : tenors_)
        if (tt == tenor) found = true;
    if (!found)
        throw std::invalid_argument("Curve::bumped_node: tenor " + std::to_string(tenor) +
                                    " is not a curve node");
    std::vector<double> rates = rates_;
    for (std::size_t i = 0; i < tenors_.size(); ++i)
        if (tenors_[i] == tenor) rates[i] = rates_[i] + size;
    return Curve(tenors_, rates);
}

Curve Curve::bumped_parallel(double size) const {
    std::vector<double> rates = rates_;
    for (double& r : rates) r += size;
    return Curve(tenors_, rates);
}

EquityQuote::EquityQuote(double spot_, double vol_, double div_yield_, std::string bucket_)
    : spot(spot_), vol(vol_), div_yield(div_yield_), bucket(std::move(bucket_)) {
    if (!std::isfinite(spot) || spot <= 0.0)
        throw std::invalid_argument("EquityQuote: spot must be positive finite");
    if (!std::isfinite(vol) || vol < 0.0)
        throw std::invalid_argument("EquityQuote: vol must be >= 0");
    if (!std::isfinite(div_yield))
        throw std::invalid_argument("EquityQuote: div_yield must be finite");
}

const Curve& Market::curve(const std::string& ccy) const {
    auto it = curves.find(ccy);
    if (it == curves.end())
        throw std::invalid_argument("Market: no curve for currency '" + ccy + "'");
    return it->second;
}

const EquityQuote& Market::equity(const std::string& name) const {
    auto it = equities.find(name);
    if (it == equities.end())
        throw std::invalid_argument("Market: no equity quote for '" + name + "'");
    return it->second;
}

double Market::fx_spot(const std::string& pair) const {
    auto it = fx.find(pair);
    if (it == fx.end())
        throw std::invalid_argument("Market: no FX spot for pair '" + pair + "'");
    return it->second;
}

Market Market::with_curve(const std::string& ccy, Curve curve) const {
    Market m = *this;
    m.curves.erase(ccy);
    m.curves.emplace(ccy, std::move(curve));
    return m;
}

Market Market::bump_curve_node(const std::string& ccy, double tenor, double size) const {
    return with_curve(ccy, curve(ccy).bumped_node(tenor, size));
}

Market Market::bump_curve_parallel(const std::string& ccy, double size) const {
    return with_curve(ccy, curve(ccy).bumped_parallel(size));
}

Market Market::bump_equity_spot(const std::string& name, double rel) const {
    const EquityQuote& q = equity(name);
    Market m = *this;
    m.equities[name] = EquityQuote(q.spot * (1.0 + rel), q.vol, q.div_yield, q.bucket);
    return m;
}

Market Market::bump_equity_vol(const std::string& name, double size) const {
    const EquityQuote& q = equity(name);
    Market m = *this;
    m.equities[name] = EquityQuote(q.spot, q.vol + size, q.div_yield, q.bucket);
    return m;
}

Market Market::bump_fx(const std::string& pair, double rel) const {
    double s = fx_spot(pair);
    Market m = *this;
    m.fx[pair] = s * (1.0 + rel);
    return m;
}

namespace {

/// Split one CSV line into cells (the bundled files have no quoting).
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

double to_double(const std::string& s, const std::string& ctx) {
    char* end = nullptr;
    std::string t = strip(s);
    double v = std::strtod(t.c_str(), &end);
    if (t.empty() || end != t.c_str() + t.size())
        throw std::invalid_argument("load_market: bad number '" + s + "' in " + ctx);
    return v;
}

}  // namespace

Market load_market(const std::string& curves_csv, const std::string& spots_csv) {
    std::ifstream cf(curves_csv);
    if (!cf) throw std::invalid_argument("load_market: cannot open " + curves_csv);
    std::string line;
    if (!std::getline(cf, line))
        throw std::invalid_argument("load_market: empty file " + curves_csv);
    std::map<std::string, std::vector<std::pair<double, double>>> by_ccy;
    while (std::getline(cf, line)) {
        if (strip(line).empty()) continue;
        auto cells = split_csv(line);
        if (cells.size() != 3)
            throw std::invalid_argument("load_market: bad row in " + curves_csv);
        by_ccy[strip(cells[0])].emplace_back(to_double(cells[1], curves_csv),
                                             to_double(cells[2], curves_csv));
    }
    if (by_ccy.empty())
        throw std::invalid_argument("load_market: no curves in " + curves_csv);

    Market m;
    for (auto& [ccy, pts] : by_ccy) {
        std::sort(pts.begin(), pts.end());
        std::vector<double> tenors, rates;
        for (const auto& p : pts) {
            tenors.push_back(p.first);
            rates.push_back(p.second);
        }
        m.curves.emplace(ccy, Curve(tenors, rates));
    }

    std::ifstream sf(spots_csv);
    if (!sf) throw std::invalid_argument("load_market: cannot open " + spots_csv);
    if (!std::getline(sf, line))
        throw std::invalid_argument("load_market: empty file " + spots_csv);
    while (std::getline(sf, line)) {
        if (strip(line).empty()) continue;
        auto cells = split_csv(line);
        if (cells.size() < 6)
            throw std::invalid_argument("load_market: bad row in " + spots_csv);
        std::string kind = strip(cells[0]);
        std::string name = strip(cells[1]);
        if (kind == "equity") {
            m.equities[name] = EquityQuote(to_double(cells[2], spots_csv),
                                           to_double(cells[3], spots_csv),
                                           to_double(cells[4], spots_csv), strip(cells[5]));
        } else if (kind == "fx") {
            m.fx[name] = to_double(cells[2], spots_csv);
        } else {
            throw std::invalid_argument("load_market: unknown kind '" + kind + "' in " + spots_csv);
        }
    }
    return m;
}

}  // namespace frtb
