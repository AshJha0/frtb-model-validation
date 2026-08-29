//! Market data containers: zero curves, equity quotes, FX spots.
//!
//! All bump operations return *new* objects (the engine treats markets as
//! immutable snapshots), so bump-and-revalue sensitivities cannot leak
//! state between bumps.

use std::collections::BTreeMap;
use std::path::Path;

use crate::error::{invalid, FrtbError, Result};

/// Continuously-compounded zero curve with linear interpolation in tenor.
///
/// Rates are interpolated linearly between nodes and extrapolated flat
/// beyond the first/last node. Discount factor: `DF(t) = exp(-z(t)*t)`,
/// `DF(0) = 1`.
#[derive(Debug, Clone, PartialEq)]
pub struct Curve {
    tenors: Vec<f64>,
    rates: Vec<f64>,
}

impl Curve {
    /// Build a curve; tenors must be positive, finite and strictly
    /// increasing, rates finite.
    pub fn new(tenors: Vec<f64>, rates: Vec<f64>) -> Result<Curve> {
        if tenors.is_empty() || tenors.len() != rates.len() {
            return invalid("Curve: tenors and rates must be non-empty and equal length");
        }
        for i in 1..tenors.len() {
            if tenors[i] <= tenors[i - 1] {
                return invalid("Curve: tenors must be strictly increasing");
            }
        }
        for (&t, &r) in tenors.iter().zip(rates.iter()) {
            if !t.is_finite() || !r.is_finite() || t <= 0.0 {
                return invalid("Curve: tenors must be positive finite, rates finite");
            }
        }
        Ok(Curve { tenors, rates })
    }

    /// Curve node tenors (strictly increasing).
    pub fn tenors(&self) -> &[f64] {
        &self.tenors
    }

    /// Zero rates aligned with [`Curve::tenors`].
    pub fn rates(&self) -> &[f64] {
        &self.rates
    }

    /// Interpolated zero rate at time `t` (flat extrapolation).
    pub fn rate(&self, t: f64) -> Result<f64> {
        if !t.is_finite() || t < 0.0 {
            return invalid(format!("Curve.rate: invalid time {t}"));
        }
        let (ts, rs) = (&self.tenors, &self.rates);
        if t <= ts[0] {
            return Ok(rs[0]);
        }
        if t >= ts[ts.len() - 1] {
            return Ok(rs[rs.len() - 1]);
        }
        for i in 1..ts.len() {
            if t <= ts[i] {
                let w = (t - ts[i - 1]) / (ts[i] - ts[i - 1]);
                return Ok(rs[i - 1] * (1.0 - w) + rs[i] * w);
            }
        }
        Ok(rs[rs.len() - 1]) // unreachable: t < last tenor was handled above
    }

    /// Discount factor `exp(-z(t)*t)`; `DF(0) = 1`.
    pub fn df(&self, t: f64) -> Result<f64> {
        if t == 0.0 {
            return Ok(1.0);
        }
        Ok((-self.rate(t)? * t).exp())
    }

    /// A copy with the zero rate at one node shifted by `size` (absolute).
    ///
    /// Errors when `tenor` is not an exact curve node (the pinned GIRR bump
    /// convention bumps nodes, never interpolated points).
    pub fn bumped_node(&self, tenor: f64, size: f64) -> Result<Curve> {
        if !self.tenors.contains(&tenor) {
            return invalid(format!("Curve.bumped_node: tenor {tenor} is not a curve node"));
        }
        let rates = self
            .tenors
            .iter()
            .zip(self.rates.iter())
            .map(|(&tt, &r)| if tt == tenor { r + size } else { r })
            .collect();
        Curve::new(self.tenors.clone(), rates)
    }

    /// A copy with every node shifted by `size` (absolute).
    pub fn bumped_parallel(&self, size: f64) -> Result<Curve> {
        let rates = self.rates.iter().map(|&r| r + size).collect();
        Curve::new(self.tenors.clone(), rates)
    }
}

/// Equity market data: spot, flat lognormal vol, dividend yield, SBM bucket.
#[derive(Debug, Clone, PartialEq)]
pub struct EquityQuote {
    /// Spot price (positive, finite).
    pub spot: f64,
    /// Flat lognormal volatility (>= 0).
    pub vol: f64,
    /// Continuous dividend yield.
    pub div_yield: f64,
    /// SBM equity bucket label (e.g. "1", "11").
    pub bucket: String,
}

