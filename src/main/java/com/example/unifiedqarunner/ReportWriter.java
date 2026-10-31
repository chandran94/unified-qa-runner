package com.example.unifiedqarunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class ReportWriter {
    private ReportWriter() {
    }

    static void write(Path directory, RunSummary summary) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("summary.csv"), csv(summary), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("index.html"), html(summary), StandardCharsets.UTF_8);
    }

    private static String csv(RunSummary summary) {
        return "suite,target,total,passed,failed,skipped,exitCode\r\n"
                + csvCell(summary.suite()) + "," + csvCell(summary.target()) + ","
                + summary.total() + "," + summary.passed() + "," + summary.failed() + ","
                + summary.skipped() + "," + summary.exitCode() + "\r\n";
    }

    private static String csvCell(String value) {
        if (!value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) {
            value = "'" + value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String html(RunSummary summary) {
        String detailsTable;
        if (summary.details().isEmpty()) {
            detailsTable = "<p>Only aggregate counts are available for this run.</p>";
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("<h2>Execution details</h2>\n");
            sb.append("<table>\n<thead><tr>\n");
            sb.append("  <th>#</th><th>Name</th><th>Status</th><th>Request</th><th>Response</th><th>Error</th>\n");
            sb.append("</tr></thead><tbody>\n");
            int i = 1;
            for (RunDetail detail : summary.details()) {
                String statusClass = "FAILED".equals(detail.status()) ? "color:#b32b3b"
                        : "SKIPPED".equals(detail.status()) ? "color:#a96500" : "color:#16845b";
                sb.append("<tr>\n");
                sb.append("  <td>").append(i++).append("</td>\n");
                sb.append("  <td>").append(escapeHtml(detail.name())).append("</td>\n");
                sb.append("  <td style=\"").append(statusClass).append(";font-weight:700\">")
                        .append(escapeHtml(detail.status())).append("</td>\n");
                sb.append("  <td>").append(escapeHtml(detail.request() != null ? detail.request() : "")).append("</td>\n");
                sb.append("  <td><pre style=\"margin:0;white-space:pre-wrap\">")
                        .append(escapeHtml(detail.response() != null ? detail.response() : ""))
                        .append("</pre></td>\n");
                sb.append("  <td><pre style=\"margin:0;white-space:pre-wrap;color:#b32b3b\">")
                        .append(escapeHtml(detail.errorMessage() != null ? detail.errorMessage() : ""))
                        .append("</pre></td>\n");
                sb.append("</tr>\n");
            }
            sb.append("</tbody></table>\n");
            detailsTable = sb.toString();
        }
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>Unified QA Runner report</title>
                  <style>
                    body{font:16px system-ui,sans-serif;margin:3rem;color:#172033}
                    table{border-collapse:collapse;min-width:34rem;margin-bottom:2rem}
                    th,td{border:1px solid #ccd3df;padding:.65rem;text-align:left;vertical-align:top}
                    th{background:#f1f4f8}
                    pre{font:inherit;margin:0;padding:0}
                  </style>
                </head>
                <body>
                  <h1>Unified QA Runner summary</h1>
                  <table>
                    <tbody>
                      <tr><th>Suite</th><td>%s</td></tr>
                      <tr><th>Target</th><td>%s</td></tr>
                      <tr><th>Total</th><td>%d</td></tr>
                      <tr><th>Passed</th><td>%d</td></tr>
                      <tr><th>Failed</th><td>%d</td></tr>
                      <tr><th>Skipped</th><td>%d</td></tr>
                      <tr><th>Process exit code</th><td>%d</td></tr>
                    </tbody>
                  </table>
                  %s
                </body>
                </html>
                """.formatted(escapeHtml(summary.suite()), escapeHtml(summary.target()),
                summary.total(), summary.passed(), summary.failed(), summary.skipped(),
                summary.exitCode(), detailsTable);
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
