# Independent Model Validation Report

> Educational FRTB implementation — Basel-2019-flavored pinned parameter set.
> NOT a compliant capital engine; for teaching and testing only.

## 1. Scope & Overview

Desks in scope: desk1, desk2. Framework: SBM + DRC + RRAO (SA) and ES/IMCC + PLAT + backtesting + SES (IMA sketch).

## 2. Pricing Benchmark

| metric | value | threshold | result |
|---|---|---|---|
| max abs diff BS vs binomial(501) | 5.163e-03 | 0.05 | PASS |

## 3. Sensitivity Verification

Analytic BS delta vs central finite difference: max abs diff 3.423e-08 (threshold 1e-06) — PASS.

## 4. Capital Stability

| scenario | SBM capital | change vs base |
|---|---|---|
| base | 5,310,401.79 | — |
| GIRR delta RW x1.1 | 5,378,306.11 | 67,904.33 |
| GIRR delta RW x0.9 | 5,242,497.46 | -67,904.33 |

## 5. VaR Backtesting

| desk | exceptions | zone | multiplier |
|---|---|---|---|
| desk1 | 2 | green | 1.50 |
| desk2 | 5 | amber | 1.70 |

## 6. P&L Attribution (PLAT)

| desk | spearman | KS | zone | surcharge |
|---|---|---|---|---|
| desk1 | 0.9949 | 0.0346 | green | 0.00 |
| desk2 | 0.8434 | 0.0731 | amber | 2,031,490.61 |

## 7. Data Quality

| desk | zero-change days | gaps |
|---|---|---|
| desk1 | 0 | 0 |
| desk2 | 0 | 0 |

## 8. NMRF / SES

| desk | SES |
|---|---|
| desk1 | 35,000.00 |
| desk2 | 120,000.00 |

## 9. Findings

- **Medium** [BT-02] (desk2): VaR backtest in AMBER zone
- **Medium** [PLAT-02] (desk2): PLAT in AMBER zone

## 10. Overall Verdict

- desk1: **approve**
- desk2: **approve-with-conditions**
