package com.example.unifiedqarunner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RunnerReportTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void writesEscapedHtmlAndFormulaSafeCsvAggregate() throws Exception {
        Path reportDirectory = temporaryDirectory.resolve("report");
        RunSummary summary = new RunSummary("<script>", "=HYPERLINK(\"x\")",
                3, 2, 1, 0, 1, java.util.List.of());

        ReportWriter.write(reportDirectory, summary);

        String html = Files.readString(reportDirectory.resolve("index.html"));
        String csv = Files.readString(reportDirectory.resolve("summary.csv"));
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(!html.contains("<script>"));
        assertTrue(csv.contains("'=HYPERLINK"));
        assertTrue(csv.contains("3,2,1,0,1"));
    }
}
