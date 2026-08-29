# API_SPEC — P15 FRTB & Model Validation

> **EDUCATIONAL PARAMETER SET — NOT COMPLIANT BASEL TEXT.**
> Every risk weight, correlation, bucket set, threshold and formula
> simplification below is a *pinned, Basel-2019-flavored teaching value*
> (simplified buckets, no securitisations, subset of risk classes).
> Never use this engine or its parameters for real regulatory capital.

This file is the cross-language contract. All implementations (Python
reference in `python/src/frtb`, later C++/Rust/Java) must reproduce the
formulas below exactly and pass `data/golden/golden.json`.

---

## 1. Market data & pricers

* **Zero curves** (`data/curves.csv`: `currency,tenor,zero_rate`): continuously
  compounded, **linear interpolation in tenor, flat extrapolation**,
  `DF(t) = exp(-z(t)*t)`, `DF(0) = 1`. Currencies: USD, EUR.
* **Spots** (`data/spots.csv`: `kind,name,spot,vol,div_yield,eq_bucket`):
  equity spot/flat vol/dividend yield/SBM bucket; FX row holds the EURUSD spot.
* **Pricers** (pinned; all closed-form/lattice, deterministic):
  * Bond (annual-pay bullet): `PV = Σ c·N·DF(t_i) + N·DF(T)`, coupons at
    `T, T-1, …  (> 0)`.
  * Payer swap proxy: `V = N·(1 − DF(T)) − c·N·Σ_{i=1..T} DF(t_i)` (annual
    fixed leg; the float leg is worth `N·(1 − DF(T))`).
  * European equity option: Black–Scholes with dividend yield `q`, rate
    `r = z_USD(T)`. Edge cases: `T = 0` → intrinsic; `σ = 0` → discounted
    deterministic payoff `max(±(S·e^{−qT} − K·e^{−rT}), 0)`.
  * FX forward (pair FORDOM): `V_dom = N·(S·DF_for(T) − K·DF_dom(T))`.
  * Benchmark pricer: CRR binomial lattice, European, **501 steps pinned**
    (validation only).

## 2. Sensitivities — pinned bump-and-revalue

| risk factor | bump | sensitivity definition |
|---|---|---|
| GIRR delta (per curve node) | +1bp absolute (1e-4) on one node | `s = (V⁺ − V)/1e-4` (= dV/dr per unit rate) |
| Equity delta (per name) | +1% relative spot (S→1.01·S) | `s = (V⁺ − V)/0.01` (= S·dV/dS convention) |
| Equity vega (per name) | +1 vol pt absolute (σ→σ+0.01) | `raw = (V⁺ − V)/0.01`; **WS uses `s = raw·σ`** |
| FX delta (per pair) | +1% relative spot | `s = (V⁺ − V)/0.01` |
| Curvature shock | full risk weight (§3.4) | see CVR definition below |

Sensitivities with `|s| ≤ 1e-9` are dropped (all-zero currencies excluded from
GIRR buckets). Bumps are applied to immutable market snapshots; full
portfolio revaluation each bump (so desk-2 options contribute GIRR, etc.).

## 3. SBM — Sensitivities-Based Method

### 3.1 Weighted sensitivities and within-bucket aggregation

```
WS_k = RW_k · s_k
K_b  = sqrt( max(0,  Σ_k WS_k² + Σ_{k≠l} ρ_kl · WS_k · WS_l ) )
```
The `max(0,·)` is a guard against negative rounding of the quadratic form.

### 3.2 Across-bucket aggregation and the S_b fallback

```
Charge = sqrt( max(0,  Σ_b K_b² + Σ_{b≠c} γ_bc · S_b · S_c ) ),   S_b = Σ_k WS_k
```
If `Σ_b K_b² + Σ_{b≠c} γ_bc S_b S_c < 0`, recompute **once** with the fallback
`S_b = max( min(Σ_k WS_k, K_b), −K_b )` (then the outer `max(0,·)` still applies).

### 3.3 Correlation scenarios

