/// \file demo.cpp
/// \brief End-to-end FRTB demo: SBM capital breakdown per desk / risk class /
/// scenario, DRC-lite, RRAO, the IMA sketch (ES, IMCC, PLAT, backtesting,
/// SES) and the generated independent validation report.
///
/// Run: ./build/demo   (paths are baked in at configure time)

#include <cstdio>
#include <fstream>
#include <iostream>
#include <string>

#include "frtb/engine.hpp"

namespace {

std::string fmt(double x) {
    // "%14,.0f"-style: fixed 0 decimals with thousands separators, width 14.
    char raw[64];
    std::snprintf(raw, sizeof raw, "%.0f", x);
    std::string s = raw;
    bool neg = !s.empty() && s[0] == '-';
    std::string digits = neg ? s.substr(1) : s;
    std::string grouped;
    int count = 0;
    for (std::size_t i = digits.size(); i-- > 0;) {
        grouped.insert(grouped.begin(), digits[i]);
        if (++count % 3 == 0 && i > 0) grouped.insert(grouped.begin(), ',');
    }
    std::string out = (neg ? "-" : "") + grouped;
    while (out.size() < 14) out.insert(out.begin(), ' ');
    return out;
}

}  // namespace

int main() {
    std::printf("%s\n", std::string(76, '=').c_str());
    std::printf("FRTB & Model Validation demo  —  EDUCATIONAL parameter set "
                "(Basel-2019-flavored)\n");
    std::printf("%s\n", std::string(76, '=').c_str());

    frtb::Results res;
    try {
        res = frtb::compute_results(FRTB_DATA_DIR);
    } catch (const std::exception& e) {
        std::fprintf(stderr, "demo: %s\n", e.what());
        return 1;
    }

    // ---- SBM breakdown ----------------------------------------------------
    for (const std::string& scope : {std::string("desk1"), std::string("desk2"),
                                     std::string("firm")}) {
        std::string label = res.desks.count(scope) ? res.desks.at(scope).display
                                                   : std::string("FIRM (all desks)");
        std::printf("\n--- SBM: %s (%s) ----------------------------------\n", scope.c_str(),
                    label.c_str());
        std::printf("%-10s %-10s %14s %14s %14s\n", "risk class", "measure", "high", "medium",
                    "low");
        const frtb::SbmResult& s = res.sa.at(scope).sbm;
        for (const std::string& rc : frtb::RISK_CLASSES)
            for (const std::string& m : frtb::MEASURES) {
                const auto& row = s.charges.at(rc).at(m);
                std::printf("%-10s %-10s %s %s %s\n", rc.c_str(), m.c_str(),
                            fmt(row.at("high")).c_str(), fmt(row.at("medium")).c_str(),
                            fmt(row.at("low")).c_str());
            }
        const auto& st = s.scenario_totals;
        std::printf("%-10s %-10s %s %s %s\n", "TOTAL", "", fmt(st.at("high")).c_str(),
                    fmt(st.at("medium")).c_str(), fmt(st.at("low")).c_str());
        std::printf("SBM capital (max over scenarios): %.2f\n", s.capital);
        std::printf("DRC-lite: %.2f   (HBR = %.4f)   RRAO: %.2f\n", res.sa.at(scope).drc,
                    res.sa.at(scope).drc_hbr, res.sa.at(scope).rrao);
        std::printf("SA capital (SBM + DRC + RRAO):    %.2f\n", res.sa.at(scope).capital());
    }

    // ---- IMA sketch -------------------------------------------------------
    std::printf("\n--- IMA sketch (per desk) "
                "-------------------------------------------------\n");
    std::printf("%-7s %12s %12s %12s %4s %6s %5s %6s %10s %10s %12s\n", "desk", "ES base10d",
                "ES LH", "IMCC", "exc", "zone", "mult", "PLAT", "SES", "surchg", "capital");
    for (const auto& [d, i] : res.ima) {
        std::printf("%-7s %12.0f %12.0f %12.0f %4d %6s %5.2f %6s %10.0f %10.0f %12.0f\n",
                    d.c_str(), i.es_base, i.es_lh, i.imcc, i.backtest.exceptions,
                    i.backtest.zone.c_str(), i.backtest.multiplier, i.plat.zone.c_str(), i.ses,
                    i.plat_surcharge, i.capital);
        char spb[32] = "n/a", ksb[32] = "n/a";
        if (i.plat.spearman) std::snprintf(spb, sizeof spb, "%.4f", *i.plat.spearman);
        if (i.plat.ks) std::snprintf(ksb, sizeof ksb, "%.4f", *i.plat.ks);
        std::printf("        PLAT metrics: spearman = %s, KS = %s\n", spb, ksb);
    }

    // ---- validation -------------------------------------------------------
    const frtb::ValidationBlock& val = res.validation;
    std::printf("\n--- Independent validation "
                "------------------------------------------------\n");
    std::printf("benchmark BS vs binomial(501): max diff = %.3e (tol 0.05)\n",
                val.benchmark_max_diff);
    std::printf("delta vs finite difference:    max diff = %.3e (tol 1e-06)\n",
                val.sensitivity_max_diff);
    std::printf("stability: capital %.0f -> %.0f under +10%% GIRR RW (%.2f%% max move)\n",
                val.stability_base_capital, val.stability_capital_rw_up10,
                val.stability_rel_change * 100.0);
    for (const auto& [d, findings] : val.findings) {
        std::string rules;
        for (const frtb::Finding& f : findings) rules += (rules.empty() ? "" : ", ") + f.rule_id;
        if (rules.empty()) rules = "none";
        std::printf("%s: findings = %s  ->  verdict: %s\n", d.c_str(), rules.c_str(),
                    val.verdicts.at(d).c_str());
    }

    const std::string report_path = std::string(FRTB_OUTPUT_DIR) + "/validation_report.md";
    std::ofstream out(report_path);
    out << val.report_md;
    out.close();
    std::printf("\nvalidation_report.md written to %s\n", report_path.c_str());
    std::string verdict_line;
    for (const auto& [d, v] : val.verdicts)
        verdict_line += (verdict_line.empty() ? "" : ", ") + d + "=" + v;
    std::printf("Overall verdicts: %s\n", verdict_line.c_str());
    return 0;
}
