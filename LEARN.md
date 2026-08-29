# LEARN — FRTB Mechanics & Independent Model Validation

> ## ⚠ Educational implementation — read this first
>
> **Everything in this project is a teaching device, not a compliant capital
> engine.** The risk weights, correlations, buckets, thresholds and several
> formulas are a pinned, *Basel-2019-flavored* simplification: a subset of
> risk classes (GIRR, equity, FX), simplified bucket sets, no securitisations,
> no CSR/commodity classes, a one-bucket FX treatment, a simplified low
> correlation scenario, and a non-standard curvature correlation rule. Section
> 10 lists every deviation. If you need real FRTB capital, read the Basel
> text (MAR20–MAR33) and your regulator's implementation — never this code.

This document teaches the mechanics the project implements: why FRTB exists,
how the Standardised Approach builds capital from sensitivities, how the
Internal Models Approach builds it from Expected Shortfall, how desks earn
(and lose) model approval, and how an independent validation function
challenges the whole stack. All worked numbers are golden values reproduced
bit-for-bit by all four language implementations on the bundled data.

---

## 1. Why FRTB exists

### 1.1 The 2008 lesson

Pre-crisis market-risk capital (Basel II) rested on a 10-day 99% Value-at-Risk
computed by each bank's internal model. In 2008 this failed in three
compounding ways:

1. **VaR ignored the tail beyond its own quantile.** A 99% VaR says nothing
   about *how bad* the worst 1% is. Books stuffed with senior CDO tranches
   showed tiny VaR right up until they lost half their value.
2. **The trading book / banking book boundary was gameable.** Trading-book
   capital (VaR-based) was far lower than banking-book capital (credit-risk
   weights) for the same asset. Banks warehoused illiquid credit in the
   trading book — "held for trading" in name only — and capital did not
   reflect the risk of positions that could not actually be traded out of.
3. **Liquidity was assumed uniform.** The 10-day horizon treated an S&P 500
   future and a bespoke correlation tranche as equally liquid. In the crisis,
   exit horizons for structured credit stretched to months.

### 1.2 Basel 2.5 — the patches

The 2009 emergency response bolted extra charges onto the old framework:
**Stressed VaR** (VaR over a crisis window, added to ordinary VaR), the
**Incremental Risk Charge** (default and migration risk in the trading book),
and the **Comprehensive Risk Measure** for correlation trading. It roughly
tripled market-risk capital but was widely seen as incoherent: overlapping
charges, double counting, and still VaR at the core.

### 1.3 FRTB — the rewrite

The Fundamental Review of the Trading Book (final standard January 2019,
effective 2023+ in most jurisdictions) rebuilt the framework:

* **ES replaces VaR.** A 97.5% Expected Shortfall — the *average* loss beyond
  the quantile — replaces 99% VaR. For a normal distribution the two are
  calibrated to be nearly equal, but ES is coherent (subadditive) and sees
  the tail shape.
* **Liquidity horizons differentiate risk factors.** Risk factors get 10, 20,
  40, 60 or 120-day horizons; illiquid factors are scaled up.
* **A hard, revised boundary** between trading and banking books, with
  restrictions on moving positions and capital consequences for doing so.
* **Desk-level internal model approval.** Approval is earned desk by desk via
  a P&L attribution test and backtesting — not bank-wide. A failing desk
  falls back to the Standardised Approach.
* **A credible, risk-sensitive Standardised Approach** (the
  Sensitivities-Based Method) that every bank must compute — approved or not
  — so it acts as a floor, a fallback and a comparable benchmark.
* **NMRF:** risk factors without enough real, observable prices cannot go in
  the ES model and are capitalised by stress scenarios instead.

This project implements a miniature of each of those pieces.

---

## 2. The two-approach architecture

```
                        every desk, always
                        ┌──────────────────────────────┐
                        │  SA = SBM + DRC + RRAO       │
                        └──────────────────────────────┘
   desk with approval   ┌──────────────────────────────┐
   (PLAT + backtest OK) │  IMA = m·IMCC + SES (+PLAT   │
                        │        amber surcharge)      │
                        └──────────────────────────────┘
```

The Standardised Approach (SA) is the sum of three components: the
**Sensitivities-Based Method** (SBM — delta, vega, curvature per risk class),
the **Default Risk Charge** (DRC — jump-to-default), and the **Residual Risk
Add-On** (RRAO — exotic payoffs the sensitivities miss). The Internal Models
Approach (IMA) is a multiplier times the **Internally Modelled Capital
Charge** (IMCC, an ES-based aggregate) plus **SES** (NMRF stress capital),
plus — in this kit — a PLAT amber surcharge.

---

## 3. SBM step 1 — sensitivities

The SBM does not see positions; it sees **sensitivities** to a prescribed
grid of risk factors. This project computes them by pinned bump-and-revalue
on a small deterministic pricer kit (bond, payer-swap proxy, Black–Scholes
option, FX forward):

