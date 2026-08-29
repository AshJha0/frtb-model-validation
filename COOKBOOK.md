# COOKBOOK — Task-Oriented Recipes

Copy-paste recipes for the `frtb` kit in all four languages. Python snippets
run against the real package (`cd python && PYTHONPATH=src python3`, data at
`../data`); C++/Rust/Java snippets follow the API_SPEC naming (namespace
`frtb`, crate `frtb`, package `com.quant.frtb`) with idiomatic casing per
language. Expected numbers are the golden values on the bundled data.
Everything is deterministic — rerunning any recipe reproduces the numbers
exactly.

> Reminder: educational parameter set, Basel-2019-flavored. Not for real
> capital.

---

## 1. How do I run the whole pipeline on the bundled data?

`compute_results` loads params, market, portfolio and P&L, then computes SA
per desk and firm, the IMA sketch, and all validation output in one call.

**Python**

```python
from pathlib import Path
import frtb

res = frtb.compute_results(Path("../data"))
print(f"firm SA capital: {res['sa']['firm'].capital:,.2f}")      # 6,557,108.46
print(f"desk2 IMA capital: {res['ima']['desk2']['capital']:,.2f}")  # 3,558,177.34
print(res["validation"]["verdicts"])  # {'desk1': 'approve', 'desk2': 'approve-with-conditions'}
```

**C++**

```cpp
#include "frtb/engine.hpp"

frtb::Results res = frtb::compute_results("../data");
std::cout << "firm SA capital: " << res.sa.at("firm").capital() << "\n";
std::cout << "desk2 verdict: " << res.validation.verdicts.at("desk2") << "\n";
```

**Rust**

```rust
use frtb::compute_results;

let res = compute_results("../data")?;
println!("firm SA capital: {:.2}", res.sa["firm"].capital());
println!("desk2 verdict: {}", res.validation.verdicts["desk2"]);
```

**Java**

```java
import com.quant.frtb.Engine;
import com.quant.frtb.Results;

Results res = Engine.computeResults("../data");
System.out.printf("firm SA capital: %,.2f%n", res.sa.get("firm").capital());
System.out.println("desk2 verdict: " + res.validation.verdicts.get("desk2"));
```

---

## 2. How do I load the market and price the portfolio?

**Python**

```python
from pathlib import Path
import frtb

data = Path("../data")
market = frtb.load_market(data / "curves.csv", data / "spots.csv")
desks = frtb.load_portfolio(data / "portfolio.json")

for name, desk in sorted(desks.items()):
    pv = frtb.price_portfolio(desk.instruments, market)
    print(f"{name}: PV = {pv:,.2f}")
for inst in desks["desk1"].instruments:
    print(inst.inst_id, f"{frtb.price_instrument(inst, market):,.2f}")
```

**C++**

```cpp
#include "frtb/market.hpp"
#include "frtb/instruments.hpp"
#include "frtb/pricers.hpp"

frtb::Market market = frtb::load_market("../data/curves.csv", "../data/spots.csv");
auto desks = frtb::load_portfolio("../data/portfolio.json");
double pv = frtb::price_portfolio(desks.at("desk1").instruments, market);
```

**Rust**

```rust
use frtb::{load_market, load_portfolio, price_portfolio};

let market = load_market("../data/curves.csv", "../data/spots.csv")?;
let desks = load_portfolio("../data/portfolio.json")?;
let pv = price_portfolio(&desks["desk1"].instruments, &market)?;
```

**Java**

```java
import com.quant.frtb.Market;
import com.quant.frtb.Portfolio;
import com.quant.frtb.Pricers;

Market market = Market.load("../data/curves.csv", "../data/spots.csv");
Portfolio desks = Portfolio.load("../data/portfolio.json");
double pv = Pricers.pricePortfolio(desks.desk("desk1").instruments(), market);
```

---

## 3. How do I compute a desk's bump-and-revalue sensitivities?

Pinned bumps: +1bp per GIRR node, +1% relative equity/FX spot, +1 vol pt
(vega WS = raw·σ). The GIRR ladder below matches golden `girr_ws_rates_desk`
after weighting.

**Python**

