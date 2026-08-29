/// \file instruments.hpp
/// \brief Instrument definitions and portfolio loading.
///
/// Instrument universe (deliberately small — this is an educational FRTB kit):
///  * Bond         — fixed-coupon annual-pay bullet bond (also the DRC vehicle).
///  * PayerSwap    — payer-swap proxy: long floating leg / short fixed leg,
///                   priced as N*(1 - DF(T)) - c*N*sum DF(t_i).  Its GIRR
///                   sensitivity is the "DV01 ladder" of the spec.
///  * EquityOption — European equity option under Black-Scholes.
///  * FxForward    — FX forward, valued in the domestic (quote) currency.

#pragma once

#include <map>
#include <optional>
#include <string>
#include <variant>
#include <vector>

#include "frtb/json.hpp"

namespace frtb {

/// Residual-risk add-on flag: category ("exotic" or "other") + notional base.
struct RraoFlag {
    std::string category;
    double notional = 0.0;

    /// \throws std::invalid_argument on unknown category / negative notional.
    RraoFlag(std::string category_, double notional_);
};

/// Fixed-coupon annual-pay bullet bond.
///
/// \c notional may be negative (short position, used by DRC netting).
/// Coupons are paid at T, T-1, ... (annual, stub-free by construction).
struct Bond {
    std::string inst_id;
    double notional = 0.0;
    double coupon = 0.0;
    double maturity = 0.0;
    std::string currency;
    std::string issuer;
    std::string rating;
    double lgd = 0.75;
    std::optional<RraoFlag> rrao;

    /// \throws std::invalid_argument on zero notional, non-positive maturity,
    /// LGD outside [0,1] or non-finite numeric fields.
    Bond(std::string inst_id_, double notional_, double coupon_, double maturity_,
         std::string currency_, std::string issuer_, std::string rating_, double lgd_ = 0.75,
         std::optional<RraoFlag> rrao_ = std::nullopt);

    /// Annual coupon payment times T, T-1, ... (> 0), sorted ascending.
    std::vector<double> coupon_times() const;
};

/// Payer interest-rate swap proxy (pay fixed, receive float).
///
/// Value = N*(1 - DF(T)) - fixed_rate*N*sum_i DF(t_i) with annual fixed payments.
struct PayerSwap {
    std::string inst_id;
    double notional = 0.0;
    double fixed_rate = 0.0;
    double maturity = 0.0;
    std::string currency;
    std::optional<RraoFlag> rrao;

    /// \throws std::invalid_argument on zero notional or maturity < 1y.
    PayerSwap(std::string inst_id_, double notional_, double fixed_rate_, double maturity_,
              std::string currency_, std::optional<RraoFlag> rrao_ = std::nullopt);

    /// Annual fixed-leg payment times, sorted ascending.
    std::vector<double> fixed_times() const;
};

/// European equity option; value = position * contracts * BS(...).
struct EquityOption {
    std::string inst_id;
    std::string underlier;
    std::string option_type;  ///< "call" | "put"
    int position = 1;         ///< +1 long, -1 short
    double contracts = 0.0;
    double strike = 0.0;
    double maturity = 0.0;
    std::string currency;
    std::optional<RraoFlag> rrao;

    /// \throws std::invalid_argument on bad option type/position/strike/maturity.
    EquityOption(std::string inst_id_, std::string underlier_, std::string option_type_,
                 int position_, double contracts_, double strike_, double maturity_,
                 std::string currency_, std::optional<RraoFlag> rrao_ = std::nullopt);
};

/// FX forward on pair FORDOM (e.g. EURUSD): long N foreign at strike K.
///
/// Value in domestic ccy = N * (S * DF_for(T) - K * DF_dom(T)).
struct FxForward {
    std::string inst_id;
    std::string pair;
    double notional = 0.0;
    double strike = 0.0;
    double maturity = 0.0;
    std::optional<RraoFlag> rrao;

    /// \throws std::invalid_argument on zero notional, bad strike/maturity/pair.
    FxForward(std::string inst_id_, std::string pair_, double notional_, double strike_,
              double maturity_, std::optional<RraoFlag> rrao_ = std::nullopt);

    std::string foreign() const { return pair.substr(0, 3); }
    std::string domestic() const { return pair.substr(3); }
};

/// Closed instrument universe.
using Instrument = std::variant<Bond, PayerSwap, EquityOption, FxForward>;

/// RRAO flag of any instrument (nullopt when not flagged).
const std::optional<RraoFlag>& instrument_rrao(const Instrument& inst);

/// Parse one instrument JSON object (portfolio.json schema).
/// \throws std::invalid_argument on unknown type or bad fields.
Instrument instrument_from_json(const json::Value& d);

/// A trading desk: name + instrument list (may be empty — capital is then 0).
struct Desk {
    std::string name;
    std::string display;
    std::vector<Instrument> instruments;
};

/// Load portfolio.json -> {desk_name: Desk}.
/// \throws std::invalid_argument on schema errors or duplicate desk names.
std::map<std::string, Desk> load_portfolio(const std::string& path);

}  // namespace frtb
