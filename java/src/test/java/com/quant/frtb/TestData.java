package com.quant.frtb;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Shared fixtures for the test suite: bundled data dir, pinned params and a
 * single full engine run (the analogue of the Python session fixtures).
 */
final class TestData {

    static final Path DATA_DIR = Paths.get("..", "data");

    private static SbmParams params;
    private static Engine.Results results;

    private TestData() {
    }

    static synchronized SbmParams params() {
        if (params == null) {
            params = SbmParams.load(DATA_DIR.resolve("sbm_params.json"));
        }
        return params;
    }

    static synchronized Engine.Results results() {
        if (results == null) {
            results = Engine.computeResults(DATA_DIR);
        }
        return results;
    }
}