```python
from pathlib import Path
import frtb

data = Path("../data")
params = frtb.load_params(data / "sbm_params.json")
market = frtb.load_market(data / "curves.csv", data / "spots.csv")
desks = frtb.load_portfolio(data / "portfolio.json")

sens = frtb.compute_sensitivities(desks["desk1"].instruments, market, params)
for tenor, s in sens.girr["USD"].items():
    print(f"{tenor:>5g}y  s = {s:>15,.2f}   WS = {params.girr_rw(tenor) * s:>14,.4f}")
# 10y: s = 90,326,576.04, WS = 993,592.3365
```

**C++**

```cpp
#include "frtb/sensitivities.hpp"

frtb::Sensitivities sens =
    frtb::compute_sensitivities(desks.at("desk1").instruments, market, params);
for (const auto& [tenor, s] : sens.girr.at("USD"))
    std::cout << tenor << "y  WS = " << params.girr_rw(tenor) * s << "\n";
```

**Rust**

```rust
use frtb::compute_sensitivities;

let sens = compute_sensitivities(&desks["desk1"].instruments, &market, &params)?;
for (tenor, s) in &sens.girr["USD"] {
    println!("{tenor}y  WS = {:.4}", params.girr_rw(*tenor)? * s);
}
```

**Java**

```java
import com.quant.frtb.Sensitivities;

Sensitivities sens = Sensitivities.compute(
        desks.desk("desk1").instruments(), market, params);
sens.girr().get("USD").forEach((tenor, s) ->
        System.out.printf("%sy  WS = %.4f%n", tenor, params.girrRw(tenor) * s));
```

---

## 4. How do I compute SBM capital and drill into the scenarios?

**Python**

```python
from pathlib import Path
import frtb

data = Path("../data")
params = frtb.load_params(data / "sbm_params.json")
market = frtb.load_market(data / "curves.csv", data / "spots.csv")
desks = frtb.load_portfolio(data / "portfolio.json")
insts = [i for d in ("desk1", "desk2") for i in desks[d].instruments]

sens = frtb.compute_sensitivities(insts, market, params)
sbm = frtb.sbm_capital(sens, market, params)
print(sbm.scenario_totals)                # high/medium/low: 4,838,909 / 4,947,341 / 5,310,402
print(f"capital = {sbm.capital:,.2f}")    # 5,310,401.79 (low scenario binds)
print(sbm.charges["equity"]["curvature"]) # per-scenario curvature charges
print(sbm.kb_medium["girr"]["delta"])     # {'USD': 413453.97..., 'EUR': ...}
```

**C++**

```cpp
#include "frtb/sa.hpp"

frtb::SbmResult sbm = frtb::sbm_capital(sens, market, params);
std::cout << "capital = " << sbm.capital << "\n";
std::cout << "low total = " << sbm.scenario_totals.at("low") << "\n";
std::cout << "USD K_b = " << sbm.kb_medium.at("girr").at("delta").at("USD") << "\n";
```

**Rust**

```rust
use frtb::sbm_capital;

let sbm = sbm_capital(&sens, &market, &params)?;
println!("capital = {:.2}", sbm.capital);
println!("low total = {:.2}", sbm.scenario_totals["low"]);
println!("USD K_b = {:.4}", sbm.kb_medium["girr"]["delta"]["USD"]);
```

**Java**

```java
import com.quant.frtb.Sbm;
import com.quant.frtb.SbmResult;

SbmResult sbm = Sbm.capital(sens, market, params);
System.out.printf("capital = %,.2f%n", sbm.capital());
System.out.println("USD K_b = " + sbm.kbMedium().get("girr").get("delta").get("USD"));
```

---

## 5. How do I hand-run the K_b / gamma aggregation on my own numbers?

The low-level building blocks take plain weighted sensitivities and
correlation callbacks — ideal for reproducing the golden 2-bucket example
(K_A = √75, total = √159).

**Python**

```python
from frtb import aggregate_buckets, bucket_kb

k_a = bucket_kb([10.0, -5.0], lambda i, j: 0.5)   # 8.660254037844387
k_b = bucket_kb([8.0], lambda i, j: 0.0)          # 8.0
agg = aggregate_buckets({"A": k_a, "B": k_b}, {"A": 5.0, "B": 8.0},
                        lambda b, c: 0.25)
print(agg.charge, agg.used_fallback)              # 12.609520212918492 False
```