Applied to **every** ρ and γ:

| scenario | scaling |
|---|---|
| high | `min(1.25·ρ, 1.0)` (capped at 1) |
| medium | `ρ` |
| low | `0.75·ρ` — **pinned simplification** of Basel's `max(2ρ−1, 0.75ρ)` |

Per scenario: `total(s) = Σ_{risk class} (delta + vega + curvature)`.
**SBM capital = max over the three scenario totals.**

### 3.4 Curvature (CVR, ψ)

Per curvature factor k (whole curve per currency for GIRR; spot per equity
name; spot per FX pair) with delta sensitivity `s_k` (GIRR: Σ over tenors):

```
CVR_k⁺ = −( V(shock up)   − V − RW_k^curv · s_k )
CVR_k⁻ = −( V(shock down) − V + RW_k^curv · s_k )
```
Shocks: GIRR = ±RW parallel absolute shift of all nodes of one currency;
equity/FX = ±RW relative spot shift.

Within bucket, per side (± independently):
```
K_b^± = sqrt( max(0, Σ_k max(CVR_k^±,0)² + Σ_{k≠l} ρ_kl CVR_k^± CVR_l^± ψ(CVR_k^±, CVR_l^±)) )
ψ(a,b) = 0 if a<0 and b<0, else 1
K_b = max(K_b⁺, K_b⁻);  S_b = Σ_k CVR_k on the winning side (tie → up side)
```
Across buckets: `Charge = sqrt(max(0, Σ K_b² + Σ_{b≠c} γ_bc² S_b S_c ψ(S_b,S_c)))`.

**Pinned ψ / correlation simplifications (documented deviations):**
curvature correlations are the *squares of the scenario-scaled delta
correlations* (`ρ_curv = ρ_scen²`, `γ_curv = γ_scen²`); ψ is applied at both
levels with the same both-negative rule; no S_b fallback for curvature;
side/tie selection is up-side-on-tie.

### 3.5 Pinned SBM parameter tables

Authoritative values live in **`data/sbm_params.json`** (single source of
truth, loaded at runtime; missing key ⇒ `ValueError`). Summary:

* **GIRR** — tenors `{0.25, 0.5, 1, 2, 3, 5, 10, 15, 20, 30}`; delta RW
  `1.7% / 1.7% / 1.6% / 1.3% / 1.2% / 1.1% / 1.1% / 1.1% / 1.1% / 1.1%`;
  tenor correlations `ρ_kl = max(exp(−0.03·|T_k−T_l| / min(T_k,T_l)), 0.40)`
  rounded to 6 dp and tabulated in the file; vega RW 100% (no IR-vol
  instruments in the bundled book ⇒ GIRR vega ≡ 0); curvature RW 1.7%
  (parallel absolute shift); cross-currency `γ = 0.5`.
* **Equity** — buckets `1–4` (large cap, delta RW 55/60/45/55%) + `11`
  ("indices", **educational RW 15%**); vega RW 78% everywhere; intra-bucket
  `ρ = 0.15`; cross-bucket `γ = 0.15`.
* **FX** — **single pinned bucket** (documented simplification), delta
  RW 15%, intra ρ 0.6, γ 0.6 (unused with one bucket); no FX vega modelled.

## 4. DRC-lite (Default Risk Charge)

```
JTD_i = LGD·notional + (MV − notional)        (signed; shorts: negative notional)
netJTD_issuer = Σ same-issuer JTD             (long/short netting)
HBR = ΣnetLong / (ΣnetLong + Σ|netShort|)     (= 1 if no net shorts or empty book)
DRC = max(0, Σ RW(rating_i)·netLong_i − HBR·Σ RW(rating_i)·|netShort_i|)
```
Rating RW table (pinned): AAA 0.5%, AA 2%, A 3%, BBB 6%, BB 15%, B 30%,
CCC 50%, NR 15%, D 100%. LGD pinned per instrument (bonds 75%).
Only bonds enter DRC in this kit (documented). Unknown rating or one issuer
with two ratings ⇒ `ValueError`. No maturity scaling (simplification).

