//! Golden-value suite: every case in `../data/golden/golden.json` is
//! recomputed from the bundled inputs and must agree within its tolerance
//! (cross-language contract — the same file is asserted by the Python, C++
//! and Java suites).

mod common;

use std::collections::BTreeMap;

use frtb::sa::tenor_label;
use frtb::{aggregate_buckets, bucket_kb, compute_sensitivities, Results};
use serde_json::Value;

use common::{data_dir, results};

fn golden_cases() -> Vec<Value> {
    let path = data_dir().join("golden").join("golden.json");
    let text = std::fs::read_to_string(&path).expect("read golden.json");
    let doc: Value = serde_json::from_str(&text).expect("parse golden.json");
    let cases = doc["cases"].as_array().expect("cases list").clone();
    assert!(cases.len() >= 20, "expected at least 20 golden cases");
    cases
}

fn inp(case: &Value, key: &str) -> f64 {
    case["inputs"][key].as_f64().unwrap_or_else(|| panic!("missing input {key}"))
}

fn inp_str<'a>(case: &'a Value, key: &str) -> &'a str {
    case["inputs"][key].as_str().unwrap_or_else(|| panic!("missing input {key}"))
}

/// Recompute the expectation map for one golden case (mirrors the Python
/// test's `computed_values`).
fn computed_values(name: &str, case: &Value, res: &Results) -> BTreeMap<String, Value> {
    let mut out: BTreeMap<String, Value> = BTreeMap::new();
    fn put(out: &mut BTreeMap<String, Value>, k: &str, v: f64) {
        out.insert(k.to_string(), serde_json::json!(v));
    }
    match name {
        "sbm_agg_hand_2bucket" => {
            let (ws_a1, ws_a2, rho_a) = (inp(case, "ws_a1"), inp(case, "ws_a2"), inp(case, "rho_a"));
            let (ws_b1, gamma) = (inp(case, "ws_b1"), inp(case, "gamma"));
            let k_a = bucket_kb(&[ws_a1, ws_a2], |_, _| Ok(rho_a)).unwrap();
            let k_b = bucket_kb(&[ws_b1], |_, _| Ok(0.0)).unwrap();
            let kb = BTreeMap::from([("A".to_string(), k_a), ("B".to_string(), k_b)]);
            let ws_sum =
                BTreeMap::from([("A".to_string(), ws_a1 + ws_a2), ("B".to_string(), ws_b1)]);
            let agg = aggregate_buckets(&kb, &ws_sum, |_, _| Ok(gamma)).unwrap();
            put(&mut out, "k_a", k_a);
            put(&mut out, "k_b", k_b);
            put(&mut out, "total", agg.charge);
        }
        "girr_ws_rates_desk" => {
            let desk = &res.desks[inp_str(case, "desk")];
            let ccy = inp_str(case, "currency");
            let sens =
                compute_sensitivities(&desk.instruments, &res.market, &res.params).unwrap();
            let ladder = &sens.girr[ccy];
            for (i, &s) in ladder.iter().enumerate() {
                let t = res.params.girr_tenors[i];
                out.insert(
                    format!("ws_{}", tenor_label(t)),
                    serde_json::json!(res.params.girr_delta_rw[i] * s),
                );
            }
        }
        "girr_kb_usd" => put(&mut out, "kb", res.sa["firm"].sbm.kb_medium["girr"]["delta"]["USD"]),
        "equity_kb_bucket1" => put(&mut out, "kb", res.sa["firm"].sbm.kb_medium["equity"]["delta"]["1"]),
        "sbm_firm_scenarios" => {
            for scen in ["high", "medium", "low"] {
                put(&mut out, scen, res.sa["firm"].sbm.scenario_totals[scen]);
            }
            put(&mut out, "capital", res.sa["firm"].sbm.capital);
        }
        "sbm_desk_capitals" => {
            put(&mut out, "desk1", res.sa["desk1"].sbm.capital);
            put(&mut out, "desk2", res.sa["desk2"].sbm.capital);
        }
        "curvature_equity_desk2" => {
            put(&mut out, "charge", res.sa["desk2"].sbm.charge("equity", "curvature", "medium"));
        }
        "drc_firm" => {
            put(&mut out, "charge", res.sa["firm"].drc);
            put(&mut out, "hbr", res.sa["firm"].drc_hbr);
        }
        "rrao_firm" => put(&mut out, "charge", res.sa["firm"].rrao),
        "es_desk1" | "es_desk2" => {
            let d = inp_str(case, "desk");
            put(&mut out, "es_base", res.ima[d].es_base);
            put(&mut out, "es_lh", res.ima[d].es_lh);
        }
        "imcc_desks" => {
            put(&mut out, "desk1", res.ima["desk1"].imcc);
            put(&mut out, "desk2", res.ima["desk2"].imcc);
        }
        "plat_desk1" | "plat_desk2" => {
            let pl = &res.ima[inp_str(case, "desk")].plat;
            put(&mut out, "spearman", pl.spearman.expect("defined spearman"));
            put(&mut out, "ks", pl.ks.expect("defined ks"));
            out.insert("zone".to_string(), serde_json::json!(pl.zone));
        }
        "backtest_desk1" | "backtest_desk2" => {
            let bt = &res.ima[inp_str(case, "desk")].backtest;
            out.insert("exceptions".to_string(), serde_json::json!(bt.exceptions));
            put(&mut out, "multiplier", bt.multiplier);
        }
        "ses_firm" => put(&mut out, "charge", res.ima["desk1"].ses + res.ima["desk2"].ses),
        "benchmark_max_diff" => put(&mut out, "value", res.validation.benchmark_max_diff),
        "stability_girr_rw_up10" => {
            put(
                &mut out,
                "delta_capital",
                res.validation.stability_capital_rw_up10 - res.validation.stability_base_capital,
            );
        }
        "verdict_desk1" | "verdict_desk2" => {
            let v = &res.validation.verdicts[inp_str(case, "desk")];
            out.insert("verdict".to_string(), serde_json::json!(v));
        }
        other => panic!("golden case '{other}' has no recompute mapping"),
    }
    out
}

