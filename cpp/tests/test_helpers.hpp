/// \file test_helpers.hpp
/// \brief Shared test fixtures: data directory paths and the (expensive)
/// end-to-end result tree, computed once per test binary.

#pragma once

#include <string>

#include "frtb/engine.hpp"

namespace frtb_test {

inline const std::string& data_dir() {
    static const std::string dir = FRTB_DATA_DIR;
    return dir;
}

/// Full engine results, computed once and shared by all test files.
inline const frtb::Results& results() {
    static const frtb::Results res = frtb::compute_results(data_dir());
    return res;
}

/// Pinned parameter set alone (cheap accessor for unit tests).
inline const frtb::SbmParams& params() { return results().params; }

}  // namespace frtb_test