## 5. RRAO

`RRAO = Σ_flagged rate(category) · rrao_notional`, rates pinned:
`exotic → 1.0%`, `other → 0.1%`. Unknown category ⇒ `ValueError`.

## 6. IMA sketch

### 6.1 ES 97.5 and the liquidity-horizon ladder

* Daily ES: losses `L = −PnL` sorted descending, `k = max(1, ceil((1−α)·n − 1e-9))`
  (ε guards binary-float ceil artefacts), `ES_daily = mean of k worst losses`,
  α = 0.975. For n = 260 ⇒ k = 7.
* **Base 10d ES** = `sqrt(10) · ES_daily` (pinned √t scaling).
* **LH ladder** (pinned `LH = (10, 20, 40, 60, 120)`, category horizons
  `ir→20, eq→20, fx→40, cr→60`):

```
ES_LH = sqrt( ES₁(P)² + Σ_{j≥2} ( ES₁(P_j) · sqrt((LH_j − LH_{j−1})/10) )² )
```
`P` = full desk P&L, `P_j` = Σ of category P&L with `LH_cat ≥ LH_j`, `ES₁` =
base-10d ES operator. Category columns must sum to the desk column
(validated to 1e-6, ⇒ `ValueError`). The ladder is monotone: `ES_LH ≥ ES₁`;
a single category with horizon LH collapses to `ES₁·sqrt(LH/10)`.

### 6.2 IMCC and capital

```
IMCC = ρ·ES_LH(full) + (1−ρ)·Σ_c ES_LH(category c),   ρ = 0.5 pinned
IMA core capital = multiplier · IMCC + SES
IMA capital      = core + PLAT surcharge
```
Documented simplification: the bundled portfolio is static ⇒
`avg60(IMCC) = IMCC` and `max(IMCC_{t−1}, m·avg60 IMCC) = m·IMCC` (m ≥ 1.5).

### 6.3 Backtesting (99% VaR, desk level, 260 days)

Exception: `PnL_t < −VaR_t` (strict). Zones and pinned multiplier map:

| exceptions | zone | multiplier |
|---|---|---|
| 0–4 | green | 1.50 |
| 5 | amber | 1.70 |
| 6 | amber | 1.75 |
| 7 | amber | 1.83 |
| 8 | amber | 1.88 |
| 9 | amber | 1.92 |
| ≥10 (incl. >12) | red | 2.00 (cap) |

### 6.4 PLAT

Metrics on hypothetical vs risk-theoretical P&L (both bundled, 260d):
**Spearman** = Pearson correlation of average ranks (ties → average rank);
**KS** = exact two-sample statistic `sup |F_hypo − F_rtpl|` over the pooled
sample. Both implemented natively in the package (`frtb/stats.py`); scipy is
used only in the tests as a cross-check.

| zone | rule (pinned thresholds) |
|---|---|
| green | `spearman ≥ 0.85` **and** `KS ≤ 0.09` |
| red | `spearman < 0.80` **or** `KS > 0.12` |
| amber | otherwise |

Constant P&L on either side ⇒ correlation undefined ⇒ **Red** with metrics
reported as null (documented conservative convention).
**Amber surcharge (pinned k = 0.5 interpolation):**
`surcharge = 0.5 · max(0, SA_desk − IMA_core_desk)`; 0 for green/red
(red-zone fallback-to-SA is a reporting matter, not a surcharge).

### 6.5 NMRF / SES

`SES = Σ stressed_loss` over `data/nmrf.json` factors of the scope —
**zero diversification**. `stressed_loss < 0` ⇒ `ValueError`.

## 7. Validation framework

Pinned checks: benchmark grid S=100, r=3%, q=1%, σ=20%,
K ∈ {70,85,100,115,130}, T ∈ {0.25,0.5,1,2}, call+put (40 prices);
binomial steps 501, pass `max|BS−binomial| ≤ 0.05`; delta FD check central
difference `h = 1e-4·S`, pass `≤ 1e-6`; stability = firm SBM capital under
GIRR delta RW ×1.1 / ×0.9; staleness threshold 15 zero-change days; any gap
(NaN) is a finding.

