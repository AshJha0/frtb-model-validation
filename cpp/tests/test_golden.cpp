/// Golden-value suite: every case in data/golden/golden.json is recomputed
/// from the bundled inputs and must agree within its tolerance (cross-language
/// contract — the same file is asserted by the Python/Rust/Java suites).

#include <gtest/gtest.h>

#include <cmath>
#include <cstdio>
#include <map>
#include <string>
#include <variant>

#include "frtb/json.hpp"
#include "frtb/sbm.hpp"
#include "test_helpers.hpp"

namespace {

using frtb::json::Value;

/// A golden expectation value: number or exact string.
using GoldenValue = std::variant<double, std::string>;

std::string fmt_g(double t) {
    char buf[32];
    std::snprintf(buf, sizeof buf, "%g", t);
    return buf;
}

const Value& golden_doc() {
    static const Value doc = frtb::json::parse_file(frtb_test::data_dir() + "/golden/golden.json");
    return doc;
}

/// Recompute the expectation map for one golden case.
std::map<std::string, GoldenValue> computed_values(const std::string& name, const Value& inputs) {
    const frtb::Results& res = frtb_test::results();
    std::map<std::string, GoldenValue> out;

    if (name == "sbm_agg_hand_2bucket") {
        const double ws_a1 = inputs.at("ws_a1").as_number();
        const double ws_a2 = inputs.at("ws_a2").as_number();
        const double rho_a = inputs.at("rho_a").as_number();
        const double ws_b1 = inputs.at("ws_b1").as_number();
        const double gamma = inputs.at("gamma").as_number();
        double k_a = frtb::bucket_kb({ws_a1, ws_a2}, [rho_a](std::size_t, std::size_t) {
            return rho_a;
        });
        double k_b = frtb::bucket_kb({ws_b1}, [](std::size_t, std::size_t) { return 0.0; });
        frtb::AggregateResult agg = frtb::aggregate_buckets(
            {{"A", k_a}, {"B", k_b}}, {{"A", ws_a1 + ws_a2}, {"B", ws_b1}},
            [gamma](const std::string&, const std::string&) { return gamma; });
        out["k_a"] = k_a;
        out["k_b"] = k_b;
        out["total"] = agg.charge;
        return out;
    }
    if (name == "girr_ws_rates_desk") {
        const std::string desk = inputs.at("desk").as_string();
        const std::string ccy = inputs.at("currency").as_string();
        frtb::Sensitivities sens = frtb::compute_sensitivities(
            res.desks.at(desk).instruments, res.market, res.params);
        for (const auto& [t, s] : sens.girr.at(ccy))
            out["ws_" + fmt_g(t)] = res.params.girr_rw(t) * s;
        return out;
    }
    if (name == "girr_kb_usd") {
        out["kb"] = res.sa.at("firm").sbm.kb_medium.at("girr").at("delta").at("USD");
        return out;
    }
    if (name == "equity_kb_bucket1") {
        out["kb"] = res.sa.at("firm").sbm.kb_medium.at("equity").at("delta").at("1");
        return out;
    }
    if (name == "sbm_firm_scenarios") {
        for (const auto& [scen, total] : res.sa.at("firm").sbm.scenario_totals)
            out[scen] = total;
        out["capital"] = res.sa.at("firm").sbm.capital;
        return out;
    }
    if (name == "sbm_desk_capitals") {
        out["desk1"] = res.sa.at("desk1").sbm.capital;
        out["desk2"] = res.sa.at("desk2").sbm.capital;
        return out;
    }
    if (name == "curvature_equity_desk2") {
        out["charge"] = res.sa.at("desk2").sbm.charges.at("equity").at("curvature").at("medium");
        return out;
    }
    if (name == "drc_firm") {
        out["charge"] = res.sa.at("firm").drc;
        out["hbr"] = res.sa.at("firm").drc_hbr;
        return out;
    }
    if (name == "rrao_firm") {
        out["charge"] = res.sa.at("firm").rrao;
        return out;
    }
    if (name == "es_desk1" || name == "es_desk2") {
        const std::string desk = inputs.at("desk").as_string();
        out["es_base"] = res.ima.at(desk).es_base;
        out["es_lh"] = res.ima.at(desk).es_lh;
        return out;
    }
    if (name == "imcc_desks") {
        out["desk1"] = res.ima.at("desk1").imcc;
        out["desk2"] = res.ima.at("desk2").imcc;
        return out;
    }
    if (name == "plat_desk1" || name == "plat_desk2") {
        const frtb::PlatResult& pl = res.ima.at(inputs.at("desk").as_string()).plat;
        out["spearman"] = pl.spearman.value();
        out["ks"] = pl.ks.value();
        out["zone"] = pl.zone;
        return out;
    }
    if (name == "backtest_desk1" || name == "backtest_desk2") {
        const frtb::BacktestResult& bt = res.ima.at(inputs.at("desk").as_string()).backtest;
        out["exceptions"] = static_cast<double>(bt.exceptions);
        out["multiplier"] = bt.multiplier;
        return out;
    }
    if (name == "ses_firm") {
        out["charge"] = res.ima.at("desk1").ses + res.ima.at("desk2").ses;
        return out;
    }
    if (name == "benchmark_max_diff") {
        out["value"] = res.validation.benchmark_max_diff;
        return out;
    }
    if (name == "stability_girr_rw_up10") {
        out["delta_capital"] =
            res.validation.stability_capital_rw_up10 - res.validation.stability_base_capital;
        return out;
    }
    if (name == "verdict_desk1" || name == "verdict_desk2") {
        out["verdict"] = res.validation.verdicts.at(inputs.at("desk").as_string());
        return out;
    }
    ADD_FAILURE() << "golden case '" << name << "' has no recompute mapping";
    return out;
}

TEST(Golden, AllCases) {
    const Value& doc = golden_doc();
    const auto& cases = doc.at("cases").array;
    ASSERT_GE(cases.size(), 20u);
    for (const Value& c : cases) {
        const std::string name = c.at("name").as_string();
        SCOPED_TRACE("golden case: " + name);
        std::map<std::string, GoldenValue> got = computed_values(name, c.at("inputs"));
        const Value& expect = c.at("expect");
        const double tol = c.at("tol").as_number();
        ASSERT_EQ(got.size(), expect.object.size());
        for (const auto& [key, want] : expect.object) {
            SCOPED_TRACE("key: " + key);
            ASSERT_TRUE(got.count(key));
            const GoldenValue& g = got.at(key);
            if (want.is_string()) {
                ASSERT_TRUE(std::holds_alternative<std::string>(g));
                EXPECT_EQ(std::get<std::string>(g), want.as_string());
            } else {
                ASSERT_TRUE(std::holds_alternative<double>(g));
                const double gv = std::get<double>(g);
                ASSERT_TRUE(std::isfinite(gv));
                EXPECT_NEAR(gv, want.as_number(), tol);
            }
        }
    }
}

TEST(Golden, SchemaFlatScalars) {
    // Cross-language contract: inputs/expect values are flat scalars only.
    for (const Value& c : golden_doc().at("cases").array) {
        for (const char* section : {"inputs", "expect"}) {
            for (const auto& [k, v] : c.at(section).object) {
                (void)k;
                EXPECT_TRUE(v.type == Value::Type::Number || v.type == Value::Type::String)
                    << c.at("name").as_string() << "." << section;
            }
        }
    }
}

}  // namespace