| risk factor | bump | sensitivity |
|---|---|---|
| GIRR delta (curve node) | +1bp absolute on one node | `s = (V⁺ − V)/10⁻⁴` (dV/dr) |
| Equity delta (name) | +1% relative spot | `s = (V⁺ − V)/0.01` (= S·dV/dS) |
| Equity vega (name) | +1 vol pt absolute | `s = raw vega · σ` |
| FX delta (pair) | +1% relative spot | `s = (V⁺ − V)/0.01` |

Note the FRTB conventions: equity and FX deltas are defined against a
*relative* shift, so the sensitivity is `S·∂V/∂S` (a currency amount per 100%
spot move), and vega is scaled by the implied vol (`vega·σ`), which makes a
1-vol-point move on a 10-vol asset "worth" less than on a 30-vol asset. GIRR
delta is against an *absolute* 1bp shift, expressed per unit of rate.

Each bump reprices the **whole portfolio** against an immutable market
snapshot — so desk 2's equity options correctly contribute GIRR delta through
their discounting, and no bump can leak state into the next.

For the bundled rates desk (desk1: three bonds + a 10y payer swap, all USD),
the resulting GIRR ladder (golden case `girr_ws_rates_desk`) is:

| tenor | s = dV/dr | RW | WS = RW·s |
|---|---|---|---|
| 1y  | −135,719.79 | 1.6% | −2,171.5167 |
| 2y  | −11,529,072.91 | 1.3% | −149,877.9478 |
| 3y  | −89,619.97 | 1.2% | −1,075.4396 |
| 5y  | −39,420,819.92 | 1.1% | −433,629.0192 |
| 10y | +90,326,576.04 | 1.1% | +993,592.3365 |

Read the signs: the bonds lose value when rates rise (negative dV/dr at their
coupon/redemption tenors), while the payer swap *gains* — its +90.3m dV/dr at
10y dominates. The desk is net short rates at the long end and long at the
short end: a curve-steepening position. This is exactly the structure the
correlation scenarios will probe (§5).

---

## 4. SBM step 2 — aggregation: K_b, γ, S_b

### 4.1 The formulas

Weighted sensitivities aggregate in two stages. Within a bucket *b* (a
currency for GIRR, an industry/size group for equity):

```
WS_k = RW_k · s_k
K_b  = sqrt( max(0,  Σ_k WS_k²  +  Σ_{k≠l} ρ_kl · WS_k · WS_l ) )
```

This is the standard deviation formula of a sum of correlated normals — the
quadratic form `WSᵀ ρ WS` — with a `max(0,·)` guard because with scenario
scaling the tabulated matrix need not stay positive semi-definite and the
quadratic form can round (or genuinely dip) below zero.

Across buckets:

```
Charge = sqrt( max(0,  Σ_b K_b²  +  Σ_{b≠c} γ_bc · S_b · S_c ) ),   S_b = Σ_k WS_k
```

`S_b` is the *signed net* sensitivity of the bucket — so a long bucket and a
short bucket hedge each other through the γ term. But that construction can
make the argument negative (K_b can be much smaller than |S_b| suggests when
a bucket is internally hedged). Hence the **S_b fallback**: if the argument
is negative, recompute once with

```
S_b = max( min(Σ_k WS_k, K_b), −K_b )
```

i.e. clamp each S_b into `[−K_b, K_b]`, which guarantees the quadratic form
is non-negative for |γ| ≤ 1.

### 4.2 Worked example (golden case `sbm_agg_hand_2bucket`, tol 1e-12)

Bucket A holds WS = {+10, −5} with intra-bucket ρ = 0.5; bucket B holds a
single WS = {+8}; γ = 0.25.

```
K_A = sqrt(10² + (−5)² + 2·0.5·10·(−5))
    = sqrt(100 + 25 − 50) = sqrt(75)  = 8.660254037844387
K_B = sqrt(8²) = 8
S_A = 10 − 5 = 5,   S_B = 8

Charge = sqrt(K_A² + K_B² + 2·γ·S_A·S_B)
       = sqrt(75 + 64 + 2·0.25·5·8)
       = sqrt(159) = 12.609520212918492
```

Notice `K_A < |10| + |−5|`: partial hedging within the bucket (the −5 offsets
part of the +10 at ρ = 0.5). And the cross term uses the *net* +5, not K_A —
the bucket enters the cross-bucket correlation with its net direction.

### 4.3 A real K_b

