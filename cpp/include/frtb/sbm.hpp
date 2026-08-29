/// \file sbm.hpp
/// \brief Sensitivities-Based Method (SBM) aggregation.
///
/// Formulas (FRTB structure, educational parameter set):
///
///   WS_k = RW_k * s_k
///   K_b  = sqrt(max(0, sum_k WS_k^2 + sum_{k != l} rho_kl WS_k WS_l))
///   Charge = sqrt(max(0, sum_b K_b^2 + sum_{b != c} gamma_bc S_b S_c))
///
/// with S_b = sum_k WS_k; if the argument of the outer sqrt is negative the
/// S_b FALLBACK applies: S_b = max(min(sum_k WS_k, K_b), -K_b) and the
/// aggregate is recomputed once (the max(0,.) guard stays as protection
/// against negative rounding).
///
/// Correlation scenarios (applied to every rho and gamma):
///   high   rho -> min(1.25 * rho, 1.0)
///   medium rho -> rho
///   low    rho -> 0.75 * rho   (pinned simplification of Basel's
///                               max(2*rho - 1, 0.75*rho))
/// SBM capital is the MAX of the three scenario totals.
///
/// Curvature: CVR+/- with the delta term stripped; psi(a,b) = 0 iff both
/// negative; curvature correlations are the squares of the scenario-scaled
/// delta correlations; no S_b fallback; up-side-on-tie (pinned, documented).

#pragma once

#include <functional>
#include <map>
#include <string>
#include <utility>
#include <vector>

namespace frtb {

/// The three FRTB correlation scenarios, in the pinned evaluation order.
inline const std::vector<std::string> SCENARIOS = {"high", "medium", "low"};

/// Apply the correlation scenario scaler; "high" is capped at 1.0.
/// \throws std::invalid_argument on an unknown scenario.
double scale_rho(double rho, const std::string& scenario, double high = 1.25, double low = 0.75);

/// Within-bucket charge K_b = sqrt(max(0, sum WS^2 + sum_{k!=l} rho WS_k WS_l)).
///
/// \p rho supplies the pairwise correlation for k != l.
/// \throws std::invalid_argument on non-finite weighted sensitivities.
double bucket_kb(const std::vector<double>& ws,
                 const std::function<double(std::size_t, std::size_t)>& rho);

/// Across-bucket aggregation output (with the S_b fallback bookkeeping).
struct AggregateResult {
    double charge = 0.0;
    bool used_fallback = false;
    std::map<std::string, double> sb;  ///< the S_b actually used (post-fallback)
};

/// Across-bucket aggregation with the FRTB S_b fallback rule.
///
/// First tries S_b = sum_k WS_k.  If sum_b K_b^2 + sum_{b!=c} gamma S_b S_c
/// < 0, recomputes with S_b = max(min(sum_k WS_k, K_b), -K_b).
/// \throws std::invalid_argument when kb and ws_sum cover different buckets.
AggregateResult aggregate_buckets(
    const std::map<std::string, double>& kb, const std::map<std::string, double>& ws_sum,
    const std::function<double(const std::string&, const std::string&)>& gamma);

/// Per-scenario charge with per-bucket K_b detail (for reporting/golden).
struct RiskClassCharge {
    double charge = 0.0;
    std::map<std::string, double> kb;
    bool used_fallback = false;
};

/// {bucket: {factor_key: WS}} — factor keys iterate in lexicographic order,
/// mirroring the Python reference's sorted() over string keys.
using BucketWs = std::map<std::string, std::map<std::string, double>>;
/// (bucket, factor_k, factor_l) -> medium-scenario correlation.
using IntraRhoFn =
    std::function<double(const std::string&, const std::string&, const std::string&)>;
/// (bucket_b, bucket_c) -> medium-scenario cross-bucket gamma.
using GammaFn = std::function<double(const std::string&, const std::string&)>;

/// Generic delta or vega charge for one risk class under one scenario.
RiskClassCharge delta_vega_charge(const BucketWs& bucket_ws, const IntraRhoFn& intra_rho,
                                  const GammaFn& gamma, const std::string& scenario,
                                  double scenario_high = 1.25, double scenario_low = 0.75);

/// FRTB psi: 0 when both CVR terms are negative, else 1.
double psi(double a, double b);

/// Within-bucket curvature charge.
///
/// Returns (K_b, S_b) where K_b = max(K_b+, K_b-) and S_b is the CVR sum on
/// the winning side (up side on ties).  \p rho must already be the CURVATURE
/// correlation (delta rho squared).
/// \throws std::invalid_argument when up/down CVR lists mismatch.
std::pair<double, double> curvature_bucket_kb(
    const std::vector<double>& cvr_up, const std::vector<double>& cvr_dn,
    const std::function<double(std::size_t, std::size_t)>& rho);

/// {bucket: (list CVR+, list CVR-)} aligned with the bucket's factor keys.
using BucketCvr = std::map<std::string, std::pair<std::vector<double>, std::vector<double>>>;
/// {bucket: ordered factor keys}.
using FactorKeys = std::map<std::string, std::vector<std::string>>;

/// Curvature charge for one risk class under one scenario.
///
/// \p intra_rho / \p gamma supply medium DELTA correlations; they are
/// scenario-scaled then SQUARED for curvature (pinned simplification).
RiskClassCharge curvature_charge(const BucketCvr& bucket_cvr, const IntraRhoFn& intra_rho,
                                 const GammaFn& gamma, const std::string& scenario,
                                 const FactorKeys& factor_keys, double scenario_high = 1.25,
                                 double scenario_low = 0.75);

}  // namespace frtb