**C++**

```cpp
#include "frtb/sbm.hpp"

double ka = frtb::bucket_kb({10.0, -5.0}, [](int, int) { return 0.5; });
double kb = frtb::bucket_kb({8.0}, [](int, int) { return 0.0; });
frtb::AggregateResult agg = frtb::aggregate_buckets(
    {{"A", ka}, {"B", kb}}, {{"A", 5.0}, {"B", 8.0}},
    [](const std::string&, const std::string&) { return 0.25; });
// agg.charge == 12.609520212918492, agg.used_fallback == false
```

**Rust**

```rust
use frtb::{aggregate_buckets, bucket_kb};
use std::collections::BTreeMap;

let ka = bucket_kb(&[10.0, -5.0], |_, _| 0.5)?;
let kb = bucket_kb(&[8.0], |_, _| 0.0)?;
let kbs = BTreeMap::from([("A".into(), ka), ("B".into(), kb)]);
let ws = BTreeMap::from([("A".into(), 5.0), ("B".into(), 8.0)]);
let agg = aggregate_buckets(&kbs, &ws, |_, _| 0.25)?;
assert!((agg.charge - 12.609520212918492).abs() < 1e-12);
```

**Java**

```java
import com.quant.frtb.Sbm;
import com.quant.frtb.AggregateResult;
import java.util.Map;

double ka = Sbm.bucketKb(new double[] {10.0, -5.0}, (i, j) -> 0.5);
double kb = Sbm.bucketKb(new double[] {8.0}, (i, j) -> 0.0);
AggregateResult agg = Sbm.aggregateBuckets(
        Map.of("A", ka, "B", kb), Map.of("A", 5.0, "B", 8.0), (b, c) -> 0.25);
// agg.charge() == 12.609520212918492
```

---

## 6. How do I compute the DRC with issuer netting and the HBR?

**Python**

```python
from pathlib import Path
import frtb
from frtb import DrcPosition

data = Path("../data")
params = frtb.load_params(data / "sbm_params.json")

# hand positions: long and short the same issuer net; a cross-issuer short is HBR-weighted
positions = [
    DrcPosition(issuer="ACME", rating="BBB", notional=10_000_000, market_value=9_800_000),
    DrcPosition(issuer="ACME", rating="BBB", notional=-4_000_000, market_value=-3_900_000),
    DrcPosition(issuer="GLOBEX", rating="BB", notional=-2_000_000, market_value=-1_950_000),
]
res = frtb.drc_charge(positions, params)
print(f"HBR = {res.hbr:.4f}")          # 0.7521: net long 4.40m vs net short 1.45m
print(f"DRC = {res.charge:,.2f}")      # 100,410.26
print(res.net_jtd)                     # {'ACME': 4,400,000, 'GLOBEX': -1,450,000}

# or straight from the bundled book (bonds only):
market = frtb.load_market(data / "curves.csv", data / "spots.csv")
desks = frtb.load_portfolio(data / "portfolio.json")
insts = [i for d in sorted(desks) for i in desks[d].instruments]
firm = frtb.drc_charge(frtb.drc_positions_from_instruments(insts, market), params)
print(f"{firm.charge:,.4f}")           # 1,143,706.6695 (HBR = 1, all long)
```

**C++**

```cpp
#include "frtb/sa.hpp"

std::vector<frtb::DrcPosition> pos = {
    {"ACME", "BBB", 10'000'000.0, 9'800'000.0},
    {"ACME", "BBB", -4'000'000.0, -3'900'000.0},
    {"GLOBEX", "BB", -2'000'000.0, -1'950'000.0},
};
frtb::DrcResult res = frtb::drc_charge(pos, params);
std::cout << "HBR = " << res.hbr << "  DRC = " << res.charge << "\n";
```

**Rust**

