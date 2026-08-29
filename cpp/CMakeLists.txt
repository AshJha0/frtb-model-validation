# frtb — C++17 port of the FRTB & model-validation kit (P15).
# Targets: frtb (static lib), demo, frtb_tests (GoogleTest).

cmake_minimum_required(VERSION 3.16)
project(frtb LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_compile_options(-Wall -Wextra)
# The golden values are bit-tight (down to ~1 ulp): forbid FMA contraction so
# every floating-point operation rounds exactly like the Python reference.
add_compile_options(-ffp-contract=off)

# Bundled datasets live one level above the language directory.
set(FRTB_DATA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../data")

add_library(frtb
    src/market.cpp
    src/instruments.cpp
    src/pricers.cpp
    src/params.cpp
    src/sensitivities.cpp
    src/sbm.cpp
    src/sa.cpp
    src/stats.cpp
    src/ima.cpp
    src/plat.cpp
    src/validation.cpp
    src/engine.cpp
    src/report.cpp)
target_include_directories(frtb PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)

add_executable(demo src/demo.cpp)
target_link_libraries(demo PRIVATE frtb)
target_compile_definitions(demo PRIVATE
    FRTB_DATA_DIR="${FRTB_DATA_DIR}"
    FRTB_OUTPUT_DIR="${CMAKE_CURRENT_SOURCE_DIR}")

enable_testing()
find_package(GTest REQUIRED)

add_executable(frtb_tests
    tests/test_golden.cpp
    tests/test_pricers.cpp
    tests/test_sbm.cpp
    tests/test_drc_rrao.cpp
    tests/test_ima.cpp
    tests/test_plat_stats.cpp
    tests/test_sensitivities_engine.cpp
    tests/test_validation.cpp)
target_link_libraries(frtb_tests PRIVATE frtb GTest::gtest GTest::gtest_main)
target_compile_definitions(frtb_tests PRIVATE FRTB_DATA_DIR="${FRTB_DATA_DIR}")

include(GoogleTest)
gtest_discover_tests(frtb_tests)
