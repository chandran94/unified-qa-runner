package com.example.unifiedqarunner;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Disposable HTTP coverage for bounded screenshot/video response streaming. */
class ScreenshotVideoStreamingTest {
    private Path root;
    private RunnerServer server;
    private URI base;
    private byte[] screenshotBytes;
    private byte[] videoBytes;
    private SessionClient clientA;
    private SessionClient clientB;
    private SessionClient suspendedClient;
    private String projectAId;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("qa-media-stream-");
        Files.writeString(root.resolve("qa-runner.json"), "{}");
        AuthUserDirectory users = new AuthUserDirectory(root);
        String passwordA = "media-password-a";
        String passwordB = "media-password-b";
        AuthUserDirectory.User ownerA = users.create("Media Owner A", "mediaa", "mediaa@example.test", "", passwordA.toCharArray(), true);
        AuthUserDirectory.User userB = users.create("Media User B", "mediab", "mediab@example.test", "", passwordB.toCharArray(), true);
        AuthUserDirectory.User suspendedUser = users.create("Suspended Media User", "medias", "medias@example.test", "", "media-password-s".toCharArray(), true);

        UserStoryStore stories = new UserStoryStore(root);
        UserStoryStore.Project projectA = stories.createProject("MSA", "Media A", "Disposable", ownerA.username());
        UserStoryStore.Project projectB = stories.createProject("MSB", "Media B", "Disposable", userB.username());
        projectAId = projectA.id();
        OrganizationStore organizations = new OrganizationStore(root);
        Organization orgA = organizations.createOrganization("Media Organization A", "media-org-a", ownerA.id());
        Organization orgB = organizations.createOrganization("Media Organization B", "media-org-b", userB.id());
        organizations.assignProject(projectA.id(), orgA.organizationId(), "MEDIA_FIXTURE");
        organizations.assignProject(projectB.id(), orgB.organizationId(), "MEDIA_FIXTURE");
        OrganizationMembership suspendedMembership = new OrganizationMembership(UUID.randomUUID(), orgA.organizationId(),
                suspendedUser.id(), OrganizationRole.MEMBER, MembershipStatus.ACTIVE, Instant.now(), Instant.now());
        organizations.createMembership(suspendedMembership);
        organizations.updateMembership(suspendedMembership.membershipId(), OrganizationRole.MEMBER, MembershipStatus.SUSPENDED);

        TeamRoleManager roles = new TeamRoleManager(root);
        TeamMemberManager members = new TeamMemberManager(root);
        members.create(member(ownerA, "Admin", projectA.id()), Set.of(projectA.id(), projectB.id()), roles.list());
        members.create(member(userB, "Viewer", projectB.id()), Set.of(projectA.id(), projectB.id()), roles.list());
        members.create(member(suspendedUser, "Viewer", projectA.id()), Set.of(projectA.id()), roles.list());

        Path screenshots = Files.createDirectories(root.resolve("target/screenshots"));
        Path videos = Files.createDirectories(root.resolve("target/videos"));
        screenshotBytes = patternedBytes(48 * 1024 + 17, (byte) 0x2A);
        videoBytes = patternedBytes(48 * 1024 + 29, (byte) 0x57);
        Files.write(screenshots.resolve("authorized.png"), screenshotBytes);
        Files.write(videos.resolve("authorized.mp4"), videoBytes);
        Files.write(screenshots.resolve("empty.png"), new byte[0]);
        Files.write(videos.resolve("empty.mp4"), new byte[0]);
        ProjectOwnershipManager ownership = new ProjectOwnershipManager(root);
        ownership.assign("SCREENSHOT", "authorized.png", projectA.id(), "MEDIA_FIXTURE", Set.of(projectA.id(), projectB.id()));
        ownership.assign("VIDEO", "authorized.mp4", projectA.id(), "MEDIA_FIXTURE", Set.of(projectA.id(), projectB.id()));
        ownership.assign("SCREENSHOT", "empty.png", projectA.id(), "MEDIA_FIXTURE", Set.of(projectA.id(), projectB.id()));
        ownership.assign("VIDEO", "empty.mp4", projectA.id(), "MEDIA_FIXTURE", Set.of(projectA.id(), projectB.id()));

