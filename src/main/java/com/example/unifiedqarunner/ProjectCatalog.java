package com.example.unifiedqarunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class ProjectCatalog {
    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("(?m)^\\s*package\\s+([A-Za-z0-9_.]+)\\s*;");

    private ProjectCatalog() {
    }

    static Map<ExecutionService.Kind, List<String>> discover(Path projectRoot)
            throws IOException {
        Map<ExecutionService.Kind, List<String>> catalog =
                new EnumMap<>(ExecutionService.Kind.class);
        for (ExecutionService.Kind kind : ExecutionService.Kind.values()) {
            catalog.put(kind, discover(projectRoot, kind));
        }
        return catalog;
    }

    private static List<String> discover(Path projectRoot, ExecutionService.Kind kind)
            throws IOException {
        List<String> selections = new ArrayList<>();
        if (kind == ExecutionService.Kind.SELENIUM_CLASS
                || kind == ExecutionService.Kind.REST_ASSURED_CLASS) {
            Path javaRoot = projectRoot.resolve("src/test/java");
            if (Files.isDirectory(javaRoot)) {
                try (var files = Files.walk(javaRoot)) {
                    for (Path file : files.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString()
                                    .matches(".*(?:Tests?|IT)\\.java"))
                            .toList()) {
                        String source = Files.readString(file);
                        boolean seleniumClass = source.contains("org.openqa.selenium");
                        boolean apiClass = source.contains("io.restassured")
                                || source.contains("ApiClient");
                        if (kind == ExecutionService.Kind.SELENIUM_CLASS && !seleniumClass
                                || kind == ExecutionService.Kind.REST_ASSURED_CLASS && !apiClass) {
                            continue;
                        }
                        var matcher = PACKAGE_PATTERN.matcher(source);
                        String packageName = matcher.find() ? matcher.group(1) + "." : "";
                        selections.add(packageName + file.getFileName().toString()
                                .replaceFirst("\\.java$", ""));
                    }
                }
            }
        } else if (kind == ExecutionService.Kind.NEWMAN_COLLECTION) {
            Path api = projectRoot.resolve("src/test/resources/api");
            if (Files.isDirectory(api)) {
                try (var files = Files.list(api)) {
                    files.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().toLowerCase()
                                    .contains("collection") && path.toString().endsWith(".json"))
                            .forEach(path -> selections.add(
                                    projectRoot.relativize(path).toString()
                                            .replace('\\', '/')));
                }
            }
        } else {
            Path featureRoot = projectRoot.resolve("src/test/resources/features");
            if (Files.isDirectory(featureRoot)) {
                try (var files = Files.list(featureRoot)) {
                    files.filter(Files::isRegularFile)
                            .filter(path -> path.toString().endsWith(".feature"))
                            .filter(path -> !path.getFileName().toString()
                                    .equals("security_ui.feature"))
                            .filter(path -> !path.getFileName().toString()
                                    .startsWith("amazon"))
                            .filter(path -> kind != ExecutionService.Kind.REST_ASSURED_FEATURE
                                    || path.getFileName().toString().equals("posts_api.feature"))
                            .forEach(path -> selections.add(
                                    projectRoot.relativize(path).toString()
                                            .replace('\\', '/')));
                }
            }
        }
        selections.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(selections);
    }
}