impl EquityQuote {
    /// Build a quote with input validation.
    pub fn new(spot: f64, vol: f64, div_yield: f64, bucket: String) -> Result<EquityQuote> {
        if !spot.is_finite() || spot <= 0.0 {
            return invalid(format!("EquityQuote: spot must be positive finite, got {spot}"));
        }
        if !vol.is_finite() || vol < 0.0 {
            return invalid(format!("EquityQuote: vol must be >= 0, got {vol}"));
        }
        if !div_yield.is_finite() {
            return invalid("EquityQuote: div_yield must be finite");
        }
        Ok(EquityQuote { spot, vol, div_yield, bucket })
    }
}

/// Immutable market snapshot: curves per currency, equities per name, FX
/// spots per pair. All `bump_*` helpers return new snapshots.
#[derive(Debug, Clone, PartialEq)]
pub struct Market {
    /// Zero curves keyed by currency code.
    pub curves: BTreeMap<String, Curve>,
    /// Equity quotes keyed by underlier name.
    pub equities: BTreeMap<String, EquityQuote>,
    /// FX spots keyed by pair (FORDOM, e.g. "EURUSD").
    pub fx: BTreeMap<String, f64>,
}

impl Market {
    /// Curve for `ccy` or an error naming the missing currency.
    pub fn curve(&self, ccy: &str) -> Result<&Curve> {
        self.curves
            .get(ccy)
            .ok_or_else(|| FrtbError::Invalid(format!("Market: no curve for currency '{ccy}'")))
    }

    /// Equity quote for `name` or an error.
    pub fn equity(&self, name: &str) -> Result<&EquityQuote> {
        self.equities
            .get(name)
            .ok_or_else(|| FrtbError::Invalid(format!("Market: no equity quote for '{name}'")))
    }

    /// FX spot for `pair` or an error.
    pub fn fx_spot(&self, pair: &str) -> Result<f64> {
        self.fx
            .get(pair)
            .copied()
            .ok_or_else(|| FrtbError::Invalid(format!("Market: no FX spot for pair '{pair}'")))
    }

    /// New snapshot with the curve for `ccy` replaced.
    pub fn with_curve(&self, ccy: &str, curve: Curve) -> Market {
        let mut curves = self.curves.clone();
        curves.insert(ccy.to_string(), curve);
        Market { curves, equities: self.equities.clone(), fx: self.fx.clone() }
    }

    /// Snapshot with one zero-curve node bumped by `size` (absolute).
    pub fn bump_curve_node(&self, ccy: &str, tenor: f64, size: f64) -> Result<Market> {
        Ok(self.with_curve(ccy, self.curve(ccy)?.bumped_node(tenor, size)?))
    }

    /// Snapshot with the whole curve of `ccy` bumped by `size` (absolute).
    pub fn bump_curve_parallel(&self, ccy: &str, size: f64) -> Result<Market> {
        Ok(self.with_curve(ccy, self.curve(ccy)?.bumped_parallel(size)?))
    }

    /// Snapshot with a relative equity-spot bump: `S -> S * (1 + rel)`.
    pub fn bump_equity_spot(&self, name: &str, rel: f64) -> Result<Market> {
        let q = self.equity(name)?;
        let bumped = EquityQuote::new(q.spot * (1.0 + rel), q.vol, q.div_yield, q.bucket.clone())?;
        let mut equities = self.equities.clone();
        equities.insert(name.to_string(), bumped);
        Ok(Market { curves: self.curves.clone(), equities, fx: self.fx.clone() })
    }

    /// Snapshot with an absolute vol bump: `sigma -> sigma + size`.
    pub fn bump_equity_vol(&self, name: &str, size: f64) -> Result<Market> {
        let q = self.equity(name)?;
        let bumped = EquityQuote::new(q.spot, q.vol + size, q.div_yield, q.bucket.clone())?;
        let mut equities = self.equities.clone();
        equities.insert(name.to_string(), bumped);
        Ok(Market { curves: self.curves.clone(), equities, fx: self.fx.clone() })
    }

