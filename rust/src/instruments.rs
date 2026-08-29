//! Instrument definitions and portfolio loading.
//!
//! Instrument universe (deliberately small — this is an educational FRTB
//! kit):
//!
//! * [`Bond`]         — fixed-coupon annual-pay bullet bond (also the DRC
//!   vehicle).
//! * [`PayerSwap`]    — payer-swap proxy: long floating leg / short fixed
//!   leg, priced as `N*(1 - DF(T)) - c*N*sum DF(t_i)`. Its GIRR sensitivity
//!   is the "DV01 ladder" of the spec.
//! * [`EquityOption`] — European equity option under Black-Scholes.
//! * [`FxForward`]    — FX forward, valued in the domestic (quote) currency.

use std::collections::BTreeMap;
use std::path::Path;

use serde_json::Value;

use crate::error::{invalid, FrtbError, Result};

/// Residual-risk add-on flag: category (`"exotic"` or `"other"`) + notional
/// base for the add-on.
#[derive(Debug, Clone, PartialEq)]
pub struct RraoFlag {
    /// RRAO category: `"exotic"` (1.0%) or `"other"` (0.1%).
    pub category: String,
    /// Notional base the pinned rate applies to (>= 0).
    pub notional: f64,
}

impl RraoFlag {
    /// Build a flag with input validation.
    pub fn new(category: String, notional: f64) -> Result<RraoFlag> {
        if category != "exotic" && category != "other" {
            return invalid(format!(
                "RraoFlag: category must be 'exotic' or 'other', got '{category}'"
            ));
        }
        if !notional.is_finite() || notional < 0.0 {
            return invalid("RraoFlag: notional must be a non-negative finite number");
        }
        Ok(RraoFlag { category, notional })
    }
}

fn check_finite(name: &str, value: f64) -> Result<()> {
    if !value.is_finite() {
        return invalid(format!("{name} must be finite, got {value}"));
    }
    Ok(())
}

/// Annual payment times `T, T-1, ...` (> 0), ascending — shared by bond
/// coupons and the swap's fixed leg.
fn annual_times(maturity: f64) -> Vec<f64> {
    let mut times = Vec::new();
    let mut t = maturity;
    while t > 1e-9 {
        times.push(t);
        t -= 1.0;
    }
    times.sort_by(|a, b| a.partial_cmp(b).expect("finite times"));
    times
}

/// Fixed-coupon annual-pay bullet bond.
///
/// `notional` may be negative (short position, used by DRC netting).
/// Coupons are paid at `T, T-1, ...` (annual, stub-free by construction of
/// the bundled data).
#[derive(Debug, Clone, PartialEq)]
pub struct Bond {
    /// Instrument identifier.
    pub inst_id: String,
    /// Signed notional (non-zero; negative = short).
    pub notional: f64,
    /// Annual coupon rate (decimal).
    pub coupon: f64,
    /// Maturity in years (> 0).
    pub maturity: f64,
    /// Discounting currency.
    pub currency: String,
    /// Issuer (DRC netting key).
    pub issuer: String,
    /// Rating label for the pinned DRC risk-weight table.
    pub rating: String,
    /// Loss-given-default in [0, 1] (pinned 0.75 for bonds).
    pub lgd: f64,
    /// Optional residual-risk add-on flag.
    pub rrao: Option<RraoFlag>,
}

impl Bond {
    /// Validate the field invariants (called by the portfolio parser).
    pub fn validate(&self) -> Result<()> {
        check_finite("Bond.notional", self.notional)?;
        check_finite("Bond.coupon", self.coupon)?;
        if self.notional == 0.0 {
            return invalid("Bond: notional must be non-zero");
        }
        if self.maturity <= 0.0 || !self.maturity.is_finite() {
            return invalid(format!("Bond: maturity must be positive, got {}", self.maturity));
        }
        if !(0.0..=1.0).contains(&self.lgd) {
            return invalid(format!("Bond: LGD must be in [0,1], got {}", self.lgd));
        }
        Ok(())
    }