```rust
use frtb::{drc_charge, DrcPosition};

let pos = vec![
    DrcPosition::new("ACME", "BBB", 10_000_000.0, 9_800_000.0),
    DrcPosition::new("ACME", "BBB", -4_000_000.0, -3_900_000.0),
    DrcPosition::new("GLOBEX", "BB", -2_000_000.0, -1_950_000.0),
];
let res = drc_charge(&pos, &params)?;
println!("HBR = {:.4}  DRC = {:.2}", res.hbr, res.charge);
```

**Java**

```java
import com.quant.frtb.Drc;
import com.quant.frtb.DrcPosition;
import com.quant.frtb.DrcResult;
import java.util.List;

List<DrcPosition> pos = List.of(
        new DrcPosition("ACME", "BBB", 10_000_000.0, 9_800_000.0),
        new DrcPosition("ACME", "BBB", -4_000_000.0, -3_900_000.0),
        new DrcPosition("GLOBEX", "BB", -2_000_000.0, -1_950_000.0));
DrcResult res = Drc.charge(pos, params);
System.out.printf("HBR = %.4f  DRC = %,.2f%n", res.hbr(), res.charge());
```

---

## 7. How do I compute ES 97.5% with the liquidity-horizon ladder?

**Python**

```python
from pathlib import Path
import frtb
from frtb.engine import desk_categories

data = Path("../data")
params = frtb.load_params(data / "sbm_params.json")
hypo = frtb.load_pnl_csv(data / "pnl_hypo.csv")

full = hypo["desk2"]
cats = desk_categories("desk2", hypo)     # {'eq': ..., 'fx': ..., 'cr': ...}

es_daily = frtb.expected_shortfall_daily(full)          # mean of 7 worst of 260
es_base = frtb.es_base_10d(full)                        # sqrt(10) * daily: 381,042.59
es_lh = frtb.es_lh_scaled(full, cats, params.category_lh, params.lh_ladder)
print(f"{es_base:,.2f}  ->  {es_lh:,.2f}")              # 612,536.77
imcc = frtb.imcc(full, cats, params)
print(f"IMCC = {imcc:,.2f}")                            # 827,462.78
```

**C++**

```cpp
#include "frtb/ima.hpp"

double es_base = frtb::es_base_10d(full, 0.975);
double es_lh = frtb::es_lh_scaled(full, cats, params.category_lh,
                                  params.lh_ladder, 0.975);
double v = frtb::imcc(full, cats, params);
```

**Rust**

```rust
use frtb::{es_base_10d, es_lh_scaled, imcc};

let es_base = es_base_10d(&full, 0.975)?;
let es_lh = es_lh_scaled(&full, &cats, &params.category_lh, &params.lh_ladder, 0.975)?;
let v = imcc(&full, &cats, &params)?;
```

**Java**

```java
import com.quant.frtb.Ima;

double esBase = Ima.esBase10d(full, 0.975);
double esLh = Ima.esLhScaled(full, cats, params.categoryLh(), params.lhLadder(), 0.975);
double v = Ima.imcc(full, cats, params);
```

---

## 8. How do I backtest a desk's 99% VaR and get its multiplier?

**Python**

```python
from pathlib import Path
import frtb

data = Path("../data")
params = frtb.load_params(data / "sbm_params.json")
hypo = frtb.load_pnl_csv(data / "pnl_hypo.csv")
var99 = frtb.load_pnl_csv(data / "pnl_var.csv")

bt = frtb.backtest(hypo["desk2"], var99["desk2"], params)
print(bt.exceptions, bt.zone, bt.multiplier)   # 5 amber 1.7

# the mapping alone:
print(frtb.backtest_zone(9), frtb.backtest_multiplier(9, params))   # amber 1.92
print(frtb.backtest_zone(13), frtb.backtest_multiplier(13, params)) # red 2.0 (cap)
```

**C++**

```cpp
#include "frtb/ima.hpp"

frtb::BacktestResult bt = frtb::backtest(pnl, var99, params);
std::cout << bt.exceptions << " " << bt.zone << " " << bt.multiplier << "\n";
```

**Rust**

```rust
use frtb::backtest;

let bt = backtest(&pnl, &var99, &params)?;
println!("{} {} {}", bt.exceptions, bt.zone, bt.multiplier);
```