        server = new RunnerServer(root, 0, null, null);
        base = server.start();
        clientA = login("mediaa", passwordA);
        clientB = login("mediab", passwordB);
        suspendedClient = login("medias", "media-password-s");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) server.close();
        if (root != null && Files.exists(root)) {
            try (var paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void authorizedScreenshotStreamsCompleteBodyWithStableHeaders() throws Exception {
        HttpResponse<byte[]> response = clientA.getBytes("/screenshots/authorized.png");
        assertEquals(200, response.statusCode());
        assertEquals("image/png", response.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals((long) screenshotBytes.length, response.headers().firstValueAsLong("Content-Length").orElseThrow());
        assertArrayEquals(screenshotBytes, response.body());
    }

    @Test
    void authorizedVideoStreamsCompleteBodyAndCrossTenantAccessIsDenied() throws Exception {
        HttpResponse<byte[]> response = clientA.getBytes("/videos/authorized.mp4");
        assertEquals(200, response.statusCode());
        assertEquals("video/mp4", response.headers().firstValue("Content-Type").orElseThrow());
        assertEquals((long) videoBytes.length, response.headers().firstValueAsLong("Content-Length").orElseThrow());
        assertArrayEquals(videoBytes, response.body());
        assertEquals(403, clientB.getBytes("/videos/authorized.mp4").statusCode());
    }

    @Test
    void missingUnauthenticatedAndTraversalRequestsFailBeforeStreaming() throws Exception {
        assertEquals(404, clientA.getBytes("/screenshots/missing.png").statusCode());
        assertEquals(401, getBytes("/screenshots/authorized.png", null).statusCode());
        assertTrue(clientA.getBytes("/videos/%2E%2E/%2E%2E/qa-runner.json").statusCode() >= 400);
    }

    @Test
    void suspendedUserCannotDownloadScreenshotOrVideo() throws Exception {
        HttpResponse<byte[]> screenshot = suspendedClient.getBytes("/screenshots/authorized.png");
        HttpResponse<byte[]> video = suspendedClient.getBytes("/videos/authorized.mp4");
        assertEquals(403, screenshot.statusCode());
        assertEquals(403, video.statusCode());
        assertTrue(!containsBytes(screenshot.body(), screenshotBytes));
        assertTrue(!containsBytes(video.body(), videoBytes));
    }

    @Test
    void zeroByteMediaReturnsEmptyBodiesWithZeroLength() throws Exception {
        HttpResponse<byte[]> screenshot = clientA.getBytes("/screenshots/empty.png");
        HttpResponse<byte[]> video = clientA.getBytes("/videos/empty.mp4");
        assertEquals(200, screenshot.statusCode());
        assertEquals(200, video.statusCode());
        assertEquals(0, screenshot.body().length);
        assertEquals(0, video.body().length);
        assertTrue(screenshot.headers().firstValueAsLong("Content-Length").orElse(0L) == 0L);
        assertTrue(video.headers().firstValueAsLong("Content-Length").orElse(0L) == 0L);
    }

    @Test
    void clientDisconnectDoesNotPreventSubsequentDownload() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(base.resolve("/videos/authorized.mp4"))
                .header("Cookie", clientA.cookie()).GET().build();
        HttpResponse<java.io.InputStream> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (java.io.InputStream body = response.body()) {
            assertEquals(200, response.statusCode());
            body.read();
        }
        HttpResponse<byte[]> subsequent = clientA.getBytes("/videos/authorized.mp4");
        assertEquals(200, subsequent.statusCode());
        assertArrayEquals(videoBytes, subsequent.body());
    }

    @Test
    void symlinkOutsideTrustedRootIsRejectedWhenWindowsAllowsSymlinkCreation() throws Exception {
        Path outside = Files.createTempFile("qa-media-outside-", ".png");
        try {
            Files.write(outside, patternedBytes(32, (byte) 0x19));
            Path link = root.resolve("target/screenshots/escaped.png");
            try {
                Files.createSymbolicLink(link, outside);
            } catch (UnsupportedOperationException | SecurityException | java.nio.file.FileSystemException error) {
                Assumptions.assumeTrue(false, "Symlink creation unavailable without elevation/Developer Mode: " + error.getClass().getSimpleName());
                return;
            }
            ProjectOwnershipManager ownership = new ProjectOwnershipManager(root);
            ownership.assign("SCREENSHOT", "escaped.png", projectAId, "MEDIA_FIXTURE", Set.of(projectAId));
            HttpResponse<byte[]> response = clientA.getBytes("/screenshots/escaped.png");
            assertTrue(response.statusCode() >= 400, "Symlink escape must not stream external bytes");
            assertEquals(0, response.body().length);
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    private SessionClient login(String username, String password) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(base.resolve("/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(CiCdConfig.toJson(Map.of("username", username, "password", password))))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        Map<?, ?> json = (Map<?, ?>) JsonParser.parse(response.body());
        return new SessionClient(response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                String.valueOf(json.get("csrfToken")));
    }

    private HttpResponse<byte[]> getBytes(String path, String cookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path)).GET();
        if (cookie != null) builder.header("Cookie", cookie);
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static Map<String, Object> member(AuthUserDirectory.User user, String role, String projectId) {
        return Map.of("name", user.fullName(), "email", user.email(), "username", user.username(),
                "registeredUserId", user.id(), "role", role, "projectIds", java.util.List.of(projectId));
    }

    private static byte[] patternedBytes(int size, byte seed) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (seed + (i % 251));
        return bytes;
    }

    private static boolean containsBytes(byte[] body, byte[] needle) {
        if (needle.length == 0 || body.length < needle.length) return false;
        outer: for (int i = 0; i <= body.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (body[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private final class SessionClient {
        private final String cookie;
        private final String csrf;

        private SessionClient(String cookie, String csrf) {
            this.cookie = cookie;
            this.csrf = csrf;
        }

        HttpResponse<byte[]> getBytes(String path) throws Exception {
            return ScreenshotVideoStreamingTest.this.getBytes(path, cookie);
        }

        String cookie() {
            return cookie;
        }
    }
}
