#include "frtb/instruments.hpp"

#include <algorithm>
#include <cmath>
#include <stdexcept>
#include <utility>

namespace frtb {

namespace {

void check_finite(const std::string& name, double value) {
    if (!std::isfinite(value))
        throw std::invalid_argument(name + " must be finite");
}

/// Annual payment ladder T, T-1, ... (> 0), ascending — shared by bond & swap.
std::vector<double> annual_times(double maturity) {
    std::vector<double> times;
    double t = maturity;
    while (t > 1e-9) {
        times.push_back(t);
        t -= 1.0;
    }
    std::sort(times.begin(), times.end());
    return times;
}

}  // namespace

RraoFlag::RraoFlag(std::string category_, double notional_)
    : category(std::move(category_)), notional(notional_) {
    if (category != "exotic" && category != "other")
        throw std::invalid_argument("RraoFlag: category must be 'exotic' or 'other', got '" +
                                    category + "'");
    if (!std::isfinite(notional) || notional < 0.0)
        throw std::invalid_argument("RraoFlag: notional must be a non-negative finite number");
}

Bond::Bond(std::string inst_id_, double notional_, double coupon_, double maturity_,
           std::string currency_, std::string issuer_, std::string rating_, double lgd_,
           std::optional<RraoFlag> rrao_)
    : inst_id(std::move(inst_id_)), notional(notional_), coupon(coupon_), maturity(maturity_),
      currency(std::move(currency_)), issuer(std::move(issuer_)), rating(std::move(rating_)),
      lgd(lgd_), rrao(std::move(rrao_)) {
    check_finite("Bond.notional", notional);
    check_finite("Bond.coupon", coupon);
    if (notional == 0.0)
        throw std::invalid_argument("Bond: notional must be non-zero");
    if (maturity <= 0.0 || !std::isfinite(maturity))
        throw std::invalid_argument("Bond: maturity must be positive");
    if (!(0.0 <= lgd && lgd <= 1.0))
        throw std::invalid_argument("Bond: LGD must be in [0,1]");
}

std::vector<double> Bond::coupon_times() const { return annual_times(maturity); }

PayerSwap::PayerSwap(std::string inst_id_, double notional_, double fixed_rate_, double maturity_,
                     std::string currency_, std::optional<RraoFlag> rrao_)
    : inst_id(std::move(inst_id_)), notional(notional_), fixed_rate(fixed_rate_),
      maturity(maturity_), currency(std::move(currency_)), rrao(std::move(rrao_)) {
    check_finite("PayerSwap.notional", notional);
    check_finite("PayerSwap.fixed_rate", fixed_rate);
    if (notional == 0.0)
        throw std::invalid_argument("PayerSwap: notional must be non-zero");
    if (maturity < 1.0 || !std::isfinite(maturity))
        throw std::invalid_argument("PayerSwap: maturity must be >= 1y");
}

std::vector<double> PayerSwap::fixed_times() const { return annual_times(maturity); }

EquityOption::EquityOption(std::string inst_id_, std::string underlier_, std::string option_type_,
                           int position_, double contracts_, double strike_, double maturity_,
                           std::string currency_, std::optional<RraoFlag> rrao_)
    : inst_id(std::move(inst_id_)), underlier(std::move(underlier_)),
      option_type(std::move(option_type_)), position(position_), contracts(contracts_),
      strike(strike_), maturity(maturity_), currency(std::move(currency_)), rrao(std::move(rrao_)) {
    if (option_type != "call" && option_type != "put")
        throw std::invalid_argument("EquityOption: option_type must be call/put, got '" +
                                    option_type + "'");
    if (position != 1 && position != -1)
        throw std::invalid_argument("EquityOption: position must be +1 or -1");
    if (contracts <= 0.0 || !std::isfinite(contracts))
        throw std::invalid_argument("EquityOption: contracts must be positive");
    if (strike <= 0.0 || !std::isfinite(strike))
        throw std::invalid_argument("EquityOption: strike must be positive");
    if (maturity < 0.0 || !std::isfinite(maturity))
        throw std::invalid_argument("EquityOption: maturity must be >= 0");
}

FxForward::FxForward(std::string inst_id_, std::string pair_, double notional_, double strike_,
                     double maturity_, std::optional<RraoFlag> rrao_)
    : inst_id(std::move(inst_id_)), pair(std::move(pair_)), notional(notional_), strike(strike_),
      maturity(maturity_), rrao(std::move(rrao_)) {
    check_finite("FxForward.notional", notional);
    if (notional == 0.0)
        throw std::invalid_argument("FxForward: notional must be non-zero");
    if (strike <= 0.0 || !std::isfinite(strike))
        throw std::invalid_argument("FxForward: strike must be positive");
    if (maturity <= 0.0 || !std::isfinite(maturity))
        throw std::invalid_argument("FxForward: maturity must be positive");
    if (pair.size() != 6)
        throw std::invalid_argument("FxForward: pair must be 6 chars FORDOM, got '" + pair + "'");
}

const std::optional<RraoFlag>& instrument_rrao(const Instrument& inst) {
    return std::visit([](const auto& i) -> const std::optional<RraoFlag>& { return i.rrao; }, inst);
}

namespace {

std::optional<RraoFlag> parse_rrao(const json::Value& d) {
    if (!d.has("rrao")) return std::nullopt;
    const json::Value& r = d.at("rrao");
    if (r.type == json::Value::Type::Null) return std::nullopt;
    return RraoFlag(r.at("category").as_string(), r.at("notional").as_number());
}

}  // namespace

Instrument instrument_from_json(const json::Value& d) {
    if (!d.has("type"))
        throw std::invalid_argument("instrument_from_json: missing instrument 'type'");
    const std::string typ = d.at("type").as_string();
    if (typ == "bond") {
        double lgd = d.has("lgd") ? d.at("lgd").as_number() : 0.75;
        return Bond(d.at("id").as_string(), d.at("notional").as_number(),
                    d.at("coupon").as_number(), d.at("maturity").as_number(),
                    d.at("currency").as_string(), d.at("issuer").as_string(),
                    d.at("rating").as_string(), lgd, parse_rrao(d));
    }
    if (typ == "payer_swap") {
        return PayerSwap(d.at("id").as_string(), d.at("notional").as_number(),
                         d.at("fixed_rate").as_number(), d.at("maturity").as_number(),
                         d.at("currency").as_string(), parse_rrao(d));
    }
    if (typ == "equity_option") {
        return EquityOption(d.at("id").as_string(), d.at("underlier").as_string(),
                            d.at("option_type").as_string(),
                            static_cast<int>(d.at("position").as_number()),
                            d.at("contracts").as_number(), d.at("strike").as_number(),
                            d.at("maturity").as_number(), d.at("currency").as_string(),
                            parse_rrao(d));
    }
    if (typ == "fx_forward") {
        return FxForward(d.at("id").as_string(), d.at("pair").as_string(),
                         d.at("notional").as_number(), d.at("strike").as_number(),
                         d.at("maturity").as_number(), parse_rrao(d));
    }
    throw std::invalid_argument("instrument_from_json: unknown instrument type '" + typ + "'");
}

std::map<std::string, Desk> load_portfolio(const std::string& path) {
    json::Value raw = json::parse_file(path);
    if (!raw.has("desks") || raw.at("desks").type != json::Value::Type::Array)
        throw std::invalid_argument("load_portfolio: portfolio.json must contain a 'desks' list");
    std::map<std::string, Desk> desks;
    for (const json::Value& d : raw.at("desks").array) {
        std::string name = d.at("name").as_string();
        if (desks.count(name))
            throw std::invalid_argument("load_portfolio: duplicate desk name '" + name + "'");
        Desk desk;
        desk.name = name;
        desk.display = d.has("display") ? d.at("display").as_string() : name;
        if (d.has("instruments"))
            for (const json::Value& x : d.at("instruments").array)
                desk.instruments.push_back(instrument_from_json(x));
        desks.emplace(name, std::move(desk));
    }
    return desks;
}

}  // namespace frtb
