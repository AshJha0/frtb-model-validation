//! # frtb — FRTB & Model Validation (Rust port)
//!
//! Educational implementation of the Basel FRTB framework mechanics on a
//! simplified portfolio, mirroring the cross-language contract in
//! `API_SPEC.md` (the Python package under `python/src/frtb` is the
//! reference implementation).
//!
//! > **EDUCATIONAL PARAMETER SET — NOT COMPLIANT BASEL TEXT.** Every risk
//! > weight, correlation, bucket set, threshold and formula simplification
//! > is a pinned, Basel-2019-flavored teaching value (simplified buckets,
//! > no securitisations, subset of risk classes). Never use this engine or
//! > its parameters for real regulatory capital.
//!
//! ## Modules
//!
//! * [`market`] / [`instruments`] / [`pricers`] — market snapshots, the
//!   small instrument universe and its deterministic pricer kit
//!   (Black-Scholes with edge cases, CRR binomial benchmark, bond / swap
//!   proxy / FX forward off zero curves).
//! * [`params`] — the pinned parameter set loaded from
//!   `data/sbm_params.json`.
//! * [`sensitivities`] — pinned bump-and-revalue deltas, vegas and
//!   curvature CVRs.
//! * [`sbm`] / [`sa`] — SBM aggregation (within/across buckets, three
//!   correlation scenarios, curvature with psi) and the SA assembly with
//!   DRC-lite and RRAO.
//! * [`stats`] / [`plat`] / [`ima`] — native Spearman/KS statistics, the
//!   P&L attribution test, ES 97.5 with the liquidity-horizon ladder,
//!   IMCC, VaR backtesting and NMRF/SES.
//! * [`validation`] — the independent validation framework: benchmark and
//!   sensitivity checks, capital stability, data quality, the pinned
//!   findings rule table and the markdown report generator.
//! * [`engine`] — end-to-end orchestration over the bundled data set.
//!
//! ## Determinism
//!
//! There is no RNG anywhere at runtime: every number is a closed-form or
//! lattice computation over the bundled deterministic data. The crate has
//! no `rand` dependency at all.
//!
//! ## Errors
//!
//! Every fallible function returns `Result<_, `[`FrtbError`]`>` with a
//! message naming the offending input (the Python reference raises
//! `ValueError` in the same places). Nothing panics on bad input.

pub mod engine;
pub mod error;
pub mod ima;
pub mod instruments;
pub mod market;
pub mod params;
pub mod plat;
pub mod pricers;
pub mod sa;
pub mod sbm;
pub mod sensitivities;
pub mod stats;
pub mod validation;

pub use engine::{compute_results, compute_sa, desk_categories, load_pnl_csv, Results, SaScope};
pub use error::FrtbError;
pub use ima::{
    backtest, backtest_multiplier, backtest_zone, es_base_10d, es_lh_scaled,
    expected_shortfall_daily, ima_capital, imcc, ses, BacktestResult, DeskIma, NmrfEntry,
};
pub use instruments::{
    instrument_from_json, load_portfolio, Bond, Desk, EquityOption, FxForward, Instrument,
    PayerSwap, RraoFlag,
};
pub use market::{load_market, Curve, EquityQuote, Market};
pub use params::{load_params, EquityBucketParams, SbmParams};
pub use plat::{plat_surcharge, plat_test, plat_zone_from_metrics, PlatResult};
pub use pricers::{
    binomial_price, bs_delta, bs_price, bs_vega, norm_cdf, norm_pdf, price_bond,
    price_equity_option, price_fx_forward, price_instrument, price_payer_swap, price_portfolio,
};
pub use sa::{
    drc_charge, drc_positions_from_instruments, rrao_charge, sbm_capital, DrcPosition, DrcResult,
    SbmResult, MEASURES, RISK_CLASSES,
};
pub use sbm::{
    aggregate_buckets, bucket_kb, curvature_bucket_kb, curvature_charge, delta_vega_charge, psi,
    scale_rho, AggregateResult, RiskClassCharge, Scenario, SCENARIOS,
};
pub use sensitivities::{compute_sensitivities, Sensitivities};
pub use stats::{average_ranks, ks_statistic, pearson, spearman};
pub use validation::{
    benchmark_max_diff, classify_findings, data_quality, overall_verdict, render_report,
    sensitivity_max_diff, DataQuality, DeskCheckInputs, Finding, ValidationSummary,
    REPORT_SECTIONS,
};