For the firm's USD GIRR bucket under the medium scenario, feeding the WS
ladder of §3 (plus desk 2's small discounting contributions) through the
tenor correlation matrix `ρ_kl = max(exp(−0.03·|T_k−T_l|/min(T_k,T_l)), 0.40)`
gives (golden `girr_kb_usd`):

```
K_USD = 413,453.9738180512
```

against a gross Σ|WS| of roughly 1.58m — the steepener is heavily internally
hedged, and high tenor correlations let the long and short legs cancel.

---

## 5. The three correlation scenarios

Correlations are the least stable inputs in the whole construction — in
crises they lurch. FRTB therefore computes the entire SBM **three times**,
scaling *every* ρ and γ:

| scenario | this project (pinned) | real Basel |
|---|---|---|
| high | `min(1.25·ρ, 1.0)` | same |
| medium | `ρ` | same |
| low | `0.75·ρ` | `max(2ρ − 1, 0.75·ρ)` |

and takes **capital = max of the three scenario totals** (each total = sum of
delta + vega + curvature over risk classes).

Why does a *lower* correlation ever produce *more* capital? Rerun the §4.2
example under each scenario:

| scenario | ρ_A | γ | K_A | Charge |
|---|---|---|---|---|
| high | 0.625 | 0.3125 | 7.9057 | 12.3085 |
| medium | 0.500 | 0.2500 | 8.6603 | 12.6095 |
| low | 0.375 | 0.1875 | 9.3541 | 12.9035 |

The bucket contains a *hedge* (+10 vs −5). High correlation makes the hedge
work better → smaller K_A. Low correlation weakens the hedge → larger charge.
A one-way book behaves oppositely: same-sign sensitivities are *penalised* by
high correlation. The max-over-scenarios rule charges you for whichever
correlation regime is worst *for your particular book*. The bundled rates
desk is a steepener (hedged), so its binding scenario is **low**
(681,412 delta vs 407,081 under high); so is the firm's (golden
`sbm_firm_scenarios`: high 4,838,909.11 / medium 4,947,340.71 / **low
5,310,401.79** = capital).

---

## 6. Curvature — the charge for gamma

Delta×RW misses convexity: a sold option loses *more* than delta predicts on
a large move. Curvature captures the residual by applying the **full risk
weight** as a shock, up and down, and stripping the delta part:

```
CVR_k⁺ = −( V(up-shock)   − V − RW_k^curv · s_k )
CVR_k⁻ = −( V(down-shock) − V + RW_k^curv · s_k )
```

The leading minus sign converts "P&L worse than delta-predicted" into a
positive number. For a *long* option (positive gamma) both CVRs are negative
— curvature helps you — and for a *short* option both are positive.

Within a bucket, each side aggregates separately:

```
K_b^± = sqrt( max(0, Σ_k max(CVR_k^±, 0)²
              + Σ_{k≠l} ρ_kl · CVR_k^± · CVR_l^± · ψ(CVR_k^±, CVR_l^±)) )
ψ(a,b) = 0 if a < 0 and b < 0, else 1
```

Two asymmetries are deliberate:

* only `max(CVR, 0)²` enters the diagonal — a helpful (negative) curvature
  can offset others via the cross terms but earns no standalone credit;
* **ψ kills the cross term when both CVRs are negative** — two long-gamma
  positions may not team up to *reduce* the charge below zero's reach.

Then `K_b = max(K_b⁺, K_b⁻)` (worst side wins; ties go to the up side), and
across buckets the same form is applied with `γ²` and `ψ(S_b, S_c)`, where
S_b is the CVR sum on the winning side.

On the bundled equity/FX desk: the two long options (AAA_TECH, EURO_BANK)
have negative CVRs on both sides, while the *short* GLOBAL_INDEX call shows
CVR⁺ = +181,329, CVR⁻ = +211,099 — short gamma costs capital. The medium
equity curvature charge is 188,052.638 (golden `curvature_equity_desk2`);
notice on the demo output that curvature *increases* from high to low
scenario (173,751 → 198,465): the long-gamma names hedge the short-gamma
index only through the cross terms, and lower correlation weakens that hedge.

Why shock by the full risk weight rather than differentiate twice? FRTB's
curvature is a *stress*, not a Greek: it captures the whole non-linear
remainder at a crisis-sized move (55% for a bucket-1 equity!), including
higher-order terms beyond gamma.

---

## 7. DRC-lite — default risk

Spread deltas capture *migration*-like repricing but not the jump to default.
The DRC charges for that jump on a **notional** basis:

```
JTD_i        = LGD·notional_i + (MV_i − notional_i)      (signed; shorts have negative notional)
netJTD_issuer = Σ same-issuer JTD                         (long/short netting)
HBR          = ΣnetLong / (ΣnetLong + Σ|netShort|)        (1 if no net shorts)
DRC          = max(0, Σ RW(rating)·netLong − HBR·Σ RW(rating)·|netShort|)
```

The `(MV − notional)` term recognises that a bond bought above par loses the
premium too, and one bought at a discount has already priced some loss. The
**hedge benefit ratio** is the framework's scepticism about shorts: short
protection only offsets longs in proportion to how long the book is overall —
a book that is 90% long gets 90% credit for its shorts, never 100%, because
single-name shorts do not default *with* your longs.

