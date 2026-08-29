"""frtb — educational FRTB (Basel market-risk framework) mechanics.

Sensitivities-Based Method (SBM), DRC-lite, RRAO, an IMA sketch (ES with
liquidity horizons, IMCC, PLAT, backtesting, SES) and an independent model
validation framework, on a small self-contained portfolio.

NOT a compliant capital engine: the parameter set is an educational,
Basel-2019-flavored simplification (see data/sbm_params.json and API_SPEC.md).
Everything is deterministic — no RNG anywhere at runtime.
"""
from .engine import compute_results, compute_sa, load_pnl_csv
from .ima import (BacktestResult, backtest, backtest_multiplier, backtest_zone,
                  es_base_10d, es_lh_scaled, expected_shortfall_daily,
                  ima_capital, imcc, ses)
from .instruments import (Bond, Desk, EquityOption, FxForward, Instrument,
                          PayerSwap, RraoFlag, load_portfolio)
from .market import Curve, EquityQuote, Market, load_market
from .params import EquityBucketParams, SbmParams, load_params
from .plat import PlatResult, plat_surcharge, plat_test, plat_zone_from_metrics
from .pricers import (binomial_price, bs_delta, bs_price, bs_vega,
                      price_instrument, price_portfolio)
from .sa import (DrcPosition, DrcResult, SbmResult, drc_charge,
                 drc_positions_from_instruments, rrao_charge, sbm_capital)
from .sbm import (SCENARIOS, aggregate_buckets, bucket_kb, curvature_bucket_kb,
                  curvature_charge, delta_vega_charge, psi, scale_rho)
from .sensitivities import Sensitivities, compute_sensitivities
from .stats import average_ranks, ks_statistic, pearson, spearman
from .validation import (Finding, benchmark_max_diff, classify_findings,
                         data_quality, overall_verdict, render_report,
                         sensitivity_max_diff)

__all__ = [name for name in dir() if not name.startswith("_")]
__version__ = "1.0.0"
