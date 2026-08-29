//! Pinned regulatory parameter set (loaded from `data/sbm_params.json`).
//!
//! IMPORTANT: the parameter values are an EDUCATIONAL, Basel-2019-flavored
//! set — simplified bucket structure, pinned correlations, no
//! securitisation buckets. They are NOT the official Basel text and must
//! not be used for real capital.

use std::collections::BTreeMap;
use std::path::Path;

use serde_json::{Map, Value};

use crate::error::{invalid, FrtbError, Result};

/// Per-bucket equity parameters: delta RW, vega RW, intra-bucket rho.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct EquityBucketParams {
    /// Delta risk weight.
    pub delta_rw: f64,
    /// Vega risk weight.
    pub vega_rw: f64,
    /// Intra-bucket correlation (medium scenario).
    pub rho: f64,
}

/// Full pinned parameter set for SBM + DRC + RRAO + IMA + PLAT.
///
/// Loaded from `data/sbm_params.json`; all lookups return an error with a
/// clear message when a bucket / rating / tenor is missing (spec edge
/// case).
#[derive(Debug, Clone, PartialEq)]
pub struct SbmParams {
    /// GIRR curve tenors (the pinned 10-point ladder).
    pub girr_tenors: Vec<f64>,
    /// GIRR delta risk weights, aligned with `girr_tenors`.
    pub girr_delta_rw: Vec<f64>,
    /// GIRR tenor x tenor correlation matrix (medium scenario).
    pub girr_rho: Vec<Vec<f64>>,
    /// GIRR vega risk weight (unused by the bundled book: no IR-vol
    /// instruments, so GIRR vega is identically zero).
    pub girr_vega_rw: f64,
    /// GIRR curvature risk weight (parallel absolute shift).
    pub girr_curvature_rw: f64,
    /// GIRR cross-currency gamma.
    pub girr_gamma: f64,
    /// Equity bucket parameters keyed by bucket label.
    pub equity_buckets: BTreeMap<String, EquityBucketParams>,
    /// Equity cross-bucket gamma.
    pub equity_gamma: f64,
    /// FX delta risk weight (single pinned bucket).
    pub fx_delta_rw: f64,
    /// FX intra-bucket rho.
    pub fx_rho: f64,
    /// FX cross-bucket gamma (unused with a single bucket).
    pub fx_gamma: f64,
    /// High-scenario correlation scaler (capped at 1 after scaling).
    pub scenario_high: f64,
    /// Low-scenario correlation scaler.
    pub scenario_low: f64,
    /// DRC risk weights by rating label.
    pub drc_rw_by_rating: BTreeMap<String, f64>,
    /// RRAO rates by category ("exotic", "other").
    pub rrao_rates: BTreeMap<String, f64>,
    /// ES confidence level (0.975 pinned).
    pub ima_alpha: f64,
    /// IMCC rho (0.5 pinned).
    pub ima_rho: f64,
    /// Liquidity-horizon ladder in days (10, 20, 40, 60, 120 pinned).
    pub lh_ladder: Vec<i64>,
    /// Pinned liquidity horizon per risk-factor category.
    pub category_lh: BTreeMap<String, i64>,
    /// Amber-zone backtest multipliers keyed by exception count (5..9).
    pub backtest_amber_multipliers: BTreeMap<i64, f64>,
    /// Green-zone backtest multiplier (1.5 pinned).
    pub backtest_base_multiplier: f64,
    /// Red-zone backtest multiplier (2.0 cap, also for counts > 12).
    pub backtest_red_multiplier: f64,
    /// PLAT green Spearman threshold (>=).
    pub plat_spearman_green: f64,
    /// PLAT amber Spearman threshold (below is red).
    pub plat_spearman_amber: f64,
    /// PLAT green KS threshold (<=).
    pub plat_ks_green: f64,
    /// PLAT amber KS threshold (above is red).
    pub plat_ks_amber: f64,
    /// Amber surcharge interpolation factor k (0.5 pinned).
    pub plat_k_surcharge: f64,
}

impl SbmParams {
    /// GIRR delta risk weight for one tenor; errors if the tenor is not a
    /// pinned curve node.
    pub fn girr_rw(&self, tenor: f64) -> Result<f64> {
        match self.girr_tenors.iter().position(|&t| t == tenor) {
            Some(i) => Ok(self.girr_delta_rw[i]),
            None => invalid(format!("SbmParams: no GIRR delta risk weight for tenor {tenor}")),
        }
    }

    /// GIRR tenor correlation by tenor indices.
    pub fn girr_rho_kl(&self, i: usize, j: usize) -> f64 {
        self.girr_rho[i][j]
    }

    /// Equity bucket parameters; errors on an unknown bucket label.
    pub fn equity_bucket(&self, bucket: &str) -> Result<&EquityBucketParams> {
        self.equity_buckets.get(bucket).ok_or_else(|| {
            let known: Vec<&String> = self.equity_buckets.keys().collect();
            FrtbError::Invalid(format!(
                "SbmParams: unknown equity bucket '{bucket}' (known: {known:?})"
            ))
        })
    }