    /// Annual coupon payment times `T, T-1, ...` (> 0), ascending.
    pub fn coupon_times(&self) -> Vec<f64> {
        annual_times(self.maturity)
    }
}

/// Payer interest-rate swap proxy (pay fixed, receive float).
///
/// Value = `N*(1 - DF(T)) - fixed_rate*N*sum_i DF(t_i)` with annual fixed
/// payments.
#[derive(Debug, Clone, PartialEq)]
pub struct PayerSwap {
    /// Instrument identifier.
    pub inst_id: String,
    /// Signed notional (non-zero).
    pub notional: f64,
    /// Fixed-leg rate (decimal).
    pub fixed_rate: f64,
    /// Maturity in years (>= 1).
    pub maturity: f64,
    /// Discounting currency.
    pub currency: String,
    /// Optional residual-risk add-on flag.
    pub rrao: Option<RraoFlag>,
}

impl PayerSwap {
    /// Validate the field invariants.
    pub fn validate(&self) -> Result<()> {
        check_finite("PayerSwap.notional", self.notional)?;
        check_finite("PayerSwap.fixed_rate", self.fixed_rate)?;
        if self.notional == 0.0 {
            return invalid("PayerSwap: notional must be non-zero");
        }
        if self.maturity < 1.0 || !self.maturity.is_finite() {
            return invalid(format!("PayerSwap: maturity must be >= 1y, got {}", self.maturity));
        }
        Ok(())
    }

    /// Annual fixed-leg payment times, ascending.
    pub fn fixed_times(&self) -> Vec<f64> {
        annual_times(self.maturity)
    }
}

/// European equity option; value = `position * contracts * BS(...)`.
#[derive(Debug, Clone, PartialEq)]
pub struct EquityOption {
    /// Instrument identifier.
    pub inst_id: String,
    /// Underlying equity name (key into the market's equity quotes).
    pub underlier: String,
    /// `"call"` or `"put"`.
    pub option_type: String,
    /// +1 long, -1 short.
    pub position: i32,
    /// Number of contracts (> 0).
    pub contracts: f64,
    /// Strike (> 0).
    pub strike: f64,
    /// Maturity in years (>= 0).
    pub maturity: f64,
    /// Currency of the discounting curve.
    pub currency: String,
    /// Optional residual-risk add-on flag.
    pub rrao: Option<RraoFlag>,
}

impl EquityOption {
    /// Validate the field invariants.
    pub fn validate(&self) -> Result<()> {
        if self.option_type != "call" && self.option_type != "put" {
            return invalid(format!(
                "EquityOption: option_type must be call/put, got '{}'",
                self.option_type
            ));
        }
        if self.position != 1 && self.position != -1 {
            return invalid(format!(
                "EquityOption: position must be +1 or -1, got {}",
                self.position
            ));
        }
        if self.contracts <= 0.0 || !self.contracts.is_finite() {
            return invalid("EquityOption: contracts must be positive");
        }
        if self.strike <= 0.0 || !self.strike.is_finite() {
            return invalid("EquityOption: strike must be positive");
        }
        if self.maturity < 0.0 || !self.maturity.is_finite() {
            return invalid("EquityOption: maturity must be >= 0");
        }
        Ok(())
    }
}

/// FX forward on `pair` = FORDOM (e.g. EURUSD): long `N` foreign at strike
/// `K`. Value in domestic ccy = `N * (S * DF_for(T) - K * DF_dom(T))`.
#[derive(Debug, Clone, PartialEq)]
pub struct FxForward {
    /// Instrument identifier.
    pub inst_id: String,
    /// Currency pair, 6 characters FORDOM.
    pub pair: String,
    /// Signed notional in foreign currency (non-zero).
    pub notional: f64,
    /// Forward strike (> 0).
    pub strike: f64,
    /// Maturity in years (> 0).
    pub maturity: f64,
    /// Optional residual-risk add-on flag.
    pub rrao: Option<RraoFlag>,
}

