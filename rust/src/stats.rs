//! Native statistics used by PLAT: Spearman rank correlation and the
//! two-sample Kolmogorov-Smirnov statistic.
//!
//! Implemented from first principles (no external stats dependency); the
//! Python reference cross-checks the same algorithms against scipy.

use crate::error::{invalid, Result};

fn validate_pair(x: &[f64], y: &[f64], min_n: usize) -> Result<()> {
    if x.len() != y.len() {
        return invalid(format!("series must have equal length ({} vs {})", x.len(), y.len()));
    }
    if x.len() < min_n {
        return invalid(format!(
            "series must have at least {min_n} observations, got {}",
            x.len()
        ));
    }
    for v in x.iter().chain(y.iter()) {
        if !v.is_finite() {
            return invalid("series must contain only finite values");
        }
    }
    Ok(())
}

/// Ranks 1..n with ties assigned the average rank of the tied block.
///
/// The input must be finite (callers validate); ties compare with exact
/// float equality, as in the reference.
pub fn average_ranks(x: &[f64]) -> Vec<f64> {
    let n = x.len();
    let mut order: Vec<usize> = (0..n).collect();
    // Stable sort by value, mirroring Python's sorted(key=...).
    order.sort_by(|&a, &b| x[a].partial_cmp(&x[b]).expect("finite values"));
    let mut ranks = vec![0.0; n];
    let mut i = 0;
    while i < n {
        let mut j = i;
        while j + 1 < n && x[order[j + 1]] == x[order[i]] {
            j += 1;
        }
        let avg = (i + j) as f64 / 2.0 + 1.0; // average of ranks i+1 .. j+1
        for k in i..=j {
            ranks[order[k]] = avg;
        }
        i = j + 1;
    }
    ranks
}

/// Pearson correlation; errors if either series is constant (undefined).
pub fn pearson(x: &[f64], y: &[f64]) -> Result<f64> {
    validate_pair(x, y, 2)?;
    let n = x.len() as f64;
    let mut sx = 0.0;
    for &a in x {
        sx += a;
    }
    let mut sy = 0.0;
    for &b in y {
        sy += b;
    }
    let mx = sx / n;
    let my = sy / n;
    let mut sxx = 0.0;
    for &a in x {
        sxx += (a - mx) * (a - mx);
    }
    let mut syy = 0.0;
    for &b in y {
        syy += (b - my) * (b - my);
    }
    if sxx == 0.0 || syy == 0.0 {
        return invalid("pearson: correlation undefined for a constant series");
    }
    let mut sxy = 0.0;
    for (&a, &b) in x.iter().zip(y.iter()) {
        sxy += (a - mx) * (b - my);
    }
    Ok(sxy / (sxx * syy).sqrt())
}

/// Spearman rank correlation: Pearson correlation of average ranks.
///
/// Errors when either series is constant (correlation undefined — PLAT maps
/// this case to the Red zone, see [`crate::plat`]).
pub fn spearman(x: &[f64], y: &[f64]) -> Result<f64> {
    validate_pair(x, y, 3)?;
    pearson(&average_ranks(x), &average_ranks(y))
}

/// Two-sample Kolmogorov-Smirnov statistic `sup_t |F_x(t) - F_y(t)|`.
///
/// Computed exactly over the pooled sample with a two-pointer sweep
/// (handles ties identically to `scipy.stats.ks_2samp`).
pub fn ks_statistic(x: &[f64], y: &[f64]) -> Result<f64> {
    if x.is_empty() || y.is_empty() {
        return invalid("ks_statistic: series must be non-empty");
    }
    for v in x.iter().chain(y.iter()) {
        if !v.is_finite() {
            return invalid("ks_statistic: series must contain only finite values");
        }
    }
    let mut xs = x.to_vec();
    let mut ys = y.to_vec();
    xs.sort_by(|a, b| a.partial_cmp(b).expect("finite values"));
    ys.sort_by(|a, b| a.partial_cmp(b).expect("finite values"));
    let (n, m) = (xs.len(), ys.len());
    let (mut i, mut j) = (0usize, 0usize);
    let mut d = 0.0f64;
    while i < n && j < m {
        let v = if xs[i] <= ys[j] { xs[i] } else { ys[j] };
        while i < n && xs[i] <= v {
            i += 1;
        }
        while j < m && ys[j] <= v {
            j += 1;
        }
        d = d.max((i as f64 / n as f64 - j as f64 / m as f64).abs());
    }
    // After one sample is exhausted the ECDF gap can only shrink toward 0.
    let tail = if i == n {
        (1.0 - j as f64 / m as f64).abs()
    } else {
        (i as f64 / n as f64 - 1.0).abs()
    };
    Ok(d.max(tail))
}
