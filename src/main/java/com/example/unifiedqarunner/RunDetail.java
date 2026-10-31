package com.example.unifiedqarunner;

/**
 * Per-request or per-scenario detail captured from a test run.
 */
record RunDetail(String name, String status, String request, String response,
                 String errorMessage, String screenshotPath) {
}