impl FxForward {
    /// Validate the field invariants.
    pub fn validate(&self) -> Result<()> {
        check_finite("FxForward.notional", self.notional)?;
        if self.notional == 0.0 {
            return invalid("FxForward: notional must be non-zero");
        }
        if self.strike <= 0.0 || !self.strike.is_finite() {
            return invalid("FxForward: strike must be positive");
        }
        if self.maturity <= 0.0 || !self.maturity.is_finite() {
            return invalid("FxForward: maturity must be positive");
        }
        if self.pair.len() != 6 {
            return invalid(format!("FxForward: pair must be 6 chars FORDOM, got '{}'", self.pair));
        }
        Ok(())
    }

    /// Foreign (base) currency of the pair.
    pub fn foreign(&self) -> &str {
        &self.pair[..3]
    }

    /// Domestic (quote) currency of the pair.
    pub fn domestic(&self) -> &str {
        &self.pair[3..]
    }
}

/// Any supported instrument.
#[derive(Debug, Clone, PartialEq)]
pub enum Instrument {
    /// Fixed-coupon bullet bond.
    Bond(Bond),
    /// Payer-swap proxy.
    PayerSwap(PayerSwap),
    /// European equity option.
    EquityOption(EquityOption),
    /// FX forward.
    FxForward(FxForward),
}

impl Instrument {
    /// The instrument's residual-risk add-on flag, if any.
    pub fn rrao(&self) -> Option<&RraoFlag> {
        match self {
            Instrument::Bond(b) => b.rrao.as_ref(),
            Instrument::PayerSwap(s) => s.rrao.as_ref(),
            Instrument::EquityOption(o) => o.rrao.as_ref(),
            Instrument::FxForward(f) => f.rrao.as_ref(),
        }
    }

    /// Equity underlier name (options only).
    pub fn underlier(&self) -> Option<&str> {
        match self {
            Instrument::EquityOption(o) => Some(&o.underlier),
            _ => None,
        }
    }

    /// FX pair (forwards only).
    pub fn fx_pair(&self) -> Option<&str> {
        match self {
            Instrument::FxForward(f) => Some(&f.pair),
            _ => None,
        }
    }
}

/// A trading desk: name + instrument list (may be empty — capital is then
/// zero).
#[derive(Debug, Clone, PartialEq)]
pub struct Desk {
    /// Machine name (portfolio key, e.g. "desk1").
    pub name: String,
    /// Human-readable display name.
    pub display: String,
    /// Instruments booked on the desk, in portfolio order.
    pub instruments: Vec<Instrument>,
}

// ---------------------------------------------------------------- parsing

fn jstr(d: &Value, key: &str, ctx: &str) -> Result<String> {
    match d.get(key) {
        Some(Value::String(s)) => Ok(s.clone()),
        _ => invalid(format!("{ctx}: missing or non-string field '{key}'")),
    }
}

fn jnum(d: &Value, key: &str, ctx: &str) -> Result<f64> {
    match d.get(key).and_then(Value::as_f64) {
        Some(x) => Ok(x),
        None => invalid(format!("{ctx}: missing or non-numeric field '{key}'")),
    }
}

fn parse_rrao(d: &Value) -> Result<Option<RraoFlag>> {
    match d.get("rrao") {
        None | Some(Value::Null) => Ok(None),
        Some(r) => Ok(Some(RraoFlag::new(
            jstr(r, "category", "rrao")?,
            jnum(r, "notional", "rrao")?,
        )?)),
    }
}

