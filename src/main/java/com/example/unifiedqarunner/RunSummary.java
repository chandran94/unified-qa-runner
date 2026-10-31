package com.example.unifiedqarunner;

import java.util.List;

record RunSummary(String suite, String target, int total, int passed, int failed,
                  int skipped, int exitCode, List<RunDetail> details) {
    RunSummary {
        total = Math.max(total, 0);
        passed = Math.max(passed, 0);
        failed = Math.max(failed, 0);
        skipped = Math.max(skipped, 0);
        if (details == null) {
            details = List.of();
        }
    }
}
