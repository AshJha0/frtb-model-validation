//! Error type for the `frtb` crate.
//!
//! Mirrors the Python reference, where every invalid input raises
//! `ValueError` with a descriptive message: here the same conditions return
//! `Err(FrtbError::Invalid(msg))`. Nothing in the library panics on bad
//! input.

use std::fmt;

/// Crate-wide error type (thiserror-style manual enum).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FrtbError {
    /// Invalid input, missing parameter or inconsistent data
    /// (the Python reference raises `ValueError` for these).
    Invalid(String),
    /// I/O or parse failure while loading a bundled data file.
    Io(String),
}

impl fmt::Display for FrtbError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            FrtbError::Invalid(msg) => write!(f, "invalid input: {msg}"),
            FrtbError::Io(msg) => write!(f, "i/o error: {msg}"),
        }
    }
}

impl std::error::Error for FrtbError {}

/// Crate-wide result alias.
pub type Result<T> = std::result::Result<T, FrtbError>;

/// Shorthand for building an `Err(FrtbError::Invalid(..))`.
pub(crate) fn invalid<T>(msg: impl Into<String>) -> Result<T> {
    Err(FrtbError::Invalid(msg.into()))
}