The bundled firm book (all long → HBR = 1):

| issuer | rating | notional | MV | JTD = 0.75·N + (MV−N) | RW | RW·JTD |
|---|---|---|---|---|---|---|
| UST-PROXY | AAA | 10,000,000 | 9,841,798.70 | 7,341,798.70 | 0.5% | 36,708.99 |
| CORP-A | A | 8,000,000 | 8,005,982.14 | 6,005,982.14 | 3% | 180,179.46 |
| CORP-B | BBB | 6,000,000 | 5,862,671.13 | 4,362,671.13 | 6% | 261,760.27 |
| HY-CORP | BB | 5,000,000 | 5,683,719.63 | 4,433,719.63 | 15% | 665,057.94 |

DRC = **1,143,706.6695** (golden `drc_firm`). One BB bond at a tenth of the
book's notional contributes over half the charge — rating risk weights are
brutally convex. Simplifications versus real Basel: bonds only, no maturity
scaling, one rating per issuer enforced, no securitisation treatment.

---

## 8. RRAO — the residual risk add-on

Some payoffs (digitals, barriers, correlation exposure, longevity…) have
risks that neither sensitivities nor curvature see. FRTB's answer is
deliberately crude: a flat notional charge,

```
RRAO = Σ_flagged rate(category) · notional,   exotic → 1.0%,  other → 0.1%
```

In the bundled book the short index call is flagged `exotic`
(0.01 × 7.8m = 78,000), and the swap and HY bond are flagged `other`
(0.001 × 25m = 25,000), so firm RRAO = **103,000** (golden `rrao_firm`).
Crude is the point: the RRAO is a tax on unmodellable complexity, meant to
make banks think twice about warehousing it.

---

## 9. The IMA sketch — ES, liquidity horizons, IMCC

### 9.1 Expected Shortfall at 97.5%

For a P&L sample of n days, losses `L = −PnL` sorted descending:

$$
k = \lceil (1-\alpha)\,n \rceil, \qquad
\mathrm{ES}_\alpha = \frac{1}{k}\sum_{i=1}^{k} L_{(i)}, \qquad \alpha = 0.975
$$

With n = 260, k = 7: the mean of the seven worst days. (The implementation
computes `ceil((1−α)n − 1e-9)` — the ε guards against binary-float artefacts
such as `(1 − 0.975)·40 = 1.0000000000000009` rounding k up to 2.) For desk 1 the
seven worst losses are 147,056 / 111,609 / 109,976 / 105,505 / 93,764 /
86,546 / 85,535, whose mean 105,712.86 scaled by √10 gives the base 10-day ES
**334,293.42** (golden `es_desk1`).

Why 97.5% ES rather than 99% VaR? For a normal distribution ES₉₇.₅ ≈ VaR₉₉
(2.34σ vs 2.33σ) — the calibration was chosen so capital doesn't jump — but
ES averages the whole tail, so two books with the same 99th percentile and
very different extreme tails are finally told apart.

### 9.2 The liquidity-horizon ladder

Each risk-factor category gets a horizon (pinned here: ir → 20, eq → 20,
fx → 40, cr → 60 days; the ladder is LH = 10, 20, 40, 60, 120). The scaled ES
is:

$$
\mathrm{ES}_{LH} = \sqrt{\; \mathrm{ES}_1(P)^2 \;+\; \sum_{j\ge 2}
\left( \mathrm{ES}_1(P_j)\,\sqrt{\tfrac{LH_j - LH_{j-1}}{10}} \right)^{\!2}}
$$

where `P` is the full desk P&L, `P_j` the P&L of categories with horizon
≥ LH_j, and `ES₁` the base-10d ES operator. Each rung adds the *incremental*
horizon (LH_j − LH_{j−1}) for only the factors still "alive" at that rung —
a position you can exit in 20 days stops contributing beyond the 20-day rung.
The ladder is monotone (`ES_LH ≥ ES₁`) and a single category with horizon LH
collapses to `ES₁·sqrt(LH/10)` — desk 1 is pure `ir` (LH 20), so
`ES_LH = 334,293.42·√2 = 472,762.29` exactly. Desk 2 mixes eq/fx/cr and lands
at 612,536.77 (golden `es_desk2`).

### 9.3 IMCC — constrained diversification

A full-portfolio ES harvests diversification across risk categories;
regulators only half-trust it. The IMCC blends the diversified and
undiversified views:

$$
\mathrm{IMCC} = \rho\,\mathrm{ES}_{LH}(\text{full}) + (1-\rho)\sum_c \mathrm{ES}_{LH}(\text{category } c),
\qquad \rho = 0.5
$$

