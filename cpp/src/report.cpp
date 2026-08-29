/// \file report.cpp
/// \brief Validation report generation (structured results -> markdown).

#include <cstdio>
#include <vector>

#include "frtb/engine.hpp"

namespace frtb {

namespace {

std::string sprintf_s(const char* spec, double v) {
    char buf[64];
    std::snprintf(buf, sizeof buf, spec, v);
    return buf;
}

/// Python's f"{x:,.2f}": fixed 2 decimals with thousands separators.
std::string fmt_comma(double x) {
    std::string s = sprintf_s("%.2f", x);
    bool negative = !s.empty() && s[0] == '-';
    std::size_t start = negative ? 1 : 0;
    std::size_t dot = s.find('.');
    std::string intpart = s.substr(start, dot - start);
    std::string frac = s.substr(dot);
    std::string grouped;
    int count = 0;
    for (std::size_t i = intpart.size(); i-- > 0;) {
        grouped.insert(grouped.begin(), intpart[i]);
        if (++count % 3 == 0 && i > 0) grouped.insert(grouped.begin(), ',');
    }
    return (negative ? "-" : "") + grouped + frac;
}

}  // namespace

std::string render_report(const Results& results) {
    const auto& ima = results.ima;
    const ValidationBlock& val = results.validation;
    std::vector<std::string> desks;  // sorted (std::map keys)
    for (const auto& kv : ima) desks.push_back(kv.first);

    std::vector<std::string> lines;
    auto add = [&lines](const std::string& line) { lines.push_back(line); };

    add("# Independent Model Validation Report");
    add("");
    add("> Educational FRTB implementation — Basel-2019-flavored pinned parameter set.");
    add("> NOT a compliant capital engine; for teaching and testing only.");
    add("");
    add("## " + REPORT_SECTIONS[0]);
    add("");
    {
        std::string joined;
        for (std::size_t i = 0; i < desks.size(); ++i)
            joined += (i ? ", " : "") + desks[i];
        add("Desks in scope: " + joined + ". Framework: SBM + DRC + RRAO (SA) and "
            "ES/IMCC + PLAT + backtesting + SES (IMA sketch).");
    }
    add("");

    add("## " + REPORT_SECTIONS[1]);
    add("");
    add("| metric | value | threshold | result |");
    add("|---|---|---|---|");
    const double bmd = val.benchmark_max_diff;
    add("| max abs diff BS vs binomial(" + std::to_string(BENCH_STEPS) + ") | " +
        sprintf_s("%.3e", bmd) + " | " + sprintf_s("%g", BENCH_TOL) + " | " +
        (bmd <= BENCH_TOL ? "PASS" : "FAIL") + " |");
    add("");

    add("## " + REPORT_SECTIONS[2]);
    add("");
    const double smd = val.sensitivity_max_diff;
    add("Analytic BS delta vs central finite difference: max abs diff " +
        sprintf_s("%.3e", smd) + " (threshold " + sprintf_s("%g", SENS_TOL) + ") — " +
        (smd <= SENS_TOL ? "PASS" : "FAIL") + ".");
    add("");

    add("## " + REPORT_SECTIONS[3]);
    add("");
    add("| scenario | SBM capital | change vs base |");
    add("|---|---|---|");
    const double base_cap = val.stability_base_capital;
    const double up_cap = val.stability_capital_rw_up10;
    const double dn_cap = val.stability_capital_rw_dn10;
    add("| base | " + fmt_comma(base_cap) + " | — |");
    add("| GIRR delta RW x1.1 | " + fmt_comma(up_cap) + " | " + fmt_comma(up_cap - base_cap) +
        " |");
    add("| GIRR delta RW x0.9 | " + fmt_comma(dn_cap) + " | " + fmt_comma(dn_cap - base_cap) +
        " |");
    add("");

    add("## " + REPORT_SECTIONS[4]);
    add("");
    add("| desk | exceptions | zone | multiplier |");
    add("|---|---|---|---|");
    for (const std::string& d : desks) {
        const BacktestResult& bt = ima.at(d).backtest;
        add("| " + d + " | " + std::to_string(bt.exceptions) + " | " + bt.zone + " | " +
            sprintf_s("%.2f", bt.multiplier) + " |");
    }
    add("");

    add("## " + REPORT_SECTIONS[5]);
    add("");
    add("| desk | spearman | KS | zone | surcharge |");
    add("|---|---|---|---|---|");
    for (const std::string& d : desks) {
        const PlatResult& pl = ima.at(d).plat;
        std::string sp = pl.spearman ? sprintf_s("%.4f", *pl.spearman) : "n/a";
        std::string ks = pl.ks ? sprintf_s("%.4f", *pl.ks) : "n/a";
        add("| " + d + " | " + sp + " | " + ks + " | " + pl.zone + " | " +
            fmt_comma(ima.at(d).plat_surcharge) + " |");
    }
    add("");

    add("## " + REPORT_SECTIONS[6]);
    add("");
    add("| desk | zero-change days | gaps |");
    add("|---|---|---|");
    for (const std::string& d : desks) {
        const DataQuality& dq = val.data_quality.at(d);
        add("| " + d + " | " + std::to_string(dq.stale_days) + " | " + std::to_string(dq.gaps) +
            " |");
    }
    add("");

    add("## " + REPORT_SECTIONS[7]);
    add("");
    add("| desk | SES |");
    add("|---|---|");
    for (const std::string& d : desks) add("| " + d + " | " + fmt_comma(ima.at(d).ses) + " |");
    add("");

    add("## " + REPORT_SECTIONS[8]);
    add("");
    bool any_finding = false;
    for (const std::string& d : desks)
        for (const Finding& f : val.findings.at(d)) {
            add("- **" + f.severity + "** [" + f.rule_id + "] (" + d + "): " + f.description);
            any_finding = true;
        }
    if (!any_finding) add("- No findings.");
    add("");

    add("## " + REPORT_SECTIONS[9]);
    add("");
    for (const std::string& d : desks) add("- " + d + ": **" + val.verdicts.at(d) + "**");
    add("");
    // "\n".join(lines) semantics — byte-identical to the Python reference.
    std::string out;
    for (std::size_t i = 0; i < lines.size(); ++i) out += (i ? "\n" : "") + lines[i];
    return out;
}

}  // namespace frtb
