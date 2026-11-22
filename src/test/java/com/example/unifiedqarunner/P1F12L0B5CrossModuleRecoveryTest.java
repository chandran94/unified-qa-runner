package com.example.unifiedqarunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Isolated recovery attempt for the supported local stores. External runner
 * execution is intentionally not invoked; unsupported producers remain a
 * reported limitation rather than being represented by hand-written results.
 */
class P1F12L0B5CrossModuleRecoveryTest {
    @Test
    void supportedCrossModuleGraphBacksUpAndRestoresWithoutLiveResources() throws Exception {
        Path root = Files.createTempDirectory("qa-f12l0b5-root-");
        Path backups = Files.createTempDirectory("qa-f12l0b5-backups-");
        try {
            OrganizationStore organizations = new OrganizationStore(root);
            String owner = UUID.randomUUID().toString();
            Organization organization = organizations.createOrganization("E2E Org", "e2e-" + owner.substring(0, 8), owner);

            UserStoryStore stories = new UserStoryStore(root);
            UserStoryStore.Project project = stories.createProject("REC", "Recovery project", "isolated", owner);
            organizations.assignProject(project.id(), organization.organizationId(), "TEST_FIXTURE");
            UserStoryStore.Sprint sprint = stories.createSprint(project.id(), "Recovery sprint", "isolated", "2026-11-15", "2026-11-30");
            UserStoryStore.Story story = stories.createStory(project.id(), null, "Recoverable story", "description", "actor",
                    "action", "result", "criteria", "High", "To Do", 3, "owner", owner);
            stories.assignStoryToSprint(sprint.id(), story.id());

            WikiManager wiki = new WikiManager(root);
            WikiManager.Space space = wiki.createSpace("Recovery docs", "RECOVERY", "isolated", owner);
            Map<String, Object> pageValues = new LinkedHashMap<>();
            pageValues.put("spaceId", space.id());
            pageValues.put("title", "Recovery page");
            pageValues.put("content", "synthetic recovery content");
            WikiManager.Page page = wiki.createPage(pageValues, owner);

            TestCaseManager testCases = new TestCaseManager(root.resolve("qa-runner.json"));
            TestCaseManager.TestCase testCase = testCases.create("Recovery test", "synthetic", "High", List.of("recovery"),
                    "JUnit", "", story.id(), List.of(), "Core", "Functional", "Manual", owner, "Draft");
            stories.linkTestCase(story.id(), testCase.id());

            DefectManager defects = new DefectManager(root);
            Map<String, Object> defectInput = new LinkedHashMap<>();
            defectInput.put("title", "Recoverable defect");
            defectInput.put("storyId", story.id());
            defectInput.put("testCaseId", testCase.id());
            defectInput.put("projectId", project.id());
            Map<String, Object> defect = defects.create(defectInput, owner, "Synthetic owner");

            new CiCdConfig(root).save(new CiCdConfig.CiCdSettings("github", "https://example.invalid/recovery",
                    "", "main", List.of("junit"), "manual", "", ""), project.id());

            Path attachment = root.resolve(".qa-attachments").resolve("recovery.txt");
            StorageWriteCoordinator.coordinatedWrite(root, "attachments", () -> {
                Files.createDirectories(attachment.getParent());
                Files.writeString(attachment, "synthetic attachment");
            });
            Path report = root.resolve("target/runner-reports/recovery/report.txt");
            ArtifactPublicationService.writeText(root, report, "synthetic completed report");
            MediaPublicationService media = MediaPublicationService.open(root, "recovery-media");
            Path screenshot = media.stagingFile("recovery.png");
            Path video = media.stagingFile("recovery.mp4");
            Files.writeString(screenshot, "synthetic screenshot");
            Files.writeString(video, "synthetic video");
            media.publish(screenshot, MediaPublicationService.MediaType.SCREENSHOT, "recovery.png");
            media.publish(video, MediaPublicationService.MediaType.VIDEO, "recovery.mp4");
            media.discard();

            StorageWriteCoordinator coordinator = StorageWriteCoordinator.forRoot(root);
            Set<String> writers = Set.of("qa-runner.json", "organizations", "attachments", "imports", "database-metadata",
                    "wiki", "team", "roles", "auth", "shared-paths", "project-ownership", "automation-mapping",
                    "automation-history", "cicd", "reports");
            List<AutoCloseable> registrations = writers.stream().map(coordinator::registerWriter).toList();
            try {
                Path backup = backups.resolve("verified");
                BackupService.BackupResult result = new BackupService(root, coordinator)
                        .createVerifiedBackup(backup, Duration.ofSeconds(2));
                assertTrue(result.fileCount() >= 4);
                assertTrue(Files.isRegularFile(backup.resolve(BackupService.COMPLETE)));
                assertEquals(result.fileCount(), BackupService.verify(backup).fileCount());

                Path restored = backups.resolve("restored");
                BackupService.restoreVerified(backup, restored, 5,
                        OrganizationStore.CURRENT_SCHEMA_VERSION);
                assertTrue(new OrganizationStore(restored).findOrganization(organization.organizationId()) != null);
                UserStoryStore.Story restoredStory = new UserStoryStore(restored)
                        .listStories(project.id(), "", 0, 10, true).stream()
                        .filter(item -> item.id().equals(story.id())).findFirst().orElseThrow();
                assertEquals(sprint.id(), restoredStory.sprintId());
                assertTrue(Files.readString(restored.resolve("qa-runner.json")).contains(page.id()));
                assertTrue(Files.readString(restored.resolve("qa-runner.json")).contains(testCase.id()));
                assertTrue(Files.readString(restored.resolve("qa-runner.json")).contains(String.valueOf(defect.get("id"))));
                assertEquals("synthetic attachment", Files.readString(restored.resolve(".qa-attachments/recovery.txt")));
                assertEquals("synthetic completed report", Files.readString(restored.resolve("target/runner-reports/recovery/report.txt")));
                assertEquals("synthetic screenshot", Files.readString(restored.resolve("target/screenshots/recovery.png")));
                assertEquals("synthetic video", Files.readString(restored.resolve("target/videos/recovery.mp4")));
                assertTrue(!Files.exists(restored.resolve("target/runner-staging")));
            } finally {
                for (AutoCloseable registration : registrations) registration.close();
            }
        } finally {
            delete(root);
            delete(backups);
        }
    }

    private static void delete(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception error) { throw new RuntimeException(error); }
            });
        }
    }
}