**Java**

```java
import com.quant.frtb.Ima;
import com.quant.frtb.BacktestResult;

BacktestResult bt = Ima.backtest(pnl, var99, params);
System.out.println(bt.exceptions() + " " + bt.zone() + " " + bt.multiplier());
```

---

## 9. How do I run the PLAT and compute the amber surcharge?

**Python**

```python
from pathlib import Path
import frtb

data = Path("../data")
params = frtb.load_params(data / "sbm_params.json")
hypo = frtb.load_pnl_csv(data / "pnl_hypo.csv")
rtpl = frtb.load_pnl_csv(data / "pnl_rtpl.csv")

pl = frtb.plat_test(hypo["desk2"], rtpl["desk2"], params)
print(f"spearman={pl.spearman:.6f} ks={pl.ks:.6f} zone={pl.zone}")
# spearman=0.843398 ks=0.073077 zone=amber

sa_desk2 = 5_589_667.95          # SA capital (recipe 1/4)
ima_core = 1.70 * 827_462.7784604847 + 120_000.0   # mult*IMCC + SES
sur = frtb.plat_surcharge(pl.zone, sa_desk2, ima_core, params)
print(f"surcharge = {sur:,.2f}")  # 0.5 * (SA - IMA_core) = 2,031,490.61
```

**C++**

```cpp
#include "frtb/plat.hpp"

frtb::PlatResult pl = frtb::plat_test(hypo, rtpl, params);
double sur = frtb::plat_surcharge(pl.zone, sa_desk2, ima_core, params);
```

**Rust**

```rust
use frtb::{plat_surcharge, plat_test};

let pl = plat_test(&hypo, &rtpl, &params)?;
let sur = plat_surcharge(&pl.zone, sa_desk2, ima_core, &params)?;
```

**Java**

```java
import com.quant.frtb.Plat;
import com.quant.frtb.PlatResult;

PlatResult pl = Plat.test(hypo, rtpl, params);
double sur = Plat.surcharge(pl.zone(), saDesk2, imaCore, params);
```

---

## 10. How do I compute the PLAT statistics on my own series?

Spearman (average ranks) and the exact two-sample KS statistic are exported
directly — no scipy needed at runtime.

**Python**

```python
from frtb import average_ranks, ks_statistic, spearman

x = [1.0, 4.0, 2.0, 2.0, 5.0]
y = [1.1, 3.9, 2.2, 1.9, 5.5]
print(average_ranks(x))          # [1.0, 4.0, 2.5, 2.5, 5.0]  (tied block -> 2.5)
print(f"{spearman(x, y):.6f}")   # 0.974679
print(f"{ks_statistic(x, y):.6f}")  # 0.200000

# constant series raise (PLAT maps this to Red):
try:
    spearman([1.0, 1.0, 1.0], y[:3])
except ValueError as e:
    print("undefined:", e)
```

**C++**

```cpp
#include "frtb/stats.hpp"

double rho = frtb::spearman(x, y);          // throws std::invalid_argument if constant
double d = frtb::ks_statistic(x, y);
```

**Rust**

```rust
use frtb::{ks_statistic, spearman};

let rho = spearman(&x, &y)?;   // Err(FrtbError) on a constant series
let d = ks_statistic(&x, &y)?;
```

**Java**

```java
import com.quant.frtb.Stats;

double rho = Stats.spearman(x, y);   // IllegalArgumentException if constant
double d = Stats.ksStatistic(x, y);
```

---

## 11. How do I test capital stability under perturbed risk weights?

The validation stability check scales every GIRR delta RW and recomputes;
`with_girr_delta_rw_scaled` returns a modified copy (params are immutable).

**Python**

```python
from pathlib import Path
import frtb

data = Path("../data")
params = frtb.load_params(data / "sbm_params.json")
market = frtb.load_market(data / "curves.csv", data / "spots.csv")
desks = frtb.load_portfolio(data / "portfolio.json")
insts = [i for d in sorted(desks) for i in desks[d].instruments]
sens = frtb.compute_sensitivities(insts, market, params)

base = frtb.sbm_capital(sens, market, params).capital
up = frtb.sbm_capital(sens, market, params.with_girr_delta_rw_scaled(1.1)).capital
dn = frtb.sbm_capital(sens, market, params.with_girr_delta_rw_scaled(0.9)).capital
print(f"base {base:,.2f}  +10% -> {up - base:+,.2f}  -10% -> {dn - base:+,.2f}")
# +10% delta_capital = 67,904.33 (golden stability_girr_rw_up10); 1.28% relative
```

