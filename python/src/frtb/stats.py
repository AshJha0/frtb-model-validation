"""Native statistics used by PLAT: Spearman rank correlation and the
two-sample Kolmogorov-Smirnov statistic.

Implemented from first principles (no scipy at runtime); the test suite
cross-checks both against scipy.
"""
from __future__ import annotations

import math
from typing import List, Sequence


def _validate_pair(x: Sequence[float], y: Sequence[float], min_n: int) -> None:
    if len(x) != len(y):
        raise ValueError(f"series must have equal length ({len(x)} vs {len(y)})")
    if len(x) < min_n:
        raise ValueError(f"series must have at least {min_n} observations, got {len(x)}")
    for v in list(x) + list(y):
        if not math.isfinite(v):
            raise ValueError("series must contain only finite values")


def average_ranks(x: Sequence[float]) -> List[float]:
    """Ranks 1..n with ties assigned the average rank of the tied block."""
    n = len(x)
    order = sorted(range(n), key=lambda i: x[i])
    ranks = [0.0] * n
    i = 0
    while i < n:
        j = i
        while j + 1 < n and x[order[j + 1]] == x[order[i]]:
            j += 1
        avg = (i + j) / 2.0 + 1.0  # average of ranks i+1 .. j+1
        for k in range(i, j + 1):
            ranks[order[k]] = avg
        i = j + 1
    return ranks


def pearson(x: Sequence[float], y: Sequence[float]) -> float:
    """Pearson correlation; raises ValueError if either series is constant."""
    _validate_pair(x, y, 2)
    n = len(x)
    mx = sum(x) / n
    my = sum(y) / n
    sxx = sum((a - mx) ** 2 for a in x)
    syy = sum((b - my) ** 2 for b in y)
    if sxx == 0.0 or syy == 0.0:
        raise ValueError("pearson: correlation undefined for a constant series")
    sxy = sum((a - mx) * (b - my) for a, b in zip(x, y))
    return sxy / math.sqrt(sxx * syy)


def spearman(x: Sequence[float], y: Sequence[float]) -> float:
    """Spearman rank correlation: Pearson correlation of average ranks.

    Raises ValueError when either series is constant (correlation undefined —
    PLAT maps this case to the Red zone, see ``frtb.plat``).
    """
    _validate_pair(x, y, 3)
    return pearson(average_ranks(x), average_ranks(y))


def ks_statistic(x: Sequence[float], y: Sequence[float]) -> float:
    """Two-sample Kolmogorov-Smirnov statistic sup_t |F_x(t) - F_y(t)|.

    Computed exactly over the pooled sample with a two-pointer sweep
    (handles ties identically to scipy.stats.ks_2samp).
    """
    if len(x) == 0 or len(y) == 0:
        raise ValueError("ks_statistic: series must be non-empty")
    for v in list(x) + list(y):
        if not math.isfinite(v):
            raise ValueError("ks_statistic: series must contain only finite values")
    xs = sorted(x)
    ys = sorted(y)
    n, m = len(xs), len(ys)
    i = j = 0
    d = 0.0
    while i < n and j < m:
        v = xs[i] if xs[i] <= ys[j] else ys[j]
        while i < n and xs[i] <= v:
            i += 1
        while j < m and ys[j] <= v:
            j += 1
        d = max(d, abs(i / n - j / m))
    # after one sample is exhausted the ECDF gap can only shrink toward |1-1|=0
    d = max(d, abs(1.0 - j / m) if i == n else abs(i / n - 1.0))
    return d