### 7.1 Findings rule table (pinned)

| rule | severity | fires when |
|---|---|---|
| BENCH-01 | High | benchmark max diff > 0.05 |
| SENS-01 | High | delta FD max diff > 1e-6 |
| BT-01 | High | backtest zone red |
| BT-02 | Medium | backtest zone amber |
| PLAT-01 | High | PLAT zone red |
| PLAT-02 | Medium | PLAT zone amber |
| STAB-01 | Medium | max(|ΔCap ×1.1|, |ΔCap ×0.9|)/Cap > 0.25 |
| DQ-01 | Medium | zero-change days > 15 |
| DQ-02 | Low | any missing value |

All comparisons are strict `>`. Verdict per desk: any High → `reject`;
else any Medium → `approve-with-conditions`; else `approve` (exact strings).

### 7.2 Report sections (generated `validation_report.md`)

`1. Scope & Overview`, `2. Pricing Benchmark`, `3. Sensitivity Verification`,
`4. Capital Stability`, `5. VaR Backtesting`, `6. P&L Attribution (PLAT)`,
`7. Data Quality`, `8. NMRF / SES`, `9. Findings`, `10. Overall Verdict` —
all ten always emitted, prefixed `## `.

## 8. Error behavior

Python raises `ValueError` with a descriptive message (C++
`std::invalid_argument`/`domain_error`; Rust `Result<_, FrtbError>`; Java
`IllegalArgumentException`) for: non-finite/negative-domain pricer inputs;
non-increasing curve tenors; bumping a non-node tenor; unknown instrument
type / option type / position; missing bucket, rating, tenor RW, RRAO
category or params key; series length mismatches; constant series in Pearson;
category P&L not summing to the desk P&L; negative VaR or stressed loss;
unknown scenario or PLAT zone. No RNG anywhere at runtime; the only seeded
RNG lives in `data/generate_data.py` (seed 20250815).

## 9. Golden cases (`data/golden/golden.json`)

Flat schema `{cases:[{name, inputs, expect, tol}]}`; every `inputs`/`expect`
value is a scalar (number or string — zones/verdicts are strings). 21 cases:

| case | expect keys | tol |
|---|---|---|
| sbm_agg_hand_2bucket | k_a, k_b, total | 1e-12 |
| girr_ws_rates_desk | ws_0.25 … ws_30 (10 keys, desk1/USD) | 1e-8 |
| girr_kb_usd | kb (firm, medium) | 1e-8 |
| equity_kb_bucket1 | kb (firm, medium) | 1e-8 |
| sbm_firm_scenarios | high, medium, low, capital | 1e-8 |
| sbm_desk_capitals | desk1, desk2 | 1e-8 |
| curvature_equity_desk2 | charge (medium) | 1e-8 |
| drc_firm | charge, hbr | 1e-10 |
| rrao_firm | charge | 1e-12 |
| es_desk1 / es_desk2 | es_base, es_lh | 1e-8 |
| imcc_desks | desk1, desk2 | 1e-8 |
| plat_desk1 / plat_desk2 | spearman, ks (1e-8), zone (exact string) | 1e-8 |
| backtest_desk1 / backtest_desk2 | exceptions (exact int), multiplier | 1e-12 |
| ses_firm | charge | 1e-10 |
| benchmark_max_diff | value | 1e-8 |
| stability_girr_rw_up10 | delta_capital | 1e-8 |
| verdict_desk1 / verdict_desk2 | verdict (exact string) | — |

Pinned data facts asserted at generation time: desk1 PLAT **green** with a
green backtest (2 exceptions), desk2 PLAT **amber** with **exactly 5** VaR
exceptions (multiplier 1.70), verdicts `approve` / `approve-with-conditions`.
