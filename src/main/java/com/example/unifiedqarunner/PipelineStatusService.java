package com.example.unifiedqarunner;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Polls CI/CD APIs for pipeline run history.
 * Supports GitHub Actions and Jenkins.
 * Results are cached for 5 minutes to avoid rate limiting.
 */
public class PipelineStatusService {

    private static final long CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutes
    private final Map<String, CachedResult> cache = new ConcurrentHashMap<>();

    private record CachedResult(List<CiCdConfig.PipelineRun> runs, long timestamp) {}

    /**
     * Get recent pipeline runs from the configured platform.
     */
    @SuppressWarnings("unchecked")
    public List<CiCdConfig.PipelineRun> getRuns(CiCdConfig.CiCdSettings settings) {
        if (settings == null) {
            return List.of();
        }
        String cacheKey = settings.platform() + ":" + (settings.repoUrl() + settings.jenkinsUrl());
        CachedResult cached = cache.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - cached.timestamp() < CACHE_TTL_MS) {
            return cached.runs();
        }

        List<CiCdConfig.PipelineRun> runs;
        if ("github".equals(settings.platform())) {
            String[] parts = parseRepoUrl(settings.repoUrl());
            runs = getGitHubActionsRuns(parts[0], parts[1], settings.token());
        } else if ("jenkins".equals(settings.platform())) {
            runs = getJenkinsBuilds(settings.jenkinsUrl(), "qa-runner", settings.token());
        } else {
            runs = List.of();
        }
        cache.put(cacheKey, new CachedResult(runs, System.currentTimeMillis()));
        return runs;
    }

    public void clearCache() {
        cache.clear();
    }

    /**
     * Fetch GitHub Actions workflow runs.
     */
    @SuppressWarnings("unchecked")
    List<CiCdConfig.PipelineRun> getGitHubActionsRuns(String owner, String repo, String token) {
        List<CiCdConfig.PipelineRun> runs = new ArrayList<>();
        if (owner == null || repo == null || token.isEmpty()) {
            return runs;
        }
        try {
            String url = "https://api.github.com/repos/" + owner + "/" + repo + "/actions/runs?per_page=10";
            java.net.HttpURLConnection conn = openUrl(url);
            conn.setRequestProperty("Authorization", "token " + token);
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            conn.setRequestProperty("User-Agent", "Unified-QA-Runner");
            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                return runs;
            }

            String response = readResponse(conn);
            Object parsed = JsonParser.parse(response);
            if (!(parsed instanceof Map<?, ?> root)) {
                return runs;
            }
            Object workflowRunsObj = root.get("workflow_runs");
            if (!(workflowRunsObj instanceof List<?> workflowRuns)) {
                return runs;
            }

            for (Object runObj : workflowRuns) {
                if (!(runObj instanceof Map<?, ?> run)) continue;
                String id = String.valueOf(run.get("id"));
                String status = stringVal(run, "status", "unknown");
                String conclusion = stringVal(run, "conclusion", "");
                String name = stringVal(run, "name", stringVal(run, "display_title", "QA Runner"));
                String htmlUrl = stringVal(run, "html_url", "");
                String createdAt = stringVal(run, "created_at", "");

                long duration = 0;
                try {
                    String updatedAt = stringVal(run, "updated_at", "");
                    if (!createdAt.isEmpty() && !updatedAt.isEmpty()) {
                        Instant start = Instant.parse(createdAt);
                        Instant end = Instant.parse(updatedAt);
                        duration = java.time.Duration.between(start, end).getSeconds();
                    }
                } catch (Exception ignored) {}

                runs.add(new CiCdConfig.PipelineRun(id, status, conclusion, duration, htmlUrl, createdAt, name));
            }
        } catch (Exception e) {
            // Silently fail
        }
        return runs;
    }

    /**
     * Fetch job-level details for a specific GitHub Actions run.
     * Returns a list of RunDetail objects representing each job/step result.
     */
    @SuppressWarnings("unchecked")
    public List<RunDetail> getRunJobs(String owner, String repo, String runId, String token) {
        List<RunDetail> details = new ArrayList<>();
        if (owner == null || repo == null || runId == null || token.isEmpty()) {
            return details;
        }
        try {
            String url = "https://api.github.com/repos/" + owner + "/" + repo + "/actions/runs/" + runId + "/jobs";
            java.net.HttpURLConnection conn = openUrl(url);
            conn.setRequestProperty("Authorization", "token " + token);
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            conn.setRequestProperty("User-Agent", "Unified-QA-Runner");
            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                return details;
            }

            String response = readResponse(conn);
            Object parsed = JsonParser.parse(response);
            if (!(parsed instanceof Map<?, ?> root)) {
                return details;
            }
            Object jobsObj = root.get("jobs");
            if (!(jobsObj instanceof List<?> jobs)) {
                return details;
            }

            for (Object jobObj : jobs) {
                if (!(jobObj instanceof Map<?, ?> job)) continue;
                String jobName = stringVal(job, "name", "Unknown Job");
                String conclusion = stringVal(job, "conclusion", "");

                // Check steps for more granular results
                Object stepsObj = job.get("steps");
                if (stepsObj instanceof List<?> steps) {
                    for (Object stepObj : steps) {
                        if (!(stepObj instanceof Map<?, ?> step)) continue;
                        String stepName = stringVal(step, "name", jobName);
                        String stepStatus = stringVal(step, "status", "");
                        String stepConclusion = stringVal(step, "conclusion", "");

                        // Normalize status
                        String status = normalizeGitHubStatus(stepConclusion, stepStatus);
                        details.add(new RunDetail(stepName, status, stepName, null, null, null));
                    }
                } else {
                    // No steps — use job-level result
                    String status = normalizeGitHubStatus(conclusion, "");
                    details.add(new RunDetail(jobName, status, jobName, null, null, null));
                }
            }
        } catch (Exception e) {
            // Silently fail
        }
        return details;
    }

    /**
     * Normalize GitHub Actions status/conclusion to PASS/FAIL/SKIP/NOT_RUN.
     */
    private String normalizeGitHubStatus(String conclusion, String status) {
        if (conclusion != null && !conclusion.isEmpty()) {
            switch (conclusion.toLowerCase()) {
                case "success": return "PASSED";
                case "failure": return "FAILED";
                case "skipped": return "SKIPPED";
                case "cancelled": return "SKIPPED";
                default: return "NOT_RUN";
            }
        }
        if (status != null && !status.isEmpty()) {
            switch (status.toLowerCase()) {
                case "completed": return "NOT_RUN";
                case "in_progress": return "NOT_RUN";
                case "queued": return "NOT_RUN";
                default: return "NOT_RUN";
            }
        }
        return "NOT_RUN";
    }

    /**
     * Fetch Jenkins build history.
     */
    @SuppressWarnings("unchecked")
    List<CiCdConfig.PipelineRun> getJenkinsBuilds(String jenkinsUrl, String jobName, String token) {
        List<CiCdConfig.PipelineRun> runs = new ArrayList<>();
        if (jenkinsUrl == null || jenkinsUrl.isEmpty()) {
            return runs;
        }
        try {
            String url = jenkinsUrl + "/job/" + jobName + "/api/json?tree=builds[number,result,duration,timestamp]";
            java.net.HttpURLConnection conn = openUrl(url);
            if (!token.isEmpty()) {
                String encoded = java.util.Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
                conn.setRequestProperty("Authorization", "Basic " + encoded);
            }
            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                return runs;
            }

            String response = readResponse(conn);
            Object parsed = JsonParser.parse(response);
            if (!(parsed instanceof Map<?, ?> root)) {
                return runs;
            }
            Object buildsObj = root.get("builds");
            if (!(buildsObj instanceof List<?> builds)) {
                return runs;
            }

            for (Object buildObj : builds) {
                if (!(buildObj instanceof Map<?, ?> build)) continue;
                int number = intVal(build, "number", 0);
                String result = stringVal(build, "result", "");
                long duration = longVal(build, "duration", 0);
                Object tsObj = build.get("timestamp");
                String timestampStr = "";
                if (tsObj instanceof Number ts) {
                    timestampStr = Instant.ofEpochMilli(ts.longValue()).toString();
                }

                String status = "completed";
                String conclusion = "";
                if (result.isEmpty()) {
                    status = "running";
                } else if ("SUCCESS".equals(result)) {
                    conclusion = "success";
                } else if ("FAILURE".equals(result)) {
                    conclusion = "failure";
                } else if ("ABORTED".equals(result)) {
                    conclusion = "cancelled";
                } else {
                    conclusion = result.toLowerCase();
                }

                String buildUrl = jenkinsUrl + "/job/" + jobName + "/" + number + "/";
                runs.add(new CiCdConfig.PipelineRun(
                        String.valueOf(number),
                        status,
                        conclusion,
                        duration / 1000,
                        buildUrl,
                        timestampStr,
                        jobName
                ));
            }
        } catch (Exception e) {
            // Silently fail
        }
        return runs;
    }

    /**
     * Get the latest pipeline status for the sidebar badge.
     */
    public Map<String, Object> getLatestStatus(CiCdConfig.CiCdSettings settings) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<CiCdConfig.PipelineRun> runs = getRuns(settings);
        if (runs.isEmpty()) {
            result.put("badge", "⏳");
            result.put("status", "no_runs");
            result.put("message", "No pipeline runs yet");
            result.put("runs", runs);
            return result;
        }
        CiCdConfig.PipelineRun latest = runs.get(0);
        if ("success".equals(latest.conclusion()) || "completed".equals(latest.status())) {
            result.put("badge", "✅");
            result.put("status", "passed");
            result.put("message", "Last run passed");
        } else if ("failure".equals(latest.conclusion())) {
            result.put("badge", "❌");
            result.put("status", "failed");
            result.put("message", "Last run failed");
        } else {
            result.put("badge", "⏳");
            result.put("status", "running");
            result.put("message", "Pipeline running");
        }
        result.put("runs", toRunList(latest));
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toRunList(CiCdConfig.PipelineRun run) {
        List<Map<String, Object>> list = new ArrayList<>();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", run.id());
        m.put("status", run.status());
        m.put("conclusion", run.conclusion());
        m.put("duration", run.duration());
        m.put("url", run.url());
        m.put("timestamp", run.timestamp());
        m.put("workflowName", run.workflowName());
        list.add(m);
        return list;
    }

    private String[] parseRepoUrl(String repoUrl) {
        if (repoUrl == null || repoUrl.isEmpty()) {
            return new String[]{null, null};
        }
        String clean = repoUrl.replace("https://github.com/", "")
                .replace("git@github.com:", "")
                .replace(".git", "")
                .trim();
        int slash = clean.lastIndexOf('/');
        if (slash > 0) {
            return new String[]{clean.substring(0, slash), clean.substring(slash + 1)};
        }
        return new String[]{null, clean};
    }

    private java.net.HttpURLConnection openUrl(String urlString) throws Exception {
        java.net.URL url = java.net.URI.create(urlString).toURL();
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        return conn;
    }

    private String readResponse(java.net.HttpURLConnection conn) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static String stringVal(Map<?, ?> map, String key, String defaultVal) {
        Object val = map.get(key);
        return val instanceof String s ? s : defaultVal;
    }

    @SuppressWarnings("unchecked")
    private static int intVal(Map<?, ?> map, String key, int defaultVal) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        return defaultVal;
    }

    @SuppressWarnings("unchecked")
    private static long longVal(Map<?, ?> map, String key, long defaultVal) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.longValue();
        return defaultVal;
    }
}