    /// Snapshot with a relative FX-spot bump: `S -> S * (1 + rel)`.
    pub fn bump_fx(&self, pair: &str, rel: f64) -> Result<Market> {
        let s = self.fx_spot(pair)?;
        let mut fx = self.fx.clone();
        fx.insert(pair.to_string(), s * (1.0 + rel));
        Ok(Market { curves: self.curves.clone(), equities: self.equities.clone(), fx })
    }
}

// ---------------------------------------------------------------- loading

fn read_csv(path: &Path) -> Result<(Vec<String>, Vec<Vec<String>>)> {
    let text = std::fs::read_to_string(path)
        .map_err(|e| FrtbError::Io(format!("cannot read {}: {e}", path.display())))?;
    let mut lines = text.lines().filter(|l| !l.trim().is_empty());
    let header: Vec<String> = lines
        .next()
        .ok_or_else(|| FrtbError::Io(format!("{}: empty CSV", path.display())))?
        .split(',')
        .map(|s| s.trim().to_string())
        .collect();
    let rows = lines
        .map(|l| l.split(',').map(|s| s.trim().to_string()).collect())
        .collect();
    Ok((header, rows))
}

fn col(header: &[String], name: &str, path: &Path) -> Result<usize> {
    header
        .iter()
        .position(|h| h == name)
        .ok_or_else(|| FrtbError::Io(format!("{}: missing column '{name}'", path.display())))
}

fn parse_f64(cell: &str, ctx: &str) -> Result<f64> {
    cell.parse::<f64>()
        .map_err(|_| FrtbError::Invalid(format!("{ctx}: cannot parse number '{cell}'")))
}

/// Load a [`Market`] from `curves.csv` (`currency,tenor,zero_rate`) and
/// `spots.csv` (`kind,name,spot,vol,div_yield,eq_bucket`).
pub fn load_market(curves_csv: &Path, spots_csv: &Path) -> Result<Market> {
    let (header, rows) = read_csv(curves_csv)?;
    let (c_ccy, c_tenor, c_rate) = (
        col(&header, "currency", curves_csv)?,
        col(&header, "tenor", curves_csv)?,
        col(&header, "zero_rate", curves_csv)?,
    );
    let mut by_ccy: BTreeMap<String, Vec<(f64, f64)>> = BTreeMap::new();
    for row in &rows {
        by_ccy.entry(row[c_ccy].clone()).or_default().push((
            parse_f64(&row[c_tenor], "curves.csv tenor")?,
            parse_f64(&row[c_rate], "curves.csv zero_rate")?,
        ));
    }
    if by_ccy.is_empty() {
        return invalid(format!("load_market: no curves in {}", curves_csv.display()));
    }
    let mut curves = BTreeMap::new();
    for (ccy, mut pts) in by_ccy {
        pts.sort_by(|a, b| a.partial_cmp(b).expect("finite tenors"));
        let tenors = pts.iter().map(|p| p.0).collect();
        let rates = pts.iter().map(|p| p.1).collect();
        curves.insert(ccy, Curve::new(tenors, rates)?);
    }

    let (header, rows) = read_csv(spots_csv)?;
    let (c_kind, c_name, c_spot, c_vol, c_div, c_bkt) = (
        col(&header, "kind", spots_csv)?,
        col(&header, "name", spots_csv)?,
        col(&header, "spot", spots_csv)?,
        col(&header, "vol", spots_csv)?,
        col(&header, "div_yield", spots_csv)?,
        col(&header, "eq_bucket", spots_csv)?,
    );
    let mut equities = BTreeMap::new();
    let mut fx = BTreeMap::new();
    for row in &rows {
        match row[c_kind].as_str() {
            "equity" => {
                let q = EquityQuote::new(
                    parse_f64(&row[c_spot], "spots.csv spot")?,
                    parse_f64(&row[c_vol], "spots.csv vol")?,
                    parse_f64(&row[c_div], "spots.csv div_yield")?,
                    row[c_bkt].clone(),
                )?;
                equities.insert(row[c_name].clone(), q);
            }
            "fx" => {
                fx.insert(row[c_name].clone(), parse_f64(&row[c_spot], "spots.csv spot")?);
            }
            other => {
                return invalid(format!(
                    "load_market: unknown kind '{other}' in {}",
                    spots_csv.display()
                ));
            }
        }
    }
    Ok(Market { curves, equities, fx })
}