    /// DRC risk weight for a rating; errors on an unknown rating.
    pub fn drc_rw(&self, rating: &str) -> Result<f64> {
        self.drc_rw_by_rating.get(rating).copied().ok_or_else(|| {
            let known: Vec<&String> = self.drc_rw_by_rating.keys().collect();
            FrtbError::Invalid(format!(
                "SbmParams: no DRC risk weight for rating '{rating}' (known: {known:?})"
            ))
        })
    }

    /// RRAO rate for a category; errors on an unknown category.
    pub fn rrao_rate(&self, category: &str) -> Result<f64> {
        self.rrao_rates
            .get(category)
            .copied()
            .ok_or_else(|| FrtbError::Invalid(format!("SbmParams: unknown RRAO category '{category}'")))
    }

    /// Copy with every GIRR delta RW scaled by `factor` (the +/-10%
    /// stability check of the validation framework).
    pub fn with_girr_delta_rw_scaled(&self, factor: f64) -> Result<SbmParams> {
        if factor <= 0.0 || !factor.is_finite() {
            return invalid(format!("with_girr_delta_rw_scaled: bad factor {factor}"));
        }
        let mut out = self.clone();
        out.girr_delta_rw = self.girr_delta_rw.iter().map(|&v| v * factor).collect();
        Ok(out)
    }
}

// ---------------------------------------------------------------- loading

fn require<'a>(d: &'a Map<String, Value>, key: &str, ctx: &str) -> Result<&'a Value> {
    d.get(key)
        .ok_or_else(|| FrtbError::Invalid(format!("sbm_params.json: missing '{key}' in {ctx}")))
}

fn as_obj<'a>(v: &'a Value, ctx: &str) -> Result<&'a Map<String, Value>> {
    v.as_object()
        .ok_or_else(|| FrtbError::Invalid(format!("sbm_params.json: '{ctx}' must be an object")))
}

fn as_f64(v: &Value, ctx: &str) -> Result<f64> {
    v.as_f64()
        .ok_or_else(|| FrtbError::Invalid(format!("sbm_params.json: '{ctx}' must be a number")))
}

fn as_i64(v: &Value, ctx: &str) -> Result<i64> {
    v.as_i64()
        .ok_or_else(|| FrtbError::Invalid(format!("sbm_params.json: '{ctx}' must be an integer")))
}

fn req_f64(d: &Map<String, Value>, key: &str, ctx: &str) -> Result<f64> {
    as_f64(require(d, key, ctx)?, key)
}