#[test]
fn all_golden_cases() {
    let res = results();
    for case in golden_cases() {
        let name = case["name"].as_str().expect("case name");
        let got = computed_values(name, &case, res);
        let expect = case["expect"].as_object().expect("expect object");
        let tol = case["tol"].as_f64().unwrap_or(0.0);
        assert_eq!(
            got.keys().collect::<Vec<_>>(),
            expect.keys().collect::<Vec<_>>(),
            "key mismatch in {name}"
        );
        for (key, want) in expect {
            let g = &got[key];
            match want {
                Value::String(s) => {
                    assert_eq!(g.as_str().unwrap(), s, "{name}.{key}");
                }
                _ if want.is_i64() && g.is_i64() || g.is_u64() && want.is_i64() => {
                    assert_eq!(
                        g.as_i64().unwrap_or_else(|| g.as_u64().unwrap() as i64),
                        want.as_i64().unwrap(),
                        "{name}.{key}"
                    );
                }
                _ => {
                    let gv = g.as_f64().unwrap();
                    let wv = want.as_f64().unwrap();
                    assert!(gv.is_finite(), "{name}.{key} not finite");
                    assert!(
                        (gv - wv).abs() <= tol,
                        "{name}.{key}: {gv:?} vs {wv:?} (tol {tol:e}, diff {:e})",
                        (gv - wv).abs()
                    );
                }
            }
        }
    }
}

#[test]
fn golden_schema_flat_scalars() {
    // Cross-language contract: inputs/expect values are flat scalars only.
    for case in golden_cases() {
        for section in ["inputs", "expect"] {
            for (k, v) in case[section].as_object().expect("object") {
                assert!(
                    v.is_number() || v.is_string(),
                    "{}: non-scalar {section}.{k}",
                    case["name"]
                );
            }
        }
    }
}
