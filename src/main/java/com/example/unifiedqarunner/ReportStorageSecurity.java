package com.example.unifiedqarunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Resolves report files only when their real path remains under the trusted report root. */
final class ReportStorageSecurity {
    private ReportStorageSecurity() {
    }

    static Path resolveRegularReportFile(Path trustedRoot, Path candidate) throws IOException {
        Path realRoot = trustedRoot.toAbsolutePath().normalize().toRealPath();
        Path realCandidate = candidate.toAbsolutePath().normalize().toRealPath();
        if (!realCandidate.startsWith(realRoot) || !Files.isRegularFile(realCandidate)) {
            throw new IOException("Report file is outside the trusted report storage root.");
        }
        return realCandidate;
    }
}