**C++**

```cpp
double base = frtb::sbm_capital(sens, market, params).capital;
double up = frtb::sbm_capital(sens, market, params.with_girr_delta_rw_scaled(1.1)).capital;
```

**Rust**

```rust
let base = sbm_capital(&sens, &market, &params)?.capital;
let up = sbm_capital(&sens, &market, &params.with_girr_delta_rw_scaled(1.1)?)?.capital;
```

**Java**

```java
double base = Sbm.capital(sens, market, params).capital();
double up = Sbm.capital(sens, market, params.withGirrDeltaRwScaled(1.1)).capital();
```

---

## 12. How do I run the validation checks and generate the report?

**Python**

```python
from pathlib import Path
import frtb
from frtb.validation import DeskCheckInputs

print(f"{frtb.benchmark_max_diff():.3e}")     # 5.163e-03 (BS vs binomial 501, tol 0.05)
print(f"{frtb.sensitivity_max_diff():.3e}")   # 3.423e-08 (delta vs FD, tol 1e-6)

# classify a constructed failure:
findings = frtb.classify_findings(DeskCheckInputs(
    benchmark_max_diff=0.2, sensitivity_max_diff=0.0, stability_rel_change=0.3,
    backtest_zone="amber", plat_zone="green", stale_days=20, gaps=1))
for f in findings:
    print(f.rule_id, f.severity, "-", f.description)
# BENCH-01 High, BT-02 Medium, STAB-01 Medium, DQ-01 Medium, DQ-02 Low
print(frtb.overall_verdict(findings))          # reject  (any High)

# the full report on the bundled data:
res = frtb.compute_results(Path("../data"))
Path("../validation_report.md").write_text(res["validation"]["report_md"])
```

**C++**

```cpp
#include "frtb/validation.hpp"

double bmd = frtb::benchmark_max_diff();                 // 5.163e-3
auto findings = frtb::classify_findings(inputs);         // frtb::DeskCheckInputs
std::string verdict = frtb::overall_verdict(findings);   // "reject" | ...
std::string md = frtb::render_report(results);
```

**Rust**

```rust
use frtb::{benchmark_max_diff, classify_findings, overall_verdict, render_report};

let bmd = benchmark_max_diff();
let findings = classify_findings(&inputs);
let verdict = overall_verdict(&findings);        // "approve" | "approve-with-conditions" | "reject"
let md = render_report(&results)?;
```

**Java**

```java
import com.quant.frtb.Validation;

double bmd = Validation.benchmarkMaxDiff();
var findings = Validation.classifyFindings(inputs);
String verdict = Validation.overallVerdict(findings);
String md = Validation.renderReport(results);
```

---

## 13. How do I verify my port against the golden values?

Every language's suite already does this; the pattern for a manual check:

**Python**

```python
import json
from pathlib import Path
import frtb

golden = json.loads(Path("../data/golden/golden.json").read_text())
res = frtb.compute_results(Path("../data"))

case = next(c for c in golden["cases"] if c["name"] == "sbm_firm_scenarios")
totals = res["sa"]["firm"].sbm.scenario_totals
for scen in ("high", "medium", "low"):
    assert abs(totals[scen] - case["expect"][scen]) <= case["tol"], scen
print("sbm_firm_scenarios OK")
```

**C++** — the tests parse the flat schema with a minimal hand-rolled reader
and `EXPECT_NEAR(actual, expect, tol)` per key.

**Rust** — `serde_json::from_str::<Golden>(...)` then
`assert!((actual - expect).abs() <= tol)`.

**Java** — the bundled minimal JSON parser class loads the file;
`assertEquals(expect, actual, tol)` per key; zone/verdict strings with
`assertEquals` exactly.