Desk 2: `0.5·612,536.77 + 0.5·(468,581.54 + 341,897.12 + 231,910.13)` where
the partial terms are the eq / fx / cr single-category ladders (each
collapsing to `ES₁·√(LH/10)`: √2, ×2, √6) — IMCC = **827,462.78** (golden
`imcc_desks`). Desk 1 has a single category so IMCC = ES_LH = 472,762.29.

IMA core capital = `multiplier · IMCC + SES`. (Real Basel:
`max(IMCC_{t−1}, m·avg₆₀ IMCC)` on stressed-calibrated ES; the bundled
portfolio is static so avg₆₀ = IMCC and the max resolves to m·IMCC — a
documented simplification.)

### 9.4 NMRF / SES

Risk factors without enough observable prices (here: an IR basis, an EM repo
rate, FX vol wings, pinned in `data/nmrf.json`) are excluded from the ES
model and capitalised as `SES = Σ stressed losses` with **zero
diversification** — the deliberately punitive treatment that, in the real
world, drives banks' data-pooling efforts. Firm SES = 155,000
(golden `ses_firm`).

---

## 10. Backtesting — the multiplier

Each desk's 99% VaR is compared daily against P&L; an **exception** is
`PnL_t < −VaR_t` (strict). Over 260 days, the count maps to the Basel
traffic-light zone and the capital multiplier (pinned table):

| exceptions | zone | multiplier |
|---|---|---|
| 0–4 | green | 1.50 |
| 5 / 6 / 7 / 8 / 9 | amber | 1.70 / 1.75 / 1.83 / 1.88 / 1.92 |
| ≥ 10 (incl. > 12) | red | 2.00 (cap) |

The statistical logic: at 99% coverage you *expect* ~2.6 exceptions in 260
days. Five or more is evidence of underestimation (the binomial tail
probability drops fast); ten or more is damning at any reasonable
significance level, and in the real framework red also jeopardises the
desk's model approval. Desk 1 has 2 exceptions → green, 1.50; desk 2 has
exactly 5 → amber, 1.70 (goldens `backtest_desk1/2`).

---

## 11. PLAT — the P&L attribution test

Backtesting checks the model's *size*; PLAT checks its *anatomy*. Two P&L
series are compared per desk:

* **HPL** (hypothetical): what the actual positions earned under actual
  market moves — produced by front-office pricing.
* **RTPL** (risk-theoretical): the P&L the *risk model* predicts from its own
  risk factors.

If the risk model omits factors (a basis, a smile, a dividend), RTPL drifts
from HPL. The metrics (both implemented natively in `frtb/stats.py`):

* **Spearman correlation** — Pearson correlation of average ranks (ties get
  the average rank of their block). Rank-based, so it tests co-movement
  without assuming linearity.
* **Kolmogorov–Smirnov statistic** — `sup_t |F_HPL(t) − F_RTPL(t)|` over the
  pooled sample: do the two P&Ls even have the same distribution?

| zone | pinned rule |
|---|---|
| green | Spearman ≥ 0.85 **and** KS ≤ 0.09 |
| red | Spearman < 0.80 **or** KS > 0.12 |
| amber | otherwise |

Desk 1: Spearman 0.99487, KS 0.03462 → **green**. Desk 2: Spearman 0.84340
(between 0.80 and 0.85), KS 0.07308 → **amber** (goldens `plat_desk1/2`).
A constant P&L series on either side leaves the rank correlation undefined —
this kit conservatively assigns **red** with metrics reported as null.

Consequences: green desks keep IMA; red desks fall back to SA (a reporting
outcome here, not a surcharge); amber desks pay a capital surcharge that
interpolates toward SA (pinned k = 0.5):

```
surcharge = 0.5 · max(0, SA_desk − IMA_core_desk)
```

Desk 2: `0.5·(5,589,667.95 − (1.70·827,462.78 + 120,000)) = 2,031,490.62` —
by far the largest term in its IMA capital of 3,558,177. That is the real
economics of PLAT: a desk whose risk model can't explain its P&L gets pushed
halfway to standardised capital, and to SA entirely if it deteriorates.
(Real Basel uses Spearman plus a KS test with different thresholds and a
different surcharge formula `k·max(0, SA−IMA)` with k linear in the metrics;
the shape here is the teaching version.)

---

## 12. Independent model validation (SR 11-7 flavour)

Everything above is *the model*. Supervisory guidance (Fed SR 11-7 / OCC
2011-12, ECB TRIM) demands an independent function that provides **effective
challenge** — qualified people, separate reporting line, authority to reject.
The `frtb.validation` module is a miniature of that function's toolkit:

