#include "frtb/stats.hpp"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <numeric>
#include <stdexcept>

namespace frtb {

namespace {

void validate_pair(const std::vector<double>& x, const std::vector<double>& y, std::size_t min_n) {
    if (x.size() != y.size())
        throw std::invalid_argument("series must have equal length");
    if (x.size() < min_n)
        throw std::invalid_argument("series must have at least " + std::to_string(min_n) +
                                    " observations");
    for (double v : x)
        if (!std::isfinite(v))
            throw std::invalid_argument("series must contain only finite values");
    for (double v : y)
        if (!std::isfinite(v))
            throw std::invalid_argument("series must contain only finite values");
}

}  // namespace

std::vector<double> average_ranks(const std::vector<double>& x) {
    const std::size_t n = x.size();
    std::vector<std::size_t> order(n);
    std::iota(order.begin(), order.end(), 0);
    std::stable_sort(order.begin(), order.end(),
                     [&x](std::size_t a, std::size_t b) { return x[a] < x[b]; });
    std::vector<double> ranks(n, 0.0);
    std::size_t i = 0;
    while (i < n) {
        std::size_t j = i;
        while (j + 1 < n && x[order[j + 1]] == x[order[i]]) ++j;
        const double avg = (i + j) / 2.0 + 1.0;  // average of ranks i+1 .. j+1
        for (std::size_t k = i; k <= j; ++k) ranks[order[k]] = avg;
        i = j + 1;
    }
    return ranks;
}

double pearson(const std::vector<double>& x, const std::vector<double>& y) {
    validate_pair(x, y, 2);
    const std::size_t n = x.size();
    double sx = 0.0, sy = 0.0;
    for (double a : x) sx += a;
    for (double b : y) sy += b;
    const double mx = sx / n;
    const double my = sy / n;
    double sxx = 0.0, syy = 0.0;
    for (double a : x) {
        const double d = a - mx;
        sxx += d * d;
    }
    for (double b : y) {
        const double d = b - my;
        syy += d * d;
    }
    if (sxx == 0.0 || syy == 0.0)
        throw std::invalid_argument("pearson: correlation undefined for a constant series");
    double sxy = 0.0;
    for (std::size_t i = 0; i < n; ++i) sxy += (x[i] - mx) * (y[i] - my);
    return sxy / std::sqrt(sxx * syy);
}

double spearman(const std::vector<double>& x, const std::vector<double>& y) {
    validate_pair(x, y, 3);
    return pearson(average_ranks(x), average_ranks(y));
}

double ks_statistic(const std::vector<double>& x, const std::vector<double>& y) {
    if (x.empty() || y.empty())
        throw std::invalid_argument("ks_statistic: series must be non-empty");
    for (double v : x)
        if (!std::isfinite(v))
            throw std::invalid_argument("ks_statistic: series must contain only finite values");
    for (double v : y)
        if (!std::isfinite(v))
            throw std::invalid_argument("ks_statistic: series must contain only finite values");
    std::vector<double> xs = x;
    std::vector<double> ys = y;
    std::sort(xs.begin(), xs.end());
    std::sort(ys.begin(), ys.end());
    const std::size_t n = xs.size();
    const std::size_t m = ys.size();
    std::size_t i = 0, j = 0;
    double d = 0.0;
    while (i < n && j < m) {
        const double v = (xs[i] <= ys[j]) ? xs[i] : ys[j];
        while (i < n && xs[i] <= v) ++i;
        while (j < m && ys[j] <= v) ++j;
        d = std::max(d, std::abs(static_cast<double>(i) / n - static_cast<double>(j) / m));
    }
    // After one sample is exhausted the ECDF gap can only shrink toward |1-1|=0.
    d = std::max(d, (i == n) ? std::abs(1.0 - static_cast<double>(j) / m)
                             : std::abs(static_cast<double>(i) / n - 1.0));
    return d;
}

}  // namespace frtb
