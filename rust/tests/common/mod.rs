//! Shared test helpers: bundled data dir and a lazily computed engine run.

use std::path::PathBuf;
use std::sync::OnceLock;

use frtb::Results;

/// Path to the bundled data directory (`../data` relative to `rust/`).
pub fn data_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("..").join("data")
}

/// One full deterministic engine run over the bundled data set (computed
/// once per test binary).
pub fn results() -> &'static Results {
    static RESULTS: OnceLock<Results> = OnceLock::new();
    RESULTS.get_or_init(|| frtb::compute_results(&data_dir()).expect("engine run"))
}

/// Assert |a - b| <= tol with a helpful message.
#[macro_export]
macro_rules! assert_close {
    ($a:expr, $b:expr, $tol:expr) => {{
        let (a, b, tol) = ($a, $b, $tol);
        assert!(
            (a - b).abs() <= tol,
            "assert_close failed: {a:?} vs {b:?} (tol {tol:e}, diff {:e})",
            (a - b).abs()
        );
    }};
    ($a:expr, $b:expr, $tol:expr, $msg:expr) => {{
        let (a, b, tol) = ($a, $b, $tol);
        assert!(
            (a - b).abs() <= tol,
            "{}: {a:?} vs {b:?} (tol {tol:e}, diff {:e})",
            $msg,
            (a - b).abs()
        );
    }};
}