| check | method | pass criterion |
|---|---|---|
| **Benchmarking** | Reprice a 40-point option grid (S=100, K∈{70..130}, T∈{0.25..2}, calls+puts) with an *independent* pricer — CRR binomial, 501 steps — vs the production BS pricer | max abs diff ≤ 0.05 (actual: 5.163e-03) |
| **Sensitivity verification** | Analytic BS delta vs central finite difference, h = 10⁻⁴·S | max diff ≤ 1e-6 (actual: 3.42e-08) |
| **Stability** | Recompute firm SBM capital with GIRR delta RWs ×1.1 / ×0.9 | rel. move ≤ 25% (actual: 1.28%; +10% RW moves capital by 67,904.33 — golden `stability_girr_rw_up10`) |
| **Backtesting / PLAT** | Zones from §10–§11 | green |
| **Data quality** | Staleness (zero-change days > 15) and gaps (any NaN) in the P&L series | none |

Findings are classified by a pinned rule table (BENCH-01 High, SENS-01 High,
BT-01 High / BT-02 Medium, PLAT-01 High / PLAT-02 Medium, STAB-01 Medium,
DQ-01 Medium, DQ-02 Low) and roll up to a per-desk verdict:

```
any High   → reject
any Medium → approve-with-conditions
otherwise  → approve
```

On the bundled data: desk 1 → **approve** (no findings); desk 2 → BT-02 +
PLAT-02 → **approve-with-conditions** (goldens `verdict_desk1/2`). The demo
renders the full ten-section `validation_report.md` — scope, benchmark,
sensitivities, stability, backtesting, PLAT, data quality, NMRF, findings,
verdict — the same skeleton a real validation report follows.

The deeper lesson: validation is not re-running the developer's tests. It is
*independent reperformance* (a different pricer), *challenge of assumptions*
(what if the risk weights are 10% wrong?), *outcome analysis* (backtests,
PLAT), and *data scrutiny* — with findings that have teeth.

---

## 13. How this educational parameter set differs from real Basel

| area | this project (pinned) | real FRTB (MAR21+) |
|---|---|---|
| Risk classes | GIRR, equity, FX | + CSR (3 flavours), commodities, securitisations |
| GIRR curves | one zero curve per currency | multiple curves per currency, inflation, basis; RW currency discounts |
| GIRR tenor ρ | `max(exp(−0.03·Δ/min T), 0.40)`, 6dp table | `max(exp(−θ·Δ/min T), 0.40)` plus cross-curve/inflation rules |
| Equity buckets | 1–4 + 11, pinned RWs 55/60/45/55/15%, ρ = 0.15 flat | 13 buckets, spot/repo split, per-bucket ρ, large/small-cap rules |
| FX | single bucket, RW 15% flat | per-pair factors, RW 15% with √2 discount for liquid pairs |
| Low scenario | `0.75ρ` | `max(2ρ − 1, 0.75ρ)` |
| Curvature ρ | `ρ_scen²`, `γ_scen²`; no S_b fallback; up-side on ties | delta ρ/γ squared per the text, with its own ψ and fallback treatment |
| Vega | equity only, RW 78%; GIRR vega 0 (no IR-vol instruments); no FX vega | vega on all classes, maturity-dependent RWs, option-liquidity scaling |
| DRC | bonds only, no maturity scaling, 9-rating RW table | equities/derivatives decomposition, maturity weighting, seniority, securitisation DRC |
| RRAO | two flat rates on flagged notionals | same rates, but a precise instrument taxonomy |
| ES | single 260d window, √10 scaling, √t from daily | stressed-window calibration, reduced factor set ratio, overlapping 10d |
| Capital combination | m·IMCC + SES per desk | `max(IMCC_{t−1} + SES_{t−1}, m·avg₆₀(IMCC) + avg₆₀(SES))`, green/amber SA floors, 72.5% output floor |
| PLAT | Spearman + KS with pinned thresholds; k = 0.5 surcharge | Spearman + KS with the MAR32 thresholds, formal amber surcharge formula |
| Backtest | desk-level 99% only | desk 97.5% and 99%, firm-level multiplier from 99% exceptions plus qualitative add-on |

Every deviation is pinned in `data/sbm_params.json` / `API_SPEC.md` so that
all four language ports agree to 1e-8 or better — the point is cross-language
numerical fidelity to a *stated* contract, not regulatory fidelity.

---

## 14. The SA vs IMA trade-off

Why would a bank spend tens of millions building and validating an IMA stack?
Compare the bundled desk 2:

```
SA capital  = 5,589,668   (SBM 4,841,610 + DRC 665,058 + RRAO 83,000)
IMA capital = 3,558,177   (1.70 × 827,463 + 120,000 SES + 2,031,491 PLAT surcharge)
```

Even amber-zoned, IMA is ~36% cheaper; a green desk 2 would pay just
1,526,687 — a 73% saving. That is the carrot. The stick is everything else:

* **Operational cost.** Daily ES on thousands of factors, RTPL production,
  desk-level backtesting, NMRF evidence, an independent validation function.
* **Cliff risk.** A desk that slips to PLAT red or backtest red snaps back to
  SA — a sudden, visible capital jump that is hard to plan around.
* **The floor.** Real Basel caps the benefit: aggregate capital cannot fall
  below 72.5% of the full SA (output floor), and SA must be computed anyway.
