/// \file market.hpp
/// \brief Market data containers: zero curves, equity quotes, FX spots.
///
/// All bump operations return *new* objects (the engine treats markets as
/// immutable snapshots) so bump-and-revalue sensitivities cannot leak state.

#pragma once

#include <map>
#include <string>
#include <vector>

namespace frtb {

/// Continuously-compounded zero curve with linear interpolation in tenor.
///
/// Rates are interpolated linearly between nodes and extrapolated flat beyond
/// the first/last node.  Discount factor: DF(t) = exp(-z(t) * t), DF(0) = 1.
class Curve {
public:
    /// Construct from strictly increasing positive tenors and finite rates.
    /// \throws std::invalid_argument on empty/mismatched/non-monotone inputs.
    Curve(std::vector<double> tenors, std::vector<double> rates);

    /// Interpolated zero rate at time t (flat extrapolation).
    double rate(double t) const;

    /// Discount factor exp(-z(t)*t); DF(0) = 1.
    double df(double t) const;

    /// Curve with the zero rate at one node shifted by \p size (absolute).
    /// \throws std::invalid_argument when \p tenor is not a curve node.
    Curve bumped_node(double tenor, double size) const;

    /// Curve with every node shifted by \p size (absolute).
    Curve bumped_parallel(double size) const;

    const std::vector<double>& tenors() const { return tenors_; }
    const std::vector<double>& rates() const { return rates_; }

private:
    std::vector<double> tenors_;
    std::vector<double> rates_;
};

/// Equity market data: spot, flat lognormal vol, dividend yield, SBM bucket.
struct EquityQuote {
    double spot = 0.0;
    double vol = 0.0;
    double div_yield = 0.0;
    std::string bucket;

    EquityQuote() = default;
    /// \throws std::invalid_argument on non-positive spot or negative vol.
    EquityQuote(double spot_, double vol_, double div_yield_, std::string bucket_);
};

/// Immutable market snapshot: curves per currency, equities per name, FX spots
/// per pair.  Bump helpers all return new Market objects.
struct Market {
    std::map<std::string, Curve> curves;
    std::map<std::string, EquityQuote> equities;
    std::map<std::string, double> fx;

    /// Lookups; each throws std::invalid_argument when the key is unknown.
    const Curve& curve(const std::string& ccy) const;
    const EquityQuote& equity(const std::string& name) const;
    double fx_spot(const std::string& pair) const;

    /// Snapshot with one currency's curve replaced.
    Market with_curve(const std::string& ccy, Curve curve) const;
    /// Snapshot with one curve node bumped by \p size (absolute).
    Market bump_curve_node(const std::string& ccy, double tenor, double size) const;
    /// Snapshot with one currency's curve shifted in parallel by \p size.
    Market bump_curve_parallel(const std::string& ccy, double size) const;
    /// Snapshot with a relative equity spot bump: S -> S * (1 + rel).
    Market bump_equity_spot(const std::string& name, double rel) const;
    /// Snapshot with an absolute equity vol bump: sigma -> sigma + size.
    Market bump_equity_vol(const std::string& name, double size) const;
    /// Snapshot with a relative FX spot bump: S -> S * (1 + rel).
    Market bump_fx(const std::string& pair, double rel) const;
};

/// Load a Market from curves.csv (currency,tenor,zero_rate) and spots.csv
/// (kind,name,spot,vol,div_yield,eq_bucket).
/// \throws std::invalid_argument on I/O or schema errors.
Market load_market(const std::string& curves_csv, const std::string& spots_csv);

}  // namespace frtb
