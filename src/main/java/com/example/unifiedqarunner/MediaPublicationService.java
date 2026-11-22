package com.example.unifiedqarunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Host-managed capture staging for Selenium, Playwright, Cypress, WebdriverIO and local producers. */
final class MediaPublicationService {
    enum MediaType { SCREENSHOT, VIDEO }

    private final Path projectRoot;
    private final Path staging;

    private MediaPublicationService(Path projectRoot, Path staging) {
        this.projectRoot = projectRoot;
        this.staging = staging;
    }

    static MediaPublicationService open(Path projectRoot, String executionId) throws IOException {
        return new MediaPublicationService(projectRoot.toAbsolutePath().normalize(),
                ArtifactPublicationService.createStagingDirectory(projectRoot, "media-" + executionId));
    }

    Path stagingFile(String fileName) throws IOException {
        if (fileName == null || !fileName.matches("[A-Za-z0-9._-]{1,160}")) {
            throw new IOException("Unsafe media file name.");
        }
        return staging.resolve(fileName).normalize();
    }

    void publish(Path completedCapture, MediaType type, String fileName) throws IOException {
        Path expected = stagingFile(fileName);
        if (!expected.equals(completedCapture.toAbsolutePath().normalize())) {
            throw new IOException("Capture must be inside this execution staging directory.");
        }
        ArtifactPublicationService.publishMedia(projectRoot, expected, type.name(), fileName);
    }

    void discard() {
        ArtifactPublicationService.discardStaging(staging);
    }

    Path stagingDirectory() { return staging; }
}