* **NMRF drag.** Zero-diversification SES on illiquid factors can erase the
  ES benefit for exotic books — in QIS studies NMRF was often the largest
  single IMA component.

The industry outcome so far: many banks that ran Basel 2.5 internal models
have chosen SA-only under FRTB for most desks, reserving IMA for large liquid
flow businesses where the ES benefit clearly outruns the cost. The
SA-as-credible-fallback design made that retreat possible — which was partly
the intent.

---

## 15. Model assumptions and where they break

* **Sensitivities linearise; curvature stresses.** Between the 1bp/1% bumps
  and the full-RW shock nothing is checked: path-dependent or
  barrier-discontinuous payoffs can hide P&L cliffs between the two — that
  is what RRAO crudely taxes.
* **√t scaling** (`√10`, and the LH ladder's `√(ΔLH/10)`) assumes i.i.d.
  returns. Autocorrelated or trending losses scale worse than √t; the ladder
  inherits the flaw.
* **ES from 260 days** is a noisy tail estimate: k = 7 means the estimator
  is the mean of 7 order statistics. Real FRTB mitigates with stressed-window
  calibration; this kit documents rather than mitigates.
* **Static-portfolio IMCC** (avg₆₀ = IMCC) removes the time dimension real
  desks live with — capital volatility from the 60-day average is a genuine
  management problem.
* **Rank-based PLAT metrics** can be gamed by monotone transformations of
  RTPL; the KS test partially closes that hole (it sees the distribution),
  which is exactly why FRTB uses both.
* **DRC netting by issuer name** assumes legal netting actually works across
  instruments (seniority, maturity mismatches ignored here; real Basel
  handles both).
* **Correlation scenarios are a blunt instrument**: three global scalings
  cannot represent, say, equity-bond decorrelation while intra-equity
  correlations spike.

---

## 16. Practical conventions and pitfalls

### Market conventions in this kit

* **Equity**: relative 1% spot bumps (FRTB convention → `s = S·dV/dS`),
  Black–Scholes with continuous dividend yield q, flat vol per name, buckets
  assigned per name in `spots.csv`.
* **FX**: the pair is quoted FORDOM (EURUSD = USD per EUR); the forward is
  valued in the *domestic* (quote) currency `N·(S·DF_for − K·DF_dom)`; the
  FX delta is against a relative spot bump of the pair. Only the EURUSD spot
  risk factor exists — a real desk would also see the EUR curve as GIRR (it
  does here: the forward contributes EUR GIRR delta through DF_for).
* **Rates**: continuously-compounded zero curves, linear interpolation in
  tenor, flat extrapolation; sensitivities only at the 10 pinned nodes.

### Numerical pitfalls the code guards against

1. **`ceil` on binary floats**: `(1−0.975)·260` is not exactly 6.5 in binary;
   naive `ceil` can produce k = 8 on some platforms. The ε-guard
   `ceil(x − 1e-9)` pins k = 7 in every language.
2. **Negative quadratic forms**: scenario-scaled correlation matrices are not
   guaranteed PSD; `max(0,·)` inside every sqrt prevents NaN capital.
3. **The S_b fallback must be taken once, not iterated** — the clamped S_b
   can still leave a (tiny) negative argument, absorbed by `max(0,·)`.
4. **Bump leakage**: bump-and-revalue on a mutable market object is the
   classic source of order-dependent sensitivities; here every bump returns a
   fresh immutable snapshot.
5. **Dropping near-zero sensitivities** (|s| ≤ 1e-9) keeps all-zero
   currencies out of the GIRR buckets — otherwise EUR would appear as a
   bucket of exact zeros and pollute cross-bucket terms with sign noise.
6. **Ties in Spearman**: average ranks are required for exact agreement with
   scipy; naive ranking silently shifts the correlation on tied P&L days.
7. **KS with duplicated values**: the two-pointer sweep must advance both
   pointers through *all* values ≤ the current point, or ties between the
   samples give a wrong supremum.
8. **String formatting of tenors**: bucket keys like `"0.25"` vs `0.25`
   (JSON) — the loader normalises to floats once, at the boundary.

---

## 17. Interview-style Q&A

**Q1. Why did FRTB replace 99% VaR with 97.5% ES?**
VaR is not subadditive (merging desks can *raise* measured risk) and is blind
to tail shape beyond its quantile. ES is coherent and averages the whole
tail. 97.5% was chosen because ES₉₇.₅ ≈ VaR₉₉ under normality, keeping the
calibration roughly capital-neutral for thin-tailed books while penalising
fat-tailed ones.

**Q2. In the SBM, why can the low-correlation scenario produce the highest
capital?**
Cross terms `ρ·WS_k·WS_l` are negative for hedged (opposite-sign) pairs.
Lowering ρ shrinks that negative contribution, weakening the hedge and
raising K_b. Hedged books bind on low; directional books bind on high. The
max-over-scenarios rule makes correlation misestimation costly in whichever
direction hurts *you*.

**Q3. What problem does the S_b fallback solve?**
With S_b = ΣWS, the across-bucket quadratic form can go negative when a
bucket is internally hedged (K_b ≪ |S_b| is impossible, but K_b small with
large offsetting cross terms is not). Clamping S_b into [−K_b, K_b]
guarantees the form is non-negative for |γ| ≤ 1 while preserving the sign of
the net exposure.

**Q4. Walk through the curvature ψ function. Why does it exist?**
ψ(a,b) = 0 iff both CVRs are negative. Negative CVR means curvature *helps*
(long gamma). Without ψ, two long-gamma factors would generate positive
cross-products (negative×negative), inflating the charge — or with helpful
sign conventions, engineering charge reductions. ψ ensures helpful curvature
can offset harmful curvature but never compound with itself.

**Q5. Your desk's JTD is positive on a bond bought below par. Why is JTD not
just LGD × notional?**
`JTD = LGD·N + (MV − N)`. Buying at 90 means 10 points of loss are already in
the price — your true incremental default loss is smaller. Symmetrically, a
premium bond loses the premium too. The MV adjustment stops double counting
between market-value risk and default risk.

**Q6. What is the HBR and why does it exist?**
Hedge Benefit Ratio = netLong/(netLong + |netShort|), applied to the *short*
side of the DRC. Cross-issuer shorts hedge systematic default risk but not
idiosyncratic jumps — issuer A's short doesn't pay when issuer B defaults.
HBR grants shorts only proportional credit, interpolating between full
offset (balanced book) and none.

**Q7. How does the liquidity-horizon ladder avoid double counting a 20-day
factor inside the 40-day rung?**
Each rung j uses the *incremental* horizon √((LH_j − LH_{j−1})/10) on the
subset P_j of factors with horizon ≥ LH_j. A 20-day factor appears in the
base term and the 10→20 rung, then drops out of P_j for j beyond 20. Summing
in quadrature reassembles each factor's total horizon: a lone LH-20 category
gives exactly ES·√(20/10).

**Q8. PLAT green desk, backtest red — what does that combination tell you?**
The risk model *explains* the P&L (right factors, right anatomy) but
*understates* its scale — e.g. correct exposures with volatilities calibrated
to a calm window. The converse (backtest green, PLAT red) suggests
compensating errors: total size right, composition wrong. The two tests are
deliberately orthogonal; here the red backtest drives both the 2.0 multiplier
and a High finding (BT-01 → reject).

**Q9. Your validation benchmark shows BS vs binomial max diff of 5e-3 on
40 grid points. What does that check actually establish — and what not?**
It establishes independent reperformance: a lattice with different
approximation error agreeing to 0.5 cents on a 100-spot grid rules out gross
implementation errors (wrong d₁, wrong discounting, wrong dividend
treatment). It does *not* validate the model itself (both engines share the
GBM assumption), inputs (vols, curves), or anything path-dependent. Hence
SR 11-7's insistence that benchmarking is one leg of validation, alongside
outcome analysis and conceptual soundness.

**Q10. Why does the amber PLAT surcharge use max(0, SA − IMA_core)?**
The surcharge pulls an amber desk halfway (k = 0.5) toward standardised
capital — but only when SA is *higher*. If the desk's IMA already exceeds SA,
attribution failure carries no capital incentive to exploit, so the surcharge
floors at zero rather than rewarding the desk.

---

## 18. Further reading

* BCBS, *Minimum capital requirements for market risk* (MAR), January 2019
  (rev. February 2019) — the FRTB standard itself; MAR21 (SBM), MAR22 (DRC),
  MAR23 (RRAO), MAR30–33 (IMA, PLAT, backtesting).
* BCBS, *Explanatory note on the minimum capital requirements for market
  risk*, January 2019 — the readable companion.
* Board of Governors of the Federal Reserve, *SR 11-7: Guidance on Model
  Risk Management*, 2011 — the validation canon: effective challenge,
  benchmarking, outcome analysis.
* BCBS, *Messages from the academic literature on risk measurement for the
  trading book* (Working Paper 19, 2011) — the intellectual case for ES and
  liquidity horizons.
* Acerbi & Tasche, *On the coherence of Expected Shortfall* (2002) — why ES
  is subadditive and VaR is not.
* Gneiting, *Making and evaluating point forecasts* (2011) — ES elicitability
  and the backtesting-of-ES debate (why FRTB backtests VaR, not ES).
* John Hull, *Risk Management and Financial Institutions*, ch. on market risk
  regulation — accessible Basel history from I to FRTB.
* Roncalli, *Handbook of Financial Risk Management* (2020) — worked SBM
  examples with the real parameter tables, useful to contrast with this
  kit's pinned set.