/// Parse one instrument JSON object (`portfolio.json` schema); errors on any
/// unknown type or invalid field.
pub fn instrument_from_json(d: &Value) -> Result<Instrument> {
    let typ = d.get("type").and_then(Value::as_str);
    match typ {
        Some("bond") => {
            let b = Bond {
                inst_id: jstr(d, "id", "bond")?,
                notional: jnum(d, "notional", "bond")?,
                coupon: jnum(d, "coupon", "bond")?,
                maturity: jnum(d, "maturity", "bond")?,
                currency: jstr(d, "currency", "bond")?,
                issuer: jstr(d, "issuer", "bond")?,
                rating: jstr(d, "rating", "bond")?,
                lgd: match d.get("lgd") {
                    Some(v) => v
                        .as_f64()
                        .ok_or_else(|| FrtbError::Invalid("bond: non-numeric 'lgd'".into()))?,
                    None => 0.75,
                },
                rrao: parse_rrao(d)?,
            };
            b.validate()?;
            Ok(Instrument::Bond(b))
        }
        Some("payer_swap") => {
            let s = PayerSwap {
                inst_id: jstr(d, "id", "payer_swap")?,
                notional: jnum(d, "notional", "payer_swap")?,
                fixed_rate: jnum(d, "fixed_rate", "payer_swap")?,
                maturity: jnum(d, "maturity", "payer_swap")?,
                currency: jstr(d, "currency", "payer_swap")?,
                rrao: parse_rrao(d)?,
            };
            s.validate()?;
            Ok(Instrument::PayerSwap(s))
        }
        Some("equity_option") => {
            let o = EquityOption {
                inst_id: jstr(d, "id", "equity_option")?,
                underlier: jstr(d, "underlier", "equity_option")?,
                option_type: jstr(d, "option_type", "equity_option")?,
                position: jnum(d, "position", "equity_option")? as i32,
                contracts: jnum(d, "contracts", "equity_option")?,
                strike: jnum(d, "strike", "equity_option")?,
                maturity: jnum(d, "maturity", "equity_option")?,
                currency: jstr(d, "currency", "equity_option")?,
                rrao: parse_rrao(d)?,
            };
            o.validate()?;
            Ok(Instrument::EquityOption(o))
        }
        Some("fx_forward") => {
            let f = FxForward {
                inst_id: jstr(d, "id", "fx_forward")?,
                pair: jstr(d, "pair", "fx_forward")?,
                notional: jnum(d, "notional", "fx_forward")?,
                strike: jnum(d, "strike", "fx_forward")?,
                maturity: jnum(d, "maturity", "fx_forward")?,
                rrao: parse_rrao(d)?,
            };
            f.validate()?;
            Ok(Instrument::FxForward(f))
        }
        other => invalid(format!(
            "instrument_from_json: unknown instrument type '{}'",
            other.unwrap_or("<missing>")
        )),
    }
}

/// Load `portfolio.json` into `{desk_name -> Desk}`; errors on schema
/// violations (missing `desks` list, duplicate desk names, bad instruments).
pub fn load_portfolio(path: &Path) -> Result<BTreeMap<String, Desk>> {
    let text = std::fs::read_to_string(path)
        .map_err(|e| FrtbError::Io(format!("cannot read {}: {e}", path.display())))?;
    let raw: Value = serde_json::from_str(&text)
        .map_err(|e| FrtbError::Io(format!("{}: bad JSON: {e}", path.display())))?;
    let desks_json = match raw.get("desks") {
        Some(Value::Array(a)) => a,
        _ => return invalid("load_portfolio: portfolio.json must contain a 'desks' list"),
    };
    let mut desks = BTreeMap::new();
    for d in desks_json {
        let name = jstr(d, "name", "desk")?;
        if desks.contains_key(&name) {
            return invalid(format!("load_portfolio: duplicate desk name '{name}'"));
        }
        let display = match d.get("display") {
            Some(Value::String(s)) => s.clone(),
            _ => name.clone(),
        };
        let mut instruments = Vec::new();
        if let Some(Value::Array(list)) = d.get("instruments") {
            for inst in list {
                instruments.push(instrument_from_json(inst)?);
            }
        }
        desks.insert(name.clone(), Desk { name, display, instruments });
    }
    Ok(desks)
}
