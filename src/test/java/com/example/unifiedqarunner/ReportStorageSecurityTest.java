package com.example.unifiedqarunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportStorageSecurityTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsRegularReportInsideTrustedRoot() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("reports"));
        Path report = Files.createFile(root.resolve("index.html"));
        assertEquals(report.toRealPath(), ReportStorageSecurity.resolveRegularReportFile(root, report));
    }

    @Test
    void rejectsReportOutsideTrustedRoot() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("reports"));
        Path outside = Files.createFile(temporaryDirectory.resolve("secret.txt"));
        assertThrows(Exception.class, () -> ReportStorageSecurity.resolveRegularReportFile(root, outside));
    }

    @Test
    void rejectsSymlinkEscapeWhenSupported() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("reports"));
        Path outside = Files.createFile(temporaryDirectory.resolve("secret.html"));
        Path link = root.resolve("index.html");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException error) {
            return;
        }
        assertTrue(Files.isSymbolicLink(link));
        assertThrows(Exception.class, () -> ReportStorageSecurity.resolveRegularReportFile(root, link));
    }
}