/// Load and validate the pinned parameter file; errors on any missing key
/// or malformed table (mirrors the reference's `ValueError` behavior).
pub fn load_params(path: &Path) -> Result<SbmParams> {
    let text = std::fs::read_to_string(path)
        .map_err(|e| FrtbError::Io(format!("cannot read {}: {e}", path.display())))?;
    let raw: Value = serde_json::from_str(&text)
        .map_err(|e| FrtbError::Io(format!("{}: bad JSON: {e}", path.display())))?;
    let root = as_obj(&raw, "root")?;

    let girr = as_obj(require(root, "girr", "root")?, "girr")?;
    let tenors: Vec<f64> = require(girr, "tenors", "girr")?
        .as_array()
        .ok_or_else(|| FrtbError::Invalid("sbm_params.json: girr.tenors must be a list".into()))?
        .iter()
        .map(|v| as_f64(v, "girr.tenors"))
        .collect::<Result<_>>()?;
    let rw_map = as_obj(require(girr, "delta_rw", "girr")?, "girr.delta_rw")?;
    let mut rw_by_tenor: Vec<(f64, f64)> = Vec::new();
    for (k, v) in rw_map {
        let t: f64 = k
            .parse()
            .map_err(|_| FrtbError::Invalid(format!("sbm_params.json: bad delta_rw tenor '{k}'")))?;
        rw_by_tenor.push((t, as_f64(v, "girr.delta_rw")?));
    }
    let mut delta_rw = Vec::with_capacity(tenors.len());
    for &t in &tenors {
        match rw_by_tenor.iter().find(|(tt, _)| *tt == t) {
            Some((_, v)) => delta_rw.push(*v),
            None => return invalid(format!("sbm_params.json: girr.delta_rw missing tenor {t}")),
        }
    }
    let rho_raw = require(girr, "delta_rho", "girr")?
        .as_array()
        .ok_or_else(|| FrtbError::Invalid("sbm_params.json: girr.delta_rho must be a matrix".into()))?;
    let n = tenors.len();
    let mut rho: Vec<Vec<f64>> = Vec::with_capacity(n);
    for row in rho_raw {
        let r: Vec<f64> = row
            .as_array()
            .ok_or_else(|| {
                FrtbError::Invalid("sbm_params.json: girr.delta_rho rows must be lists".into())
            })?
            .iter()
            .map(|v| as_f64(v, "girr.delta_rho"))
            .collect::<Result<_>>()?;
        rho.push(r);
    }
    if rho.len() != n || rho.iter().any(|r| r.len() != n) {
        return invalid("sbm_params.json: girr.delta_rho must be a square tenor x tenor matrix");
    }
    for (i, row) in rho.iter().enumerate() {
        if (row[i] - 1.0).abs() > 1e-12 {
            return invalid("sbm_params.json: girr.delta_rho diagonal must be 1");
        }
    }

    let eq = as_obj(require(root, "equity", "root")?, "equity")?;
    let mut equity_buckets = BTreeMap::new();
    for (b, p) in as_obj(require(eq, "buckets", "equity")?, "equity.buckets")? {
        let po = as_obj(p, &format!("equity bucket {b}"))?;
        let ctx = format!("equity bucket {b}");
        equity_buckets.insert(
            b.clone(),
            EquityBucketParams {
                delta_rw: req_f64(po, "delta_rw", &ctx)?,
                vega_rw: req_f64(po, "vega_rw", &ctx)?,
                rho: req_f64(po, "rho", &ctx)?,
            },
        );
    }

    let fx = as_obj(require(root, "fx", "root")?, "fx")?;
    let scen = as_obj(require(root, "scenarios", "root")?, "scenarios")?;
    let drc = as_obj(require(root, "drc", "root")?, "drc")?;
    let rrao = as_obj(require(root, "rrao", "root")?, "rrao")?;
    let ima = as_obj(require(root, "ima", "root")?, "ima")?;
    let bt = as_obj(require(ima, "backtest_multiplier", "ima")?, "backtest")?;
    let plat = as_obj(require(ima, "plat", "ima")?, "plat")?;

    let mut drc_rw_by_rating = BTreeMap::new();
    for (k, v) in as_obj(require(drc, "rw_by_rating", "drc")?, "drc.rw_by_rating")? {
        drc_rw_by_rating.insert(k.clone(), as_f64(v, "drc.rw_by_rating")?);
    }
    let mut rrao_rates = BTreeMap::new();
    for (k, v) in rrao {
        if !k.starts_with('_') {
            rrao_rates.insert(k.clone(), as_f64(v, "rrao")?);
        }
    }
    let lh_ladder: Vec<i64> = require(ima, "lh_ladder", "ima")?
        .as_array()
        .ok_or_else(|| FrtbError::Invalid("sbm_params.json: ima.lh_ladder must be a list".into()))?
        .iter()
        .map(|v| as_i64(v, "ima.lh_ladder"))
        .collect::<Result<_>>()?;
    let mut category_lh = BTreeMap::new();
    for (k, v) in as_obj(require(ima, "category_lh", "ima")?, "ima.category_lh")? {
        category_lh.insert(k.clone(), as_i64(v, "ima.category_lh")?);
    }
    let mut backtest_amber_multipliers = BTreeMap::new();
    for (k, v) in as_obj(require(bt, "amber", "backtest")?, "backtest.amber")? {
        let exc: i64 = k.parse().map_err(|_| {
            FrtbError::Invalid(format!("sbm_params.json: bad amber exception count '{k}'"))
        })?;
        backtest_amber_multipliers.insert(exc, as_f64(v, "backtest.amber")?);
    }

    Ok(SbmParams {
        girr_tenors: tenors,
        girr_delta_rw: delta_rw,
        girr_rho: rho,
        girr_vega_rw: req_f64(girr, "vega_rw", "girr")?,
        girr_curvature_rw: req_f64(girr, "curvature_rw", "girr")?,
        girr_gamma: req_f64(girr, "gamma", "girr")?,
        equity_buckets,
        equity_gamma: req_f64(eq, "gamma", "equity")?,
        fx_delta_rw: req_f64(fx, "delta_rw", "fx")?,
        fx_rho: req_f64(fx, "rho", "fx")?,
        fx_gamma: req_f64(fx, "gamma", "fx")?,
        scenario_high: req_f64(scen, "high", "scenarios")?,
        scenario_low: req_f64(scen, "low", "scenarios")?,
        drc_rw_by_rating,
        rrao_rates,
        ima_alpha: req_f64(ima, "alpha", "ima")?,
        ima_rho: req_f64(ima, "rho", "ima")?,
        lh_ladder,
        category_lh,
        backtest_amber_multipliers,
        backtest_base_multiplier: req_f64(bt, "base", "backtest")?,
        backtest_red_multiplier: req_f64(bt, "red", "backtest")?,
        plat_spearman_green: req_f64(plat, "spearman_green", "plat")?,
        plat_spearman_amber: req_f64(plat, "spearman_amber", "plat")?,
        plat_ks_green: req_f64(plat, "ks_green", "plat")?,
        plat_ks_amber: req_f64(plat, "ks_amber", "plat")?,
        plat_k_surcharge: req_f64(plat, "k_surcharge", "plat")?,
    })
}
