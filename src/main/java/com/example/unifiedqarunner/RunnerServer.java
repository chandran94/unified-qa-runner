package com.example.unifiedqarunner;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Locale;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class RunnerServer implements AutoCloseable {
    private static final int MAX_REQUEST_BYTES = 64 * 1024;
    private static final int MAX_RUNS = 50;
    private static final int DEFAULT_PORT = 8081;
    private static final String CURRENT_USER_ID = configuredUser("QA_RUNNER_USER_ID", "qa.runner.user.id", "local-user");
    private static final String CURRENT_USER_NAME = configuredUser("QA_RUNNER_USER_NAME", "qa.runner.user.name", "Local QA User");
    private static final long MAX_ATTACHMENT_BYTES = Long.getLong("qa.runner.attachments.maxBytes", 10L * 1024L * 1024L);
    private static final Path ATTACHMENT_DIRECTORY = Path.of(".qa-attachments");
    private static final Pattern MULTIPART_BOUNDARY = Pattern.compile("boundary=\\\"?([^;\\\"]+)\\\"?", Pattern.CASE_INSENSITIVE);

    private final Path projectRoot;
    private final ExecutionService executionService;
    private final SharedPathConfig sharedPathConfig;
    private final RemoteImportConfig remoteImportConfig;
    private final UserStoryStore userStoryStore;
    private final HttpServer server;
    private final ExecutorService httpExecutor;
    private final ExecutorService runExecutor;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Map<String, RunEntry> runs = new LinkedHashMap<>();
    private String activeRunId;
    private final AtomicBoolean runnerEnabled = new AtomicBoolean(true);

    private final TestCaseManager testCaseManager;
    private final DefectManager defectManager;
    private final AutomationMappingManager automationMappingManager;
    private final AutomationExecutionManager automationExecutionManager;
    private final TeamMemberManager teamMemberManager;
    private final TeamRoleManager teamRoleManager;
    private final AuthUserDirectory authUserDirectory;
    private final AuthSessionManager authSessionManager;
    private final AuthVerificationManager authVerificationManager;
    private final PasswordResetManager passwordResetManager;
    private final AuthRegistrationRateLimiter authRegistrationRateLimiter = new AuthRegistrationRateLimiter();
    private final AuthRegistrationRateLimiter organizationInvitationRateLimiter = new AuthRegistrationRateLimiter();
    private final WikiManager wikiManager;
    private final ProjectOwnershipManager projectOwnershipManager;
    private final OrganizationStore organizationStore;
    private final TenantContextResolver tenantContextResolver;
    private final SecureRandom invitationRandom = new SecureRandom();
    private final DatabaseOverviewService databaseOverviewService = new DatabaseOverviewService();
    private final DatabaseConnectionService databaseConnectionService;

    RunnerServer(Path projectRoot) throws IOException {
        this(projectRoot, Integer.getInteger("qa.runner.port", DEFAULT_PORT), null, null);
    }

    /**
     * Test-only construction path. A zero port selects an ephemeral loopback
     * port and injected delivery keeps OTPs in the test process only.
     */
    RunnerServer(Path projectRoot, int port, VerificationDelivery testEmailDelivery,
                 VerificationDelivery testSmsDelivery) throws IOException {
        this.projectRoot = projectRoot;
        StorageWriteCoordinator.registerPersistentWriter(projectRoot, "attachments");
        this.executionService = new ExecutionService(projectRoot);
        Path compatibilityStore = projectRoot.resolve("qa-runner.json").toAbsolutePath().normalize();
        this.testCaseManager = new TestCaseManager(compatibilityStore);
        this.automationMappingManager = new AutomationMappingManager(compatibilityStore);
        this.sharedPathConfig = new SharedPathConfig(projectRoot);
        this.remoteImportConfig = new RemoteImportConfig(projectRoot);
        this.userStoryStore = new UserStoryStore(projectRoot);
        this.defectManager = new DefectManager(projectRoot);
        this.automationExecutionManager = new AutomationExecutionManager(projectRoot);
        this.teamMemberManager = new TeamMemberManager(projectRoot);
        this.teamRoleManager = new TeamRoleManager(projectRoot);
        this.authUserDirectory = new AuthUserDirectory(projectRoot);
        this.authSessionManager = new AuthSessionManager();
        this.authVerificationManager = testEmailDelivery == null || testSmsDelivery == null
                ? new AuthVerificationManager(authUserDirectory)
                : new AuthVerificationManager(authUserDirectory, testEmailDelivery, testSmsDelivery,
                java.time.Clock.systemUTC());
        VerificationDelivery resetDelivery = testEmailDelivery == null ? configuredResetDelivery() : testEmailDelivery;
        this.passwordResetManager = new PasswordResetManager(authUserDirectory, resetDelivery, authSessionManager);
        this.wikiManager = new WikiManager(projectRoot);
        this.projectOwnershipManager = new ProjectOwnershipManager(projectRoot);
        this.organizationStore = new OrganizationStore(projectRoot);
        this.tenantContextResolver = new TenantContextResolver(authSessionManager, authUserDirectory,
                organizationStore, teamMemberManager, teamRoleManager, projectOwnershipManager);
        this.ciCdConfig = new CiCdConfig(projectRoot);
        // Additive and idempotent: ambiguous legacy records remain unresolved.
        this.projectOwnershipManager.migrate(userStoryStore, defectManager.list(null));
        this.databaseConnectionService = new DatabaseConnectionService(projectRoot);
        // A local-only verification profile must never expose the development server beyond loopback.
        server = HttpServer.create(new InetSocketAddress(
                (port == 0 || isLocalOnlyProfile()) ? "127.0.0.1" : "0.0.0.0", port), 0);
        httpExecutor = Executors.newCachedThreadPool(runnable -> daemonThread(runnable, "qa-http"));
        runExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                runnable -> daemonThread(runnable, "qa-execution"),
                new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(httpExecutor);
        server.createContext("/", this::handle);
    }

    private static boolean isLocalOnlyProfile() {
        return "true".equalsIgnoreCase(System.getenv("QA_RUNNER_LOCAL_ONLY"));
    }

    private static VerificationDelivery configuredResetDelivery() {
        SmtpVerificationDelivery smtp = SmtpVerificationDelivery.fromEnvironment();
        if (smtp != null) return smtp;
        String dev = System.getenv("QA_RUNNER_AUTH_DEV_VERIFICATION");
        if ("true".equalsIgnoreCase(dev)
                && AuthVerificationManager.developmentVerificationAllowed(System.getenv("QA_RUNNER_ENV"), System.getenv("QA_RUNNER_LOCAL_ONLY"))) {
            return new DevelopmentVerificationDelivery();
        }
        return new ConfiguredVerificationDelivery();
    }

    URI start() {
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    void awaitTermination() throws InterruptedException {
        shutdownLatch.await();
    }

    static void openBrowser(URI address) throws IOException {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(address);
        } else {
            System.out.println("Open Unified QA Runner at " + address);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        server.stop(0);
        executionService.cancel();
        databaseConnectionService.close();
        authVerificationManager.close();
        passwordResetManager.close();
        runExecutor.shutdownNow();
        httpExecutor.shutdownNow();
        shutdownLatch.countDown();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                String hostHeader = exchange.getRequestHeaders().getFirst("Host");
                if (hostHeader == null || !hostHeader.endsWith(":" + server.getAddress().getPort())) {
                    sendError(exchange, 403, "Invalid Host header.");
                    return;
                }
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
                exchange.getResponseHeaders().set("Content-Security-Policy",
                        "default-src 'self'; script-src 'self' 'unsafe-inline'; "
                                + "style-src 'self' 'unsafe-inline'; connect-src 'self'; "
                                + "object-src 'none'; base-uri 'none'; frame-ancestors 'none'");

                String path = exchange.getRequestURI().getPath();
                if (exchange.getRequestURI().getRawQuery() != null && !queryParametersAllowed(path)) {
                    sendError(exchange, 400, "Query parameters are not supported.");
                } else if (path.equals("/") || path.equals("/index.html") || isPublicAuthPage(path)) {
                    servePage(exchange);
                } else if (isProtectedApplicationPage(path)) {
                    if (session(exchange) == null) {
                        redirect(exchange, "/login?return=" + java.net.URLEncoder.encode(path, StandardCharsets.UTF_8));
                    } else {
                        servePage(exchange);
                    }
                } else if (path.startsWith("/api/")) {
                    handleApi(exchange, path);
                } else if (path.startsWith("/screenshots/")) {
                    requireAuthenticatedUser(exchange);
                    serveScreenshot(exchange, path);
                } else if (path.startsWith("/videos/")) {
                    requireAuthenticatedUser(exchange);
                    serveVideo(exchange, path);
                } else {
                    sendError(exchange, 404, "Not found.");
                }
            } catch (AuthorizationException exception) {
                sendError(exchange, exception.status, exception.getMessage());
            } catch (IllegalArgumentException exception) {
                sendError(exchange, 400, exception.getMessage());
            } catch (IllegalStateException exception) {
                sendError(exchange, 409, exception.getMessage());
            } catch (Exception exception) {
                System.err.println("Runner request failed: " + exception.getMessage());
                sendError(exchange, 500, "The runner could not complete this request.");
            }
        }
    }

    private static boolean isPublicAuthPage(String path) {
        return Set.of("/login", "/register", "/verify", "/forgot-password", "/reset-password").contains(path);
    }

    private static boolean isPublicAuthApi(String path) {
        return Set.of("/api/auth/login", "/api/auth/register", "/api/auth/verification/send",
                "/api/auth/verification/resend", "/api/auth/verification/resend-by-email", "/api/auth/verification/confirm", "/api/auth/me",
                "/api/auth/logout", "/api/auth/password-reset/request", "/api/auth/password-reset/confirm").contains(path);
    }

    private static boolean isProtectedApplicationPage(String path) {
        return Set.of("/dashboard", "/testcases", "/userstories", "/runner", "/assets", "/history", "/wiki", "/database", "/team").contains(path);
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
    }

    private static boolean queryParametersAllowed(String path) {
        return isPublicAuthPage(path) || path.startsWith("/api/organizations") || path.startsWith("/api/organization-invitations") || path.startsWith("/api/user-stories/projects") || path.startsWith("/api/user-stories/epics") || path.startsWith("/api/user-stories/stories") || path.startsWith("/api/user-stories/sprints") || path.startsWith("/api/user-stories/board") || path.equals("/api/user-stories/work-items") || path.equals("/api/user-stories/test-case-links") || path.equals("/api/stories/testcases") || path.equals("/api/testcases") || path.startsWith("/api/testcases/") || path.equals("/api/testcases/sync") || path.equals("/api/defects") || path.equals("/api/testcase-automation-mappings") || path.equals("/api/automation-executions") || path.equals("/api/automation-assets/scenarios") || path.equals("/api/team-members") || path.equals("/api/team-permissions") || path.equals("/api/team-roles") || path.startsWith("/api/wiki/spaces") || path.equals("/api/database/connections") || path.startsWith("/api/database/connections/") || path.startsWith("/api/database/connection-tests/") || path.startsWith("/api/cicd/");
    }

    private void handleApi(HttpExchange exchange, String path) throws Exception {
        String method = exchange.getRequestMethod();
        if (!isPublicAuthApi(path)) {
            requireAuthenticatedUser(exchange);
        }
        if (path.equals("/api/auth/login") && method.equals("POST")) {
            requireSameOrigin(exchange);
            Map<String, Object> values = requestObject(exchange);
            String login = stringField(values, "username").trim();
            String password = stringField(values, "password");
            String rateKey = exchange.getRemoteAddress().getAddress().getHostAddress() + ":" + AuthUserDirectory.normalizeUsername(login);
            if (!authSessionManager.allowLogin(rateKey)) {
                exchange.getResponseHeaders().set("Retry-After", "900");
                sendError(exchange, 429, "Too many login attempts. Try again later.");
                return;
            }
            char[] passwordChars = password.toCharArray();
            AuthUserDirectory.User user = authUserDirectory.authenticate(login, passwordChars);
            java.util.Arrays.fill(passwordChars, '\0');
            if (user == null) {
                authSessionManager.recordFailure(rateKey);
                authUserDirectory.recordAudit("LOGIN_FAILED", login);
                sendError(exchange, 401, "Invalid username or password.");
                return;
            }
            authSessionManager.recordSuccess(rateKey);
            AuthSessionManager.Session session = authSessionManager.create(user.id());
            exchange.getResponseHeaders().add("Set-Cookie", authSessionManager.cookie(session, secureCookies()));
            sendJson(exchange, 200, Map.of("user", authUserToMap(user), "csrfToken", session.csrfToken()));
            return;
        }
        if (path.equals("/api/auth/register") && method.equals("POST")) {
            requireSameOrigin(exchange);
            String source = exchange.getRemoteAddress().getAddress().getHostAddress();
            if (!authRegistrationRateLimiter.allow(source)) { sendError(exchange, 429, "Registration is temporarily unavailable. Try again later."); return; }
            Map<String,Object> values=requestObject(exchange);
            String fullName=stringField(values,"fullName").trim(); String username=stringField(values,"username").trim();
            String contact=stringField(values,"contact").trim(); String password=stringField(values,"password"); String confirmation=stringField(values,"confirmPassword");
            if (!password.equals(confirmation)) throw new IllegalArgumentException("Passwords do not match.");
            String email=contact.contains("@")?AuthUserDirectory.normalizeEmail(contact):"";
            String mobile=contact.contains("@")?"":AuthUserDirectory.normalizeMobile(contact);
            AuthUserDirectory.User user;
            char[] passwordChars=password.toCharArray();
            try { user=authUserDirectory.create(fullName,username,email,mobile,passwordChars,false,AuthUserDirectory.PENDING); }
            catch (IllegalArgumentException error) {
                if (error.getMessage()!=null && error.getMessage().contains("already registered")) { sendJson(exchange,202,Map.of("accepted",true,"message","If the account can be registered, verification instructions will be sent.")); return; }
                throw error;
            } finally { java.util.Arrays.fill(passwordChars,'\0'); }
            try {
                AuthVerificationManager.Result result=authVerificationManager.send(user.id());
                sendJson(exchange,202,verificationToMap(result));
            } catch (Exception error) {
                try { authUserDirectory.delete(user.id()); } catch (Exception ignored) { }
                sendError(exchange,503,"Verification delivery is not available. Please try again later.");
            }
            return;
        }
        if (path.equals("/api/auth/verification/send") && method.equals("POST")) {
            requireSameOrigin(exchange); Map<String,Object> values=requestObject(exchange); AuthVerificationManager.Result result=authVerificationManager.resend(stringField(values,"verificationToken"), exchange.getRemoteAddress().getAddress().getHostAddress()); sendJson(exchange,202,verificationToMap(result)); return;
        }
        if (path.equals("/api/auth/verification/resend") && method.equals("POST")) {
            requireSameOrigin(exchange); Map<String,Object> values=requestObject(exchange); AuthVerificationManager.Result result=authVerificationManager.resend(stringField(values,"verificationToken"), exchange.getRemoteAddress().getAddress().getHostAddress()); sendJson(exchange,202,verificationToMap(result)); return;
        }
        if (path.equals("/api/auth/verification/resend-by-email") && method.equals("POST")) {
            requireSameOrigin(exchange);
            try { authVerificationManager.resendByEmail(stringField(requestObject(exchange), "email"), exchange.getRemoteAddress().getAddress().getHostAddress()); }
            catch (Exception ignored) { }
            sendJson(exchange, 202, Map.of("accepted", true, "message", "If verification is available, new instructions will be sent."));
            return;
        }
        if (path.equals("/api/auth/verification/confirm") && method.equals("POST")) {
            requireSameOrigin(exchange); Map<String,Object> values=requestObject(exchange); AuthUserDirectory.User user=authVerificationManager.confirm(stringField(values,"verificationToken"),stringField(values,"otp")); sendJson(exchange,200,Map.of("verified",true,"userId",user.id(),"message","Account verified. Please sign in.")); return;
        }
        if (path.equals("/api/auth/password-reset/request") && method.equals("POST")) {
            requireSameOrigin(exchange);
            Map<String, Object> values = requestObject(exchange);
            try { passwordResetManager.request(stringField(values, "email")); }
            catch (Exception ignored) { }
            sendJson(exchange, 202, Map.of("accepted", true, "message", "If the account can be recovered, reset instructions will be sent."));
            return;
        }
        if (path.equals("/api/auth/password-reset/confirm") && method.equals("POST")) {
            requireSameOrigin(exchange);
            Map<String, Object> values = requestObject(exchange);
            String password = stringField(values, "password");
            String confirmation = stringField(values, "confirmPassword");
            if (!password.equals(confirmation)) throw new IllegalArgumentException("Passwords do not match.");
            char[] passwordChars = password.toCharArray();
            try { passwordResetManager.confirm(stringField(values, "token"), passwordChars); }
            finally { java.util.Arrays.fill(passwordChars, '\0'); }
            sendJson(exchange, 200, Map.of("reset", true, "message", "Password reset successfully. Please sign in."));
            return;
        }
        if (path.equals("/api/auth/me") && method.equals("GET")) {
            AuthSessionManager.Session session = session(exchange);
            if (session == null) { sendError(exchange, 401, "Authentication is required."); return; }
            AuthUserDirectory.User user = authUserDirectory.findById(session.userId());
            if (user == null || !AuthUserDirectory.ACTIVE.equals(user.status())) { authSessionManager.invalidate(session.id()); sendError(exchange, 401, "Authentication is required."); return; }
            sendJson(exchange, 200, Map.of("user", authUserToMap(user), "csrfToken", session.csrfToken()));
            return;
        }
        if (path.equals("/api/auth/logout") && method.equals("POST")) {
            requireAuthenticatedCsrf(exchange);
            AuthSessionManager.Session current = session(exchange);
            authSessionManager.invalidate(current.id());
            exchange.getResponseHeaders().add("Set-Cookie", authSessionManager.expiredCookie(secureCookies()));
            sendJson(exchange, 200, Map.of("loggedOut", true));
            return;
        }
        if (path.startsWith("/api/organizations") || path.startsWith("/api/organization-invitations")) {
            handleOrganizationApi(exchange, path, method);
            return;
        }
        // These legacy stores do not yet carry an authoritative project key.
        // Deny ordinary access until an explicit ownership migration exists;
        // never infer ownership from a filename, display name, or array index.
        requireAdminForUnownedResource(exchange, path);
        if (path.equals("/api/admin/project-ownership/unresolved") && method.equals("GET")) {
            requireAuthenticatedAdmin(exchange);
            sendJson(exchange, 200, projectOwnershipManager.unresolved().stream().map(this::ownershipToMap).toList());
            return;
        }
        if (path.startsWith("/api/admin/project-ownership/") && method.equals("POST")) {
            requireAuthenticatedCsrf(exchange);
            requireAuthenticatedAdmin(exchange);
            String remainder = path.substring("/api/admin/project-ownership/".length());
            int separator = remainder.indexOf('/');
            if (separator <= 0 || separator == remainder.length() - 1) throw new IllegalArgumentException("Resource type and ID are required.");
            Map<String, Object> values = requestObject(exchange);
            String projectId = stringField(values, "projectId").trim();
            ProjectOwnershipManager.Ownership ownership = projectOwnershipManager.assign(
                    remainder.substring(0, separator), remainder.substring(separator + 1), projectId,
                    "ADMIN_MIGRATION", validProjectIds());
            sendJson(exchange, 200, ownershipToMap(ownership));
            return;
        }
        if (path.startsWith("/api/wiki/") && !Set.of("GET", "HEAD", "OPTIONS").contains(method)) {
            requireAuthenticatedCsrf(exchange);
        }
        if (path.equals("/api/wiki/spaces") && method.equals("GET")) {
            sendJson(exchange, 200, wikiManager.listSpaces(queryValue(exchange, "search")).stream()
                    .filter(space -> tenantVisibleOwnedResource(exchange, "WIKI_SPACE", space.id(), TeamAuthorization.WIKI, false))
                    .map(this::wikiSpaceToMap).toList());
            return;
        }
        if (path.equals("/api/wiki/spaces") && method.equals("POST")) {
            Map<String, Object> values = requestObject(exchange);
            String projectId = stringField(values, "projectId").trim();
            requireProjectAccess(exchange, projectId, TeamAuthorization.WIKI, true);
            WikiManager.Space created = wikiManager.createSpace(stringField(values, "name"), stringField(values, "key"), optionalString(values, "description"), authActorName(exchange));
            projectOwnershipManager.assign("WIKI_SPACE", created.id(), projectId, "WIKI_SPACE_CREATE", validProjectIds());
            sendJson(exchange, 201, wikiSpaceToMap(created));
            return;
        }
        if (path.startsWith("/api/wiki/spaces/")) {
            String remainder = path.substring("/api/wiki/spaces/".length());
            if (remainder.endsWith("/pages") && method.equals("GET")) {
                String spaceId = remainder.substring(0, remainder.length() - "/pages".length());
                requireOwnedProjectAccess(exchange, "WIKI_SPACE", spaceId, TeamAuthorization.WIKI, false);
                sendJson(exchange, 200, wikiManager.listPages(spaceId, queryValue(exchange, "search"), "true".equalsIgnoreCase(queryValue(exchange, "includeArchived"))).stream().map(this::wikiPageToMap).toList());
                return;
            }
            String spaceId = remainder;
            if (method.equals("GET")) { requireOwnedProjectAccess(exchange, "WIKI_SPACE", spaceId, TeamAuthorization.WIKI, false); sendJson(exchange, 200, wikiSpaceToMap(wikiManager.getSpace(spaceId))); return; }
            if (method.equals("PUT")) { requireOwnedProjectAccess(exchange, "WIKI_SPACE", spaceId, TeamAuthorization.WIKI, true); sendJson(exchange, 200, wikiSpaceToMap(wikiManager.updateSpace(spaceId, requestObject(exchange)))); return; }
            if (method.equals("POST") && remainder.endsWith("/archive")) { String id = remainder.substring(0, remainder.length() - "/archive".length()); requireOwnedProjectAccess(exchange, "WIKI_SPACE", id, TeamAuthorization.WIKI, true); sendJson(exchange, 200, wikiSpaceToMap(wikiManager.setSpaceStatus(id, WikiManager.ARCHIVED))); return; }
        }
        if (path.equals("/api/wiki/pages") && method.equals("POST")) {
            Map<String, Object> values = requestObject(exchange);
            String spaceId = stringField(values, "spaceId");
            requireOwnedProjectAccess(exchange, "WIKI_SPACE", spaceId, TeamAuthorization.WIKI, true);
            WikiManager.Page created = wikiManager.createPage(values, authActorName(exchange));
            projectOwnershipManager.assign("WIKI_PAGE", created.id(), projectOwnershipManager.projectId("WIKI_SPACE", spaceId), "WIKI_SPACE_RELATIONSHIP", validProjectIds());
            sendJson(exchange, 201, wikiPageToMap(created));
            return;
        }
        if (path.startsWith("/api/wiki/pages/")) {
            String remainder = path.substring("/api/wiki/pages/".length());
            if (remainder.endsWith("/archive") || remainder.endsWith("/restore")) {
                boolean restore = remainder.endsWith("/restore"); String id = remainder.substring(0, remainder.lastIndexOf('/')); requireOwnedProjectAccess(exchange, "WIKI_PAGE", id, TeamAuthorization.WIKI, true); Map<String,Object> values=requestObject(exchange); int version=Integer.parseInt(String.valueOf(values.getOrDefault("version", wikiManager.getPage(id).version()))); sendJson(exchange, 200, wikiPageToMap(wikiManager.setPageStatus(id, restore ? WikiManager.DRAFT : WikiManager.ARCHIVED, authActorName(exchange), version))); return;
            }
            if (method.equals("GET")) { requireOwnedProjectAccess(exchange, "WIKI_PAGE", remainder, TeamAuthorization.WIKI, false); sendJson(exchange, 200, wikiPageToMap(wikiManager.getPage(remainder))); return; }
            if (method.equals("PUT")) { requireOwnedProjectAccess(exchange, "WIKI_PAGE", remainder, TeamAuthorization.WIKI, true); sendJson(exchange, 200, wikiPageToMap(wikiManager.updatePage(remainder, requestObject(exchange), authActorName(exchange)))); return; }
        }
        if (path.equals("/api/database/connections") && method.equals("GET")) {
            String organizationId = requireDatabaseViewForOrganization(exchange, queryValue(exchange, "organizationId"));
            int page = parseQueryInt(exchange, "page", 0); int pageSize = parseQueryInt(exchange, "pageSize", 10);
            sendJson(exchange, 200, databaseConnectionService.listForOrganization(organizationId, queryValue(exchange, "search"), page, pageSize)); return;
        }
        if (path.equals("/api/database/connections/test-draft") && method.equals("POST")) {
            requireSameOrigin(exchange);
            Map<String, Object> values = requestObject(exchange);
            requireDatabaseManageForOrganization(exchange, stringField(values, "organizationId"));
            databaseConnectionService.testDraft(values);
            sendJson(exchange, 200, Map.of("message", "Connection test passed."));
            return;
        }
        if (path.equals("/api/database/connections") && method.equals("POST")) {
            Map<String,Object> values=requestObject(exchange); String organizationId = requireDatabaseManageForOrganization(exchange, stringField(values, "organizationId"));
            sendJson(exchange, 201, databaseConnectionService.createForOrganization(organizationId, values)); return;
        }
        if (path.equals("/api/database/connections/sample/browse") && method.equals("POST")) {
            requireAuthenticatedCsrf(exchange);
            Map<String, Object> values = requestObject(exchange);
            sendJson(exchange, 200, SampleDatabase.browse(optionalString(values, "schema"), optionalString(values, "table")));
            return;
        }
        if (path.startsWith("/api/database/connections/")) {
            String remainder=path.substring("/api/database/connections/".length());
            String organizationId = queryValue(exchange, "organizationId");
            if (remainder.endsWith("/browse") && method.equals("POST")) {
                requireDatabaseManageForOrganization(exchange, organizationId);
                String id = remainder.substring(0, remainder.length() - "/browse".length());
                Map<String, Object> values = requestObject(exchange);
                sendJson(exchange, 200, databaseConnectionService.browseForOrganization(organizationId, id,
                        optionalString(values, "schema"), optionalString(values, "table")));
                return;
            }
            if (remainder.endsWith("/test") && method.equals("POST")) { requireDatabaseManageForOrganization(exchange, organizationId); String id=remainder.substring(0,remainder.length()-5); requireDatabaseConnectionOrganization(id, organizationId); ConnectionTestService.TestState state=databaseConnectionService.submitTestForOrganization(organizationId,id); sendJson(exchange,202,databaseTestToMap(state)); return; }
            if (remainder.endsWith("/connect") && method.equals("POST")) { requireDatabaseManageForOrganization(exchange, organizationId); String id=remainder.substring(0,remainder.length()-8); requireDatabaseConnectionOrganization(id, organizationId); sendJson(exchange,200,databaseConnectionService.connectForOrganization(organizationId,id)); return; }
            if (remainder.endsWith("/disconnect") && method.equals("POST")) { requireDatabaseManageForOrganization(exchange, organizationId); String id=remainder.substring(0,remainder.length()-11); requireDatabaseConnectionOrganization(id, organizationId); sendJson(exchange,200,databaseConnectionService.disconnectForOrganization(organizationId,id)); return; }
            if (remainder.matches("[^/]+") && method.equals("GET")) { requireDatabaseViewForOrganization(exchange, organizationId); requireDatabaseConnectionOrganization(remainder, organizationId); sendJson(exchange,200,databaseConnectionService.get(remainder)); return; }
            if (remainder.matches("[^/]+") && method.equals("PUT")) { Map<String,Object> values=requestObject(exchange); String bodyOrganizationId=stringField(values,"organizationId"); requireDatabaseManageForOrganization(exchange, bodyOrganizationId); requireDatabaseConnectionOrganization(remainder, bodyOrganizationId); sendJson(exchange,200,databaseConnectionService.updateForOrganization(bodyOrganizationId,remainder,values)); return; }
            if (remainder.matches("[^/]+") && method.equals("DELETE")) { requireDatabaseManageForOrganization(exchange, organizationId); requireDatabaseConnectionOrganization(remainder, organizationId); databaseConnectionService.delete(remainder); sendJson(exchange,200,Map.of("deleted",true)); return; }
        }
        if (path.startsWith("/api/database/connection-tests/")) {
            String id=path.substring("/api/database/connection-tests/".length()); String organizationId=queryValue(exchange,"organizationId");
            requireDatabaseViewForOrganization(exchange, organizationId); requireDatabaseConnectionOrganization(databaseConnectionService.testConnectionId(id), organizationId);
            if (method.equals("GET")) { sendJson(exchange,200,databaseTestToMap(databaseConnectionService.testStatus(id))); return; }
            if (method.equals("DELETE")) { requireDatabaseManageForOrganization(exchange, organizationId); sendJson(exchange,200,databaseTestToMap(databaseConnectionService.cancelTestForOrganization(organizationId,id))); return; }
        }
        if (!Set.of("GET", "HEAD", "OPTIONS").contains(method)) {
            requireAuthenticatedCsrf(exchange);
        }
        if (path.equals("/api/team-permissions") && method.equals("GET")) {
            requireAuthenticatedUser(exchange);
            sendJson(exchange, 200, teamAuthorization(exchange).permissionSnapshot(queryValue(exchange, "projectId"), teamMemberManager.list()));
            return;
        }
        if (path.equals("/api/team-roles") && method.equals("GET")) {
            requireAuthenticatedUser(exchange);
            sendJson(exchange, 200, teamRoleManager.list().stream().map(role -> teamRoleToMap(role, teamMemberManager.list())).toList());
            return;
        }
        if (path.equals("/api/team-roles") && method.equals("POST")) {
            requireSameOrigin(exchange); requireAuthenticatedAdmin(exchange);
            TeamRoleManager.Role role = teamRoleManager.create(requestObject(exchange));
            AuthUserDirectory.User actor = requireAuthenticatedUser(exchange);
            teamRoleManager.recordAudit("ROLE_CREATED", role.id(), actor.id(), actor.username());
            sendJson(exchange, 201, teamRoleToMap(role, teamMemberManager.list()));
            return;
        }
        if (path.startsWith("/api/team-roles/") && method.equals("PUT")) {
            requireSameOrigin(exchange); requireAuthenticatedAdmin(exchange);
            String id = path.substring("/api/team-roles/".length());
            TeamRoleManager.Role role = teamRoleManager.update(id, requestObject(exchange), teamMemberManager.list());
            AuthUserDirectory.User actor = requireAuthenticatedUser(exchange);
            teamRoleManager.recordAudit("ROLE_UPDATED", role.id(), actor.id(), actor.username());
            sendJson(exchange, 200, teamRoleToMap(role, teamMemberManager.list()));
            return;
        }
        if (path.equals("/api/team-members") && method.equals("GET")) {
            TenantContext tenant = tenantContextForProject(exchange, queryValue(exchange, "projectId"),
                    TeamAuthorization.TEAM_VIEW, "read", true);
            if (tenant == null) requireTeamView(exchange);
            sendJson(exchange, 200, listTeamMembers(exchange, tenant));
            return;
        }
        if (path.equals("/api/team-registered-users") && method.equals("GET")) {
            requireAuthenticatedAdmin(exchange);
            List<Map<String, Object>> users = authUserDirectory.list().stream().map(user -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("id", user.id()); result.put("fullName", user.fullName());
                result.put("username", user.username()); result.put("email", user.email());
                result.put("mobile", user.mobile()); result.put("status", user.status());
                result.put("verified", user.emailVerified() || user.mobileVerified());
                return result;
            }).toList();
            sendJson(exchange, 200, users);
            return;
        }
        if (path.equals("/api/team-members") && method.equals("POST")) {
            requireSameOrigin(exchange);
            Map<String, Object> values = normalizeMemberRole(requestObject(exchange));
            requireTeamManage(exchange, values, null);
            validateRegisteredUser(exchange, values);
            sendJson(exchange, 201, teamMemberToMap(teamMemberManager.create(values, validProjectIds(), teamRoleManager.list())));
            return;
        }
        if (path.startsWith("/api/team-members/") && method.equals("PUT")) {
            requireSameOrigin(exchange);
            String id = path.substring("/api/team-members/".length());
            Map<String, Object> values = normalizeMemberRole(requestObject(exchange));
            requireTeamManage(exchange, values, id);
            validateRegisteredUser(exchange, values);
            sendJson(exchange, 200, teamMemberToMap(teamMemberManager.update(id, values, validProjectIds(), teamRoleManager.list())));
            return;
        }
        if (path.startsWith("/api/team-members/") && method.equals("DELETE")) {
            sendError(exchange, 405, "Team members are deactivated rather than deleted to preserve work history.");
            return;
        }
        if (path.equals("/api/catalog") && method.equals("GET")) {
            sendJson(exchange, 200, visibleCatalogResponse(exchange));
            return;
        }
        if (path.equals("/api/database/overview") && method.equals("GET")) {
            Map<String, Object> catalog = catalogResponse(exchange);
            List<Map<String, Object>> assets = catalog.get("assets") instanceof List<?> values
                    ? values.stream().filter(Map.class::isInstance).map(value -> (Map<String, Object>) value).toList()
                    : List.of();
            List<Map<String, Object>> runs = runListResponse().get("runs") instanceof List<?> values
                    ? values.stream().filter(Map.class::isInstance).map(value -> (Map<String, Object>) value).toList()
                    : List.of();
            sendJson(exchange, 200, databaseOverviewService.build(testCaseManager.list(), assets, runs,
                    new DatabaseConnectionRepository(projectRoot).list()));
            return;
        }
        if (path.equals("/api/automation-assets/scenarios") && method.equals("GET")) {
            String assetId = queryParameter(exchange, "assetId");
            Map<String, Object> asset = catalogResponse(exchange).get("assets") instanceof List<?> assets
                    ? assets.stream().filter(item -> item instanceof Map<?, ?> map
                            && assetId != null && assetId.equals(String.valueOf(map.get("assetId"))))
                    .map(item -> (Map<String, Object>) item).findFirst().orElse(null) : null;
            if (asset == null) throw new IllegalArgumentException("Automation asset was not discovered in the current catalog.");
            if (!assetAccessible(exchange, assetId, false)) {
                throw new AuthorizationException(403, "This automation asset has no accessible project ownership.");
            }
            sendJson(exchange, 200, cucumberScenarios(asset));
            return;
        }
        if (path.equals("/api/testcase-automation-mappings") && method.equals("GET")) {
            String testCaseId = queryParameter(exchange, "testCaseId");
            String assetId = queryParameter(exchange, "assetId");
            List<Map<String, Object>> mappings = automationMappingManager.list().stream()
                    .filter(item -> ownedProjectAccess(exchange, "TEST_CASE", stringField(item, "testCaseId"),
                            TeamAuthorization.TEST_CASES, false)).toList();
            if (testCaseId != null && !testCaseId.isBlank()) {
                mappings = mappings.stream().filter(item -> testCaseId.equals(String.valueOf(item.get("testCaseId")))).toList();
            }
            if (assetId != null && !assetId.isBlank()) {
                mappings = mappings.stream().filter(item -> assetId.equals(String.valueOf(item.get("assetId")))).toList();
            }
            sendJson(exchange, 200, mappings);
            return;
        }
        if (path.equals("/api/testcase-automation-mappings") && method.equals("POST")) {
            requireSameOrigin(exchange);
            Map<String, Object> values = requestObject(exchange);
            String testCaseId = stringField(values, "testCaseId");
            String assetId = stringField(values, "assetId");
            if (testCaseManager.getById(testCaseId) == null) throw new IllegalArgumentException("Test case not found: " + testCaseId);
            requireOwnedProjectAccess(exchange, "TEST_CASE", testCaseId, TeamAuthorization.TEST_CASES, true);
            Map<String, Object> asset = catalogResponse(exchange).get("assets") instanceof List<?> assets
                    ? assets.stream().filter(item -> item instanceof Map<?, ?> map && assetId.equals(String.valueOf(map.get("assetId"))))
                        .map(item -> (Map<String, Object>) item).findFirst().orElse(null)
                    : null;
            if (asset == null) throw new IllegalArgumentException("Automation asset was not discovered in the current catalog.");
            Map<String, Object> mapping = automationMappingManager.link(testCaseId, assetId,
                    stringField(asset, "kind"), stringField(asset, "sourceProject"), stringField(asset, "selection"),
                    Boolean.TRUE.equals(values.get("confirmShared")));
            projectOwnershipManager.assign("AUTOMATION_MAPPING", stringField(mapping, "id"),
                    projectOwnershipManager.projectId("TEST_CASE", testCaseId), "TEST_CASE_RELATIONSHIP", validProjectIds());
            sendJson(exchange, 201, mapping);
            return;
        }
        if (path.equals("/api/testcase-automation-mappings") && method.equals("DELETE")) {
            requireSameOrigin(exchange);
            String testCaseId = queryParameter(exchange, "testCaseId");
            String assetId = queryParameter(exchange, "assetId");
            requireOwnedProjectAccess(exchange, "TEST_CASE", testCaseId, TeamAuthorization.TEST_CASES, true);
            if (!automationMappingManager.unlink(testCaseId, assetId)) {
                sendError(exchange, 404, "Automation mapping not found.");
            } else {
                sendJson(exchange, 200, Map.of("testCaseId", testCaseId, "assetId", assetId, "removed", true));
            }
            return;
        }
        if (path.equals("/api/automation-executions") && method.equals("GET")) {
            List<Map<String, Object>> executions = automationExecutionManager.list(
                    queryParameter(exchange, "testCaseId"), queryParameter(exchange, "assetId"));
            sendJson(exchange, 200, executions.stream()
                    .filter(item -> automationExecutionVisible(exchange, item)).toList());
            return;
        }
        if (path.equals("/api/status") && method.equals("GET")) {
            sendJson(exchange, 200, Map.of("enabled", runnerEnabled.get(),
                    "port", server.getAddress().getPort()));
            return;
        }
        if (path.equals("/api/enable") && method.equals("POST")) {
            requireSameOrigin(exchange);
            runnerEnabled.set(true);
            sendJson(exchange, 200, Map.of("message", "Runner enabled. You can now execute tests."));
            return;
        }
        if (path.equals("/api/disable") && method.equals("POST")) {
            requireSameOrigin(exchange);
            runnerEnabled.set(false);
            sendJson(exchange, 200, Map.of("message", "Runner disabled. Tests will not execute until re-enabled."));
            return;
        }
        if (path.equals("/api/shutdown") && method.equals("POST")) {
            requireSameOrigin(exchange);
            sendJson(exchange, 202, Map.of("message", "Unified QA Runner is shutting down."));
            Thread shutdown = daemonThread(() -> {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                close();
            }, "qa-shutdown");
            shutdown.start();
            return;
        }
        if (path.equals("/api/runs") && method.equals("GET")) {
            List<Map<String, Object>> visible = runListResponse().get("runs") instanceof List<?> values
                    ? values.stream().filter(item -> item instanceof Map<?, ?> map && ownedProjectAccess(exchange, "TEST_RUN", String.valueOf(map.get("id")), TeamAuthorization.TEST_EXECUTION, false)).map(item -> (Map<String, Object>) item).toList()
                    : List.of();
            sendJson(exchange, 200, Map.of("runs", visible));
            return;
        }
        if (path.equals("/api/shared-paths") && method.equals("GET")) {
            sendJson(exchange, 200, sharedPathConfig.getSharedPaths().stream()
                    .filter(e -> isGlobalAdmin(exchange) || ownedProjectAccess(exchange, "SHARED_PATH", e.getName(), TeamAuthorization.TEST_EXECUTION, false))
                    .map(e -> Map.of("name", e.getName(), "path", e.getPath()))
                    .toList());
            return;
        }
        if (path.equals("/api/shared-paths") && method.equals("POST")) {
            requireSameOrigin(exchange);
            addSharedPath(exchange);
            return;
        }
        if (path.startsWith("/api/shared-paths/") && method.equals("DELETE")) {
            requireSameOrigin(exchange);
            String name = path.substring("/api/shared-paths/".length());
            requireOwnedProjectAccess(exchange, "SHARED_PATH", name, TeamAuthorization.TEST_EXECUTION, true);
            try {
                sharedPathConfig.removeSharedPath(name);
                sendJson(exchange, 200, Map.of("message", "Shared path '" + name + "' removed."));
            } catch (IOException e) {
                sendError(exchange, 500, "Failed to remove shared path: " + e.getMessage());
            }
            return;
        }
        if (path.startsWith("/api/shared-paths/") && path.endsWith("/scan") && method.equals("POST")) {
            requireSameOrigin(exchange);
            String name = path.substring("/api/shared-paths/".length(), path.length() - "/scan".length());
            requireOwnedProjectAccess(exchange, "SHARED_PATH", name, TeamAuthorization.TEST_EXECUTION, false);
            scanSharedPath(exchange, name);
            return;
        }
        // === Remote Import Endpoints ===
        if (path.equals("/api/imports") && method.equals("GET")) {
            sendJson(exchange, 200, remoteImportConfig.getImports().stream()
                    .filter(e -> isGlobalAdmin(exchange) || ownedProjectAccess(exchange, "REMOTE_IMPORT", e.getId(), TeamAuthorization.TEST_EXECUTION, false))
                    .map(e -> Map.of("id", e.getId(), "name", e.getName(), "type", e.getType(),
                            "source", e.getSource(), "importedAt", e.getImportedAt()))
                    .toList());
            return;
        }
        if (path.equals("/api/imports/github") && method.equals("POST")) {
            requireSameOrigin(exchange);
            importFromGitHub(exchange);
            return;
        }
        if (path.equals("/api/imports/zip") && method.equals("POST")) {
            requireSameOrigin(exchange);
            importFromZip(exchange);
            return;
        }
        if (path.startsWith("/api/imports/") && method.equals("DELETE")) {
            requireSameOrigin(exchange);
            String id = path.substring("/api/imports/".length());
            requireOwnedProjectAccess(exchange, "REMOTE_IMPORT", id, TeamAuthorization.TEST_EXECUTION, true);
            try {
                remoteImportConfig.removeImport(id);
                sendJson(exchange, 200, Map.of("message", "Import '" + id + "' removed."));
            } catch (IOException e) {
                sendError(exchange, 500, "Failed to remove import: " + e.getMessage());
            }
            return;
        }
        if (path.startsWith("/api/imports/") && path.endsWith("/scan") && method.equals("POST")) {
            requireSameOrigin(exchange);
            String id = path.substring("/api/imports/".length(), path.length() - "/scan".length());
            requireOwnedProjectAccess(exchange, "REMOTE_IMPORT", id, TeamAuthorization.TEST_EXECUTION, false);
            scanImport(exchange, id);
            return;
        }
        // === Screenshots & Videos Endpoints ===
        if (path.equals("/api/screenshots") && method.equals("GET")) {
            sendJson(exchange, 200, listScreenshots(exchange));
            return;
        }
        if (path.equals("/api/screenshots") && method.equals("DELETE")) {
            requireSameOrigin(exchange);
            deleteAllScreenshots(exchange);
            return;
        }
        if (path.startsWith("/api/screenshots/") && method.equals("DELETE")) {
            requireSameOrigin(exchange);
            String fileName = path.substring("/api/screenshots/".length());
            deleteScreenshot(exchange, fileName);
            return;
        }
        if (path.equals("/api/videos") && method.equals("GET")) {
            sendJson(exchange, 200, listVideos(exchange));
            return;
        }
        if (path.equals("/api/videos") && method.equals("DELETE")) {
            requireSameOrigin(exchange);
            deleteAllVideos(exchange);
            return;
        }
        if (path.startsWith("/api/videos/") && method.equals("DELETE")) {
            requireSameOrigin(exchange);
            String fileName = path.substring("/api/videos/".length());
            deleteVideo(exchange, fileName);
            return;
        }
        if (path.equals("/api/runs") && method.equals("POST")) {
            requireSameOrigin(exchange);
        if (!runnerEnabled.get()) {
                sendError(exchange, 409, "Runner is disabled. Enable the runner before executing tests.");
                return;
            }
            createRun(exchange);
            return;
        }
        String[] parts = path.split("/");
        if (parts.length == 4 && parts[1].equals("api") && parts[2].equals("runs")) {
            String id = parts[3];
            if (method.equals("GET")) {
                requireOwnedProjectAccess(exchange, "TEST_RUN", id, TeamAuthorization.TEST_EXECUTION, false);
                sendJson(exchange, 200, runResponse(requireRun(id)));
                return;
            }
            if (method.equals("DELETE")) {
                requireSameOrigin(exchange);
                requireOwnedProjectAccess(exchange, "TEST_RUN", id, TeamAuthorization.TEST_EXECUTION, true);
                cancelRun(exchange, requireRun(id));
                return;
            }
        }
        if (parts.length == 5 && parts[1].equals("api") && parts[2].equals("runs")
                && parts[4].equals("report") && method.equals("GET")) {
            requireOwnedProjectAccess(exchange, "TEST_RUN", parts[3], TeamAuthorization.TEST_EXECUTION, false);
            serveReport(exchange, requireRun(parts[3]));
            return;
        }
        // === Dependency Check Endpoint ===
        if (path.equals("/api/dependencies") && method.equals("GET")) {
            sendJson(exchange, 200, checkDependencies());
            return;
        }
        // === Dependency Install Endpoint ===
        if (parts.length == 4 && parts[1].equals("api") && parts[2].equals("dependencies")
                && parts[3].equals("install") && method.equals("POST")) {
            requireSameOrigin(exchange);
            installDependencies(exchange);
            return;
        }
        // === CI/CD Config Endpoints ===
        if (path.equals("/api/cicd/config") && method.equals("GET")) {
            String projectId = requireCiCdProjectAccess(exchange, false);
            sendJson(exchange, 200, getCiCdConfig(projectId));
            return;
        }
        if (path.equals("/api/cicd/config") && method.equals("POST")) {
            requireSameOrigin(exchange);
            requireCiCdManage(exchange);
            saveCiCdConfig(exchange);
            return;
        }
        if (parts.length == 4 && parts[1].equals("api") && parts[2].equals("cicd")
                && parts[3].equals("test-connection") && method.equals("POST")) {
            requireSameOrigin(exchange);
            requireCiCdManage(exchange);
            testCiCdConnection(exchange);
            return;
        }
        if (path.equals("/api/cicd/generate") && method.equals("GET")) {
            String projectId = requireCiCdProjectAccess(exchange, false);
            generateWorkflow(exchange, projectId);
            return;
        }
        if (path.equals("/api/cicd/status") && method.equals("GET")) {
            String projectId = requireCiCdProjectAccess(exchange, false);
            sendJson(exchange, 200, getCiCdStatus(projectId));
            return;
        }
        if (parts.length == 4 && parts[1].equals("api") && parts[2].equals("cicd")
                && parts[3].equals("commit") && method.equals("POST")) {
            requireSameOrigin(exchange);
            requireCiCdManage(exchange);
            commitWorkflow(exchange, queryValue(exchange, "projectId"));
            return;
        }
        // === Test Case Management Endpoints ===
        if (path.equals("/api/testcases") && method.equals("GET")) {
            List<TestCaseManager.TestCase> visible = testCaseManager.list().stream()
                    .filter(testCase -> tenantVisibleOwnedResource(exchange, "TEST_CASE", testCase.id(), TeamAuthorization.TEST_CASES, false))
                    .toList();
            sendJson(exchange, 200, visible.stream()
                    .map(this::testCaseToMap)
                    .toList());
            return;
        }
        if (path.equals("/api/testcases") && method.equals("POST")) {
            requireSameOrigin(exchange);
            createTestCase(exchange);
            return;
        }
        if (path.startsWith("/api/testcases/") && method.equals("GET")
                && !path.equals("/api/testcases/stats")
                && !path.endsWith("/history")
                && !path.contains("/history/")
                && !path.endsWith("/archive")
                && !path.endsWith("/restore")) {
            String id = path.substring("/api/testcases/".length());
            TestCaseManager.TestCase testCase = testCaseManager.getById(id);
            if (testCase == null) {
                sendError(exchange, 404, "Test case not found: " + id);
            } else {
                requireOwnedProjectAccess(exchange, "TEST_CASE", id, TeamAuthorization.TEST_CASES, false);
                sendJson(exchange, 200, testCaseToMap(testCase));
            }
            return;
        }
        if (path.equals("/api/testcases/stats") && method.equals("GET")) {
            sendJson(exchange, 200, testCaseManager.stats());
            return;
        }
        if (path.startsWith("/api/testcases/") && path.endsWith("/history") && method.equals("GET")) {
            String id = path.substring("/api/testcases/".length(), path.length() - "/history".length());
            if (testCaseManager.getById(id) == null) {
                sendError(exchange, 404, "Test case not found: " + id);
            } else {
                requireOwnedProjectAccess(exchange, "TEST_CASE", id, TeamAuthorization.TEST_CASES, false);
                sendJson(exchange, 200, testCaseManager.history(id));
            }
            return;
        }
        if (path.startsWith("/api/testcases/") && path.contains("/history/") && path.endsWith("/restore") && method.equals("POST")) {
            requireSameOrigin(exchange);
            String rest = path.substring("/api/testcases/".length(), path.length() - "/restore".length());
            String[] historyParts = rest.split("/history/", 2);
            if (historyParts.length != 2) {
                sendError(exchange, 400, "Invalid test case history path.");
                return;
            }
            String id = historyParts[0];
            requireOwnedProjectAccess(exchange, "TEST_CASE", id, TeamAuthorization.TEST_CASES, true);
            TestCaseManager.TestCase before = testCaseManager.getById(id);
            try {
                TestCaseManager.TestCase restored = testCaseManager.restoreVersion(id, Integer.parseInt(historyParts[1]));
                testCaseManager.recordHistory(id, testCaseToMap(before), testCaseToMap(restored), "Local User");
                sendJson(exchange, 200, testCaseToMap(restored));
            } catch (IllegalArgumentException error) {
                sendError(exchange, 404, error.getMessage());
            }
            return;
        }
        if (path.startsWith("/api/testcases/") && path.endsWith("/archive") && method.equals("POST")) {
            requireSameOrigin(exchange);
            String id = path.substring("/api/testcases/".length(), path.length() - "/archive".length());
            requireOwnedProjectAccess(exchange, "TEST_CASE", id, TeamAuthorization.TEST_CASES, true);
            boolean archived = testCaseManager.archive(id, true);
            if (archived) sendJson(exchange, 200, Map.of("id", id, "archived", true));
            else sendError(exchange, 404, "Test case not found: " + id);
            return;
        }
        if (path.startsWith("/api/testcases/") && path.endsWith("/restore") && method.equals("POST")) {
            requireSameOrigin(exchange);
            String id = path.substring("/api/testcases/".length(), path.length() - "/restore".length());
            requireOwnedProjectAccess(exchange, "TEST_CASE", id, TeamAuthorization.TEST_CASES, true);
            boolean restored = testCaseManager.archive(id, false);
            if (restored) sendJson(exchange, 200, Map.of("id", id, "archived", false));
            else sendError(exchange, 404, "Test case not found: " + id);
            return;
        }
        if (path.equals("/api/testcases/sync") && method.equals("POST")) {
            requireSameOrigin(exchange);
            String projectId = requireCiCdManage(exchange);
            syncTestCases(exchange, projectId);
            return;
        }
        if (path.startsWith("/api/testcases/") && method.equals("PUT")) {
            requireSameOrigin(exchange);
            String id = path.substring("/api/testcases/".length());
            requireOwnedProjectAccess(exchange, "TEST_CASE", id, TeamAuthorization.TEST_CASES, true);
            updateTestCase(exchange, id);
            return;
        }
        if (path.startsWith("/api/testcases/") && method.equals("DELETE")) {
            requireSameOrigin(exchange);
            String id = path.substring("/api/testcases/".length());
            requireOwnedProjectAccess(exchange, "TEST_CASE", id, TeamAuthorization.TEST_CASES, true);
            boolean deleted = testCaseManager.delete(id);
            if (deleted) {
                sendJson(exchange, 200, Map.of("message", "Test case deleted."));
            } else {
                sendError(exchange, 404, "Test case not found: " + id);
            }
            return;
        }
        if (path.equals("/api/testcases/bulk-delete") && method.equals("POST")) {
            requireSameOrigin(exchange);
            bulkDeleteTestCases(exchange);
            return;
        }
        if (path.equals("/api/defects") && method.equals("GET")) {
            String requestedProject = java.util.Objects.requireNonNullElse(queryParameter(exchange, "projectId"), "");
            if (!requestedProject.isBlank()) requireProjectAccess(exchange, requestedProject, TeamAuthorization.DEFECTS, false);
            List<Map<String, Object>> defects = defectManager.list(queryParameter(exchange, "testCaseId"), queryParameter(exchange, "storyId"));
            sendJson(exchange, 200, defects.stream().filter(defect -> requestedProject.isBlank()
                    ? projectAccessForMap(exchange, defect, TeamAuthorization.DEFECTS, false)
                    : requestedProject.equals(String.valueOf(defect.getOrDefault("projectId", "")))).toList());
            return;
        }
        if (path.equals("/api/defects") && method.equals("POST")) {
            requireSameOrigin(exchange);
            Map<String, Object> values = requestObject(exchange);
            requireProjectAccess(exchange, optionalString(values, "projectId"), TeamAuthorization.DEFECTS, true);
            sendJson(exchange, 201, defectManager.create(values, authActorId(exchange), authActorName(exchange)));
            return;
        }
        if (path.startsWith("/api/defects/") && path.endsWith("/links") && method.equals("POST")) {
            requireSameOrigin(exchange);
            String id = path.substring("/api/defects/".length(), path.length() - "/links".length());
            requireDefectProjectAccess(exchange, id, true);
            Map<String, Object> values = requestObject(exchange);
            sendJson(exchange, 200, defectManager.link(id, optionalString(values, "testCaseId"), optionalString(values, "storyId"), optionalString(values, "executionId")));
            return;
        }
        if (path.startsWith("/api/defects/") && method.equals("GET")) {
            String id = path.substring("/api/defects/".length());
            requireDefectProjectAccess(exchange, id, false);
            Map<String, Object> defect = defectManager.list(null).stream().filter(item -> id.equals(stringField(item, "id"))).findFirst().orElse(null);
            if (defect == null) sendError(exchange, 404, "Defect not found: " + id);
            else sendJson(exchange, 200, defect);
            return;
        }
        if (path.startsWith("/api/defects/") && path.contains("/links/") && method.equals("DELETE")) {
            requireSameOrigin(exchange);
            String rest = path.substring("/api/defects/".length());
            int separator = rest.indexOf("/links/");
            requireDefectProjectAccess(exchange, rest.substring(0, separator), true);
            String linkTarget = rest.substring(separator + "/links/".length());
            if (linkTarget.startsWith("story-")) defectManager.unlink(rest.substring(0, separator), null, linkTarget.substring("story-".length()));
            else defectManager.unlink(rest.substring(0, separator), linkTarget);
            sendJson(exchange, 200, Map.of("message", "Defect unlinked."));
            return;
        }
        if (path.startsWith("/api/defects/") && method.equals("PUT")) {
            requireSameOrigin(exchange);
            String id = path.substring("/api/defects/".length());
            requireDefectProjectAccess(exchange, id, true);
            Map<String, Object> values = requestObject(exchange);
            if (values.containsKey("projectId")) requireProjectAccess(exchange, optionalString(values, "projectId"), TeamAuthorization.DEFECTS, true);
            sendJson(exchange, 200, defectManager.update(id, values));
            return;
        }
        if (path.startsWith("/api/user-stories/attachments/") && method.equals("GET")) {
            String attachmentId = path.substring("/api/user-stories/attachments/".length());
            requireOwnedProjectAccess(exchange, "ATTACHMENT", attachmentId, TeamAuthorization.USER_STORIES, false);
            serveUserStoryAttachment(exchange, attachmentId, false);
            return;
        }
        if (path.startsWith("/api/user-stories/attachments/") && method.equals("DELETE")) {
            requireSameOrigin(exchange);
            String attachmentId = path.substring("/api/user-stories/attachments/".length());
            requireOwnedProjectAccess(exchange, "ATTACHMENT", attachmentId, TeamAuthorization.USER_STORIES, true);
            deleteUserStoryAttachment(exchange, attachmentId);
            return;
        }
        if (path.startsWith("/api/user-stories/related-links/") && method.equals("PUT")) {
            requireSameOrigin(exchange);
            Map<String, Object> values = requestObject(exchange);
            String linkId = path.substring("/api/user-stories/related-links/".length());
            UserStoryStore.RelatedLink existingLink = requireRelatedLinkAccess(exchange, linkId, true);
            validateRelatedWorkItemTarget(stringField(values, "url"), optionalString(values, "type"));
            UserStoryStore.RelatedLink updatedLink = userStoryStore.updateRelatedLink(linkId, stringField(values, "title"), stringField(values, "url"), optionalString(values, "type"));
            userStoryStore.recordActivity(updatedLink.storyId(), "Link changed", updatedLink.title(), authActorId(exchange), authActorName(exchange));
            sendJson(exchange, 200, relatedLinkToMap(updatedLink));
            return;
        }
        if (path.startsWith("/api/user-stories/related-links/") && method.equals("DELETE")) {
            requireSameOrigin(exchange);
            String linkId = path.substring("/api/user-stories/related-links/".length());
            UserStoryStore.RelatedLink existingLink = requireRelatedLinkAccess(exchange, linkId, true);
            userStoryStore.removeRelatedLink(linkId);
            if (existingLink != null) userStoryStore.recordActivity(existingLink.storyId(), "Link removed", existingLink.title(), authActorId(exchange), authActorName(exchange));
            sendJson(exchange, 204, Map.of());
            return;
        }
        // === Phase 1 User Story Management Endpoints ===
        if (path.equals("/api/user-stories/projects") && method.equals("GET")) {
            sendJson(exchange, 200, userStoryStore.listProjects(queryBoolean(exchange, "includeArchived")).stream()
                    .filter(project -> tenantVisibleProject(exchange, project.id()))
                    .map(this::projectToMap).toList());
            return;
        }
        if (path.equals("/api/user-stories/projects") && method.equals("POST")) {
            requireSameOrigin(exchange);
            requireAuthenticatedAdmin(exchange);
            sendJson(exchange, 201, projectToMap(createUserStoryProject(exchange)));
            return;
        }
        if (path.startsWith("/api/user-stories/projects/") && method.equals("PUT")) {
            requireSameOrigin(exchange);
            requireAuthenticatedAdmin(exchange);
            sendJson(exchange, 200, projectToMap(updateUserStoryProject(exchange, path.substring("/api/user-stories/projects/".length()))));
            return;
        }
        if (path.startsWith("/api/user-stories/projects/") && method.equals("POST") && path.endsWith("/archive")) {
            requireSameOrigin(exchange);
            requireAuthenticatedAdmin(exchange);
            String id = path.substring("/api/user-stories/projects/".length(), path.length() - "/archive".length());
            sendJson(exchange, 200, projectToMap(userStoryStore.archiveProject(id, true)));
            return;
        }
        if (path.startsWith("/api/user-stories/projects/") && method.equals("POST") && path.endsWith("/restore")) {
            requireSameOrigin(exchange);
            requireAuthenticatedAdmin(exchange);
            String id = path.substring("/api/user-stories/projects/".length(), path.length() - "/restore".length());
            sendJson(exchange, 200, projectToMap(userStoryStore.archiveProject(id, false)));
            return;
        }
        if (path.equals("/api/user-stories/epics") && method.equals("GET")) {
            requireProjectAccess(exchange, queryValue(exchange, "projectId"), TeamAuthorization.USER_STORIES, false);
            sendJson(exchange, 200, userStoryStore.listEpics(queryValue(exchange, "projectId"), queryBoolean(exchange, "includeArchived")).stream().map(this::epicToMap).toList());
            return;
        }
        if (path.equals("/api/user-stories/epics") && method.equals("POST")) {
            requireSameOrigin(exchange);
            sendJson(exchange, 201, epicToMap(createUserStoryEpic(exchange)));
            return;
        }
        if (path.startsWith("/api/user-stories/epics/") && method.equals("PUT")) {
            requireSameOrigin(exchange);
            requireEpicProjectAccess(exchange, path.substring("/api/user-stories/epics/".length()), true);
            sendJson(exchange, 200, epicToMap(updateUserStoryEpic(exchange, path.substring("/api/user-stories/epics/".length()))));
            return;
        }
        if (path.startsWith("/api/user-stories/epics/") && method.equals("POST") && path.endsWith("/archive")) {
            requireSameOrigin(exchange);
            String id = path.substring("/api/user-stories/epics/".length(), path.length() - "/archive".length());
            requireEpicProjectAccess(exchange, id, true);
            sendJson(exchange, 200, epicToMap(userStoryStore.archiveEpic(id, true)));
            return;
        }
        if (path.startsWith("/api/user-stories/epics/") && method.equals("POST") && path.endsWith("/restore")) {
            requireSameOrigin(exchange);
            String id = path.substring("/api/user-stories/epics/".length(), path.length() - "/restore".length());
            requireEpicProjectAccess(exchange, id, true);
            sendJson(exchange, 200, epicToMap(userStoryStore.archiveEpic(id, false)));
            return;
        }
        if (path.equals("/api/user-stories/stories") && method.equals("GET")) {
            String search = queryValue(exchange, "search");
            int offset = queryInt(exchange, "offset", 0);
            int limit = queryInt(exchange, "limit", 50);
            String projectId = queryValue(exchange, "projectId");
            requireProjectAccess(exchange, projectId, TeamAuthorization.USER_STORIES, false);
            String sprintId = queryValue(exchange, "sprintId");
            boolean includeArchived = queryBoolean(exchange, "includeArchived");
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("items", userStoryStore.listStories(projectId, sprintId, search, offset, limit, includeArchived).stream().map(this::userStoryToMap).toList());
            response.put("total", userStoryStore.countStories(projectId, sprintId, search, includeArchived));
            response.put("offset", Math.max(0, offset));
            response.put("limit", Math.min(100, Math.max(1, limit)));
            sendJson(exchange, 200, response);
            return;
        }
        if (path.equals("/api/user-stories/test-case-links") && method.equals("GET")) {
            Map<String, UserStoryStore.Story> stories = userStoryStore.listStories(null, null, null, 0, 100, true).stream()
                    .collect(java.util.stream.Collectors.toMap(UserStoryStore.Story::id, story -> story, (left, right) -> left, LinkedHashMap::new));
            List<Map<String, Object>> links = userStoryStore.listTestCaseLinks().stream().filter(link -> {
                UserStoryStore.Story story = stories.get(link.storyId());
                return story != null && tenantVisibleProject(exchange, story.projectId());
            }).map(link -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("testCaseId", link.testCaseId());
                item.put("storyId", link.storyId());
                UserStoryStore.Story story = stories.get(link.storyId());
                if (story != null) item.put("story", userStoryToMap(story));
                return item;
            }).toList();
            sendJson(exchange, 200, links);
            return;
        }
        if (path.equals("/api/user-stories/work-items") && method.equals("GET")) {
            String query = queryValue(exchange, "search");
            requireProjectAccess(exchange, queryValue(exchange, "projectId"), TeamAuthorization.USER_STORIES, false);
            sendJson(exchange, 200, userStoryStore.searchWorkItems(queryValue(exchange, "projectId"), query, queryInt(exchange, "limit", 25)).stream().map(this::workItemToMap).toList());
            return;
        }
        if (path.equals("/api/user-stories/stories") && method.equals("POST")) {
            requireSameOrigin(exchange);
            sendJson(exchange, 201, userStoryToMap(createUserStory(exchange)));
            return;
        }
        if (path.startsWith("/api/user-stories/stories/") && method.equals("PUT") && !path.contains("/comments/")) {
            requireSameOrigin(exchange);
            requireStoryProjectAccess(exchange, path.substring("/api/user-stories/stories/".length()), true);
            sendJson(exchange, 200, userStoryToMap(updateUserStory(exchange, path.substring("/api/user-stories/stories/".length()))));
            return;
        }
        if (path.startsWith("/api/user-stories/stories/") && method.equals("POST") && path.endsWith("/status")) {
            requireSameOrigin(exchange);
            String id = path.substring("/api/user-stories/stories/".length(), path.length() - "/status".length());
            requireStoryProjectAccess(exchange, id, true);
            Map<String, Object> values = requestObject(exchange);
            sendJson(exchange, 200, userStoryToMap(userStoryStore.updateStoryStatus(id, stringField(values, "status"), optionalString(values, "projectId"))));
            return;
        }
        if (path.startsWith("/api/user-stories/stories/") && path.endsWith("/activity") && method.equals("GET")) {
            String storyId = path.substring("/api/user-stories/stories/".length(), path.length() - "/activity".length());
            requireStoryProjectAccess(exchange, storyId, false);
            sendJson(exchange, 200, userStoryStore.listActivity(storyId).stream().map(this::activityToMap).toList());
            return;
        }
        if (path.startsWith("/api/user-stories/stories/") && path.endsWith("/comments") && method.equals("POST")) {
            requireSameOrigin(exchange);
            String storyId = path.substring("/api/user-stories/stories/".length(), path.length() - "/comments".length());
            requireStoryProjectAccess(exchange, storyId, true);
            Map<String, Object> values = requestObject(exchange);
            sendJson(exchange, 201, activityToMap(userStoryStore.addComment(storyId, stringField(values, "comment"), authActorId(exchange), authActorName(exchange))));
            return;
        }
        if (path.startsWith("/api/user-stories/stories/") && path.contains("/comments/") && (method.equals("PUT") || method.equals("DELETE"))) {
            requireSameOrigin(exchange);
            String marker = "/comments/";
            int markerIndex = path.indexOf(marker);
            String storyId = path.substring("/api/user-stories/stories/".length(), markerIndex);
            String commentId = path.substring(markerIndex + marker.length());
            requireStoryProjectAccess(exchange, storyId, true);
            requireCommentMutationAccess(exchange, storyId, commentId);
            if (method.equals("PUT")) {
                Map<String, Object> values = requestObject(exchange);
                sendJson(exchange, 200, activityToMap(userStoryStore.updateComment(storyId, commentId, stringField(values, "comment"), authActorId(exchange), authActorName(exchange))));
            } else {
                sendJson(exchange, 200, activityToMap(userStoryStore.deleteComment(storyId, commentId, authActorId(exchange), authActorName(exchange))));
            }
            return;
        }
        if (path.startsWith("/api/user-stories/stories/") && path.endsWith("/attachments") && method.equals("GET")) {
            String storyId = path.substring("/api/user-stories/stories/".length(), path.length() - "/attachments".length());
            requireStoryProjectAccess(exchange, storyId, false);
            sendJson(exchange, 200, userStoryStore.listAttachments(storyId).stream().map(this::attachmentToMap).toList());
            return;
        }
        if (path.startsWith("/api/user-stories/stories/") && path.endsWith("/attachments") && method.equals("POST")) {
            requireSameOrigin(exchange);
            String storyId = path.substring("/api/user-stories/stories/".length(), path.length() - "/attachments".length());
            requireStoryProjectAccess(exchange, storyId, true);
            sendJson(exchange, 201, uploadUserStoryAttachment(exchange, storyId));
            return;
        }
        if (path.startsWith("/api/user-stories/stories/") && path.endsWith("/related-links") && method.equals("GET")) {
            String storyId = path.substring("/api/user-stories/stories/".length(), path.length() - "/related-links".length());
            requireStoryProjectAccess(exchange, storyId, false);
            sendJson(exchange, 200, userStoryStore.listRelatedLinks(storyId).stream().map(this::relatedLinkToMap).toList());
            return;
        }
        if (path.startsWith("/api/user-stories/stories/") && path.endsWith("/related-links") && method.equals("POST")) {
            requireSameOrigin(exchange);
            String storyId = path.substring("/api/user-stories/stories/".length(), path.length() - "/related-links".length());
            requireStoryProjectAccess(exchange, storyId, true);
            Map<String, Object> values = requestObject(exchange);
            validateRelatedWorkItemTarget(stringField(values, "url"), optionalString(values, "type"));
            sendJson(exchange, 201, relatedLinkToMap(userStoryStore.addRelatedLink(storyId, stringField(values, "title"), stringField(values, "url"), optionalString(values, "type"))));
            return;
        }
        if (path.startsWith("/api/user-stories/stories/") && path.endsWith("/test-cases") && method.equals("GET")) {
            String storyId = path.substring("/api/user-stories/stories/".length(), path.length() - "/test-cases".length());
            requireStoryProjectAccess(exchange, storyId, false);
            String search = queryValue(exchange, "search");
            int offset = queryInt(exchange, "offset", 0);
            int limit = Math.min(100, Math.max(1, queryInt(exchange, "limit", 25)));
            List<Map<String, Object>> items = linkedTestCases(storyId, search, offset, limit);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("items", items);
            response.put("total", linkedTestCases(storyId, search, 0, 10000).size());
            response.put("offset", Math.max(0, offset));
            response.put("limit", limit);
            sendJson(exchange, 200, response);
            return;
        }
        if (path.startsWith("/api/user-stories/stories/") && path.endsWith("/test-cases") && method.equals("POST")) {
            requireSameOrigin(exchange);
            String storyId = path.substring("/api/user-stories/stories/".length(), path.length() - "/test-cases".length());
            requireStoryProjectAccess(exchange, storyId, true);
            Map<String, Object> values = requestObject(exchange);
            String testCaseId = stringField(values, "testCaseId");
            if (testCaseManager.list().stream().noneMatch(testCase -> testCase.id().equals(testCaseId))) {
                throw new IllegalArgumentException("Test case not found: " + testCaseId);
            }
            sendJson(exchange, 201, linkToMap(userStoryStore.linkTestCase(storyId, testCaseId)));
            return;
        }
        if (path.startsWith("/api/user-stories/stories/") && path.contains("/test-cases/") && method.equals("DELETE")) {
            requireSameOrigin(exchange);
            int marker = path.indexOf("/test-cases/");
            String storyId = path.substring("/api/user-stories/stories/".length(), marker);
            String testCaseId = path.substring(marker + "/test-cases/".length());
            requireStoryProjectAccess(exchange, storyId, true);
            userStoryStore.unlinkTestCase(storyId, testCaseId);
            sendJson(exchange, 204, Map.of());
            return;
        }
        if (path.startsWith("/api/user-stories/stories/") && method.equals("POST") && path.endsWith("/archive")) {
            requireSameOrigin(exchange);
            String id = path.substring("/api/user-stories/stories/".length(), path.length() - "/archive".length());
            requireStoryProjectAccess(exchange, id, true);
            sendJson(exchange, 200, userStoryToMap(userStoryStore.archiveStory(id, true)));
            return;
        }
        if (path.startsWith("/api/user-stories/stories/") && method.equals("POST") && path.endsWith("/restore")) {
            requireSameOrigin(exchange);
            String id = path.substring("/api/user-stories/stories/".length(), path.length() - "/restore".length());
            requireStoryProjectAccess(exchange, id, true);
            sendJson(exchange, 200, userStoryToMap(userStoryStore.archiveStory(id, false)));
            return;
        }
        // === Phase 2 Sprint and Kanban endpoints ===
        if (path.equals("/api/user-stories/sprints") && method.equals("GET")) {
            requireProjectAccess(exchange, queryValue(exchange, "projectId"), TeamAuthorization.SPRINT_BOARD, false);
            sendJson(exchange, 200, userStoryStore.listSprints(queryValue(exchange, "projectId"), queryBoolean(exchange, "includeCompleted")).stream().map(this::sprintToMap).toList());
            return;
        }
        if (path.equals("/api/user-stories/sprints") && method.equals("POST")) {
            requireSameOrigin(exchange);
            sendJson(exchange, 201, sprintToMap(createSprint(exchange)));
            return;
        }
        if (path.startsWith("/api/user-stories/sprints/") && method.equals("PUT")) {
            requireSameOrigin(exchange);
            requireSprintProjectAccess(exchange, path.substring("/api/user-stories/sprints/".length()), true);
            sendJson(exchange, 200, sprintToMap(updateSprint(exchange, path.substring("/api/user-stories/sprints/".length()))));
            return;
        }
        if (path.startsWith("/api/user-stories/sprints/") && method.equals("POST") && path.endsWith("/start")) {
            requireSameOrigin(exchange);
            String id = path.substring("/api/user-stories/sprints/".length(), path.length() - "/start".length());
            requireSprintProjectAccess(exchange, id, true);
            sendJson(exchange, 200, sprintToMap(userStoryStore.startSprint(id)));
            return;
        }
        if (path.startsWith("/api/user-stories/sprints/") && method.equals("POST") && path.endsWith("/complete")) {
            requireSameOrigin(exchange);
            String id = path.substring("/api/user-stories/sprints/".length(), path.length() - "/complete".length());
            requireSprintProjectAccess(exchange, id, true);
            sendJson(exchange, 200, sprintToMap(userStoryStore.completeSprint(id)));
            return;
        }
        if (path.startsWith("/api/user-stories/sprints/") && path.endsWith("/stories") && method.equals("POST")) {
            requireSameOrigin(exchange);
            String sprintId = path.substring("/api/user-stories/sprints/".length(), path.length() - "/stories".length());
            Map<String, Object> values = requestObject(exchange);
            requireSprintProjectAccess(exchange, sprintId, true);
            requireStoryProjectAccess(exchange, stringField(values, "storyId"), true);
            sendJson(exchange, 200, userStoryToMap(userStoryStore.assignStoryToSprint(sprintId, stringField(values, "storyId"))));
            return;
        }
        if (path.startsWith("/api/user-stories/sprints/") && path.endsWith("/stories") && method.equals("DELETE")) {
            requireSameOrigin(exchange);
            Map<String, Object> values = requestObject(exchange);
            requireStoryProjectAccess(exchange, stringField(values, "storyId"), true);
            sendJson(exchange, 200, userStoryToMap(userStoryStore.removeStoryFromSprint(stringField(values, "storyId"))));
            return;
        }
        if (path.equals("/api/user-stories/board") && method.equals("GET")) {
            String projectId = queryValue(exchange, "projectId");
            requireProjectAccess(exchange, projectId, TeamAuthorization.SPRINT_BOARD, false);
            String sprintId = queryValue(exchange, "sprintId");
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("items", userStoryStore.listStories(projectId, sprintId, queryValue(exchange, "search"), queryInt(exchange, "offset", 0), queryInt(exchange, "limit", 100), false).stream().map(this::userStoryToMap).toList());
            response.put("total", userStoryStore.countStories(projectId, sprintId, queryValue(exchange, "search"), false));
            sendJson(exchange, 200, response);
            return;
        }
        // === Story Endpoints ===
        if (path.equals("/api/stories") && method.equals("GET")) {
            sendJson(exchange, 200, testCaseManager.listStories().stream()
                    .map(this::storyToMap)
                    .toList());
            return;
        }
        if (path.equals("/api/stories") && method.equals("POST")) {
            requireSameOrigin(exchange);
            createStory(exchange);
            return;
        }
        if (path.startsWith("/api/stories/") && method.equals("PUT")) {
            requireSameOrigin(exchange);
            String id = path.substring("/api/stories/".length());
            updateStory(exchange, id);
            return;
        }
        if (path.startsWith("/api/stories/") && method.equals("DELETE")) {
            requireSameOrigin(exchange);
            String id = path.substring("/api/stories/".length());
            boolean deleted = testCaseManager.deleteStory(id);
            if (deleted) {
                sendJson(exchange, 200, Map.of("message", "Story deleted."));
            } else {
                sendError(exchange, 404, "Story not found: " + id);
            }
            return;
        }
        if (path.equals("/api/stories/stats") && method.equals("GET")) {
            sendJson(exchange, 200, testCaseManager.getStoryStats());
            return;
        }
        if (path.equals("/api/stories/testcases") && method.equals("GET")) {
            // Query param: storyId
            String storyId = null;
            String query = exchange.getRequestURI().getQuery();
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] qp = param.split("=");
                    if (qp.length == 2 && "storyId".equals(qp[0])) {
                        storyId = java.net.URLDecoder.decode(qp[1], StandardCharsets.UTF_8);
                    }
                }
            }
            if (storyId != null) {
                sendJson(exchange, 200, testCaseManager.getTestCasesForStory(storyId).stream()
                        .map(this::testCaseToMap)
                        .toList());
            } else {
                sendError(exchange, 400, "Missing storyId parameter.");
            }
            return;
        }
        // === Test Suite Endpoints ===
        if (path.equals("/api/suites") && method.equals("GET")) {
            sendJson(exchange, 200, testCaseManager.listSuites().stream()
                    .map(this::suiteToMap)
                    .toList());
            return;
        }
        if (path.equals("/api/suites") && method.equals("POST")) {
            requireSameOrigin(exchange);
            createSuite(exchange);
            return;
        }
        if (path.startsWith("/api/suites/") && method.equals("PUT")) {
            requireSameOrigin(exchange);
            String id = path.substring("/api/suites/".length());
            updateSuite(exchange, id);
            return;
        }
        if (path.startsWith("/api/suites/") && method.equals("DELETE")) {
            requireSameOrigin(exchange);
            String id = path.substring("/api/suites/".length());
            boolean deleted = testCaseManager.deleteSuite(id);
            if (deleted) {
                sendJson(exchange, 200, Map.of("message", "Test suite deleted."));
            } else {
                sendError(exchange, 404, "Test suite not found: " + id);
            }
            return;
        }
        if (parts.length == 5 && parts[1].equals("api") && parts[2].equals("runs")
                && parts[4].equals("analyze") && method.equals("POST")) {
            // Return run data for AI analysis (client-side)
            RunEntry entry = requireRun(parts[3]);
            Map<String, Object> analyzeData = new LinkedHashMap<>();
            analyzeData.put("id", entry.id);
            analyzeData.put("kind", entry.kindName);
            analyzeData.put("selection", entry.selection);
            analyzeData.put("targetUrl", entry.targetUrl);
            analyzeData.put("status", entry.status);
            analyzeData.put("error", entry.error);
            analyzeData.put("logs", entry.logs());
            if (entry.result != null && entry.result.summary() != null) {
                RunSummary summary = entry.result.summary();
                Map<String, Object> summaryMap = new LinkedHashMap<>();
                summaryMap.put("total", summary.total());
                summaryMap.put("passed", summary.passed());
                summaryMap.put("failed", summary.failed());
                summaryMap.put("skipped", summary.skipped());
                List<Map<String, Object>> detailsList = new ArrayList<>();
                for (RunDetail detail : summary.details()) {
                    Map<String, Object> detailMap = new LinkedHashMap<>();
                    detailMap.put("name", detail.name());
                    detailMap.put("status", detail.status());
                    detailMap.put("errorMessage", detail.errorMessage());
                    detailsList.add(detailMap);
                }
                summaryMap.put("details", detailsList);
                analyzeData.put("summary", summaryMap);
            }
            sendJson(exchange, 200, analyzeData);
            return;
        }
        // === AI Assistant Endpoint ===
        if (path.equals("/api/ai/ask") && method.equals("POST")) {
            requireSameOrigin(exchange);
            handleAiAsk(exchange);
            return;
        }
        // === Ticket Management Endpoints ===
        if (path.equals("/api/tickets") && method.equals("GET")) {
            sendJson(exchange, 200, getTickets());
            return;
        }
        if (path.equals("/api/tickets") && method.equals("POST")) {
            requireSameOrigin(exchange);
            handleCreateTicket(exchange);
            return;
        }
        sendError(exchange, 404, "Not found.");
    }

    private Map<String, Object> catalogResponse(HttpExchange exchange) throws IOException {
        List<Path> allPaths = new ArrayList<>();
        for (SharedPathConfig.SharedPathEntry configured : sharedPathConfig.getSharedPaths()) {
            if (ownedProjectAccess(exchange, "SHARED_PATH", configured.getName(), TeamAuthorization.TEST_EXECUTION, false)
                    && Files.isDirectory(Path.of(configured.getPath()))) {
                allPaths.add(Path.of(configured.getPath()).toRealPath());
            }
        }
        for (RemoteImportConfig.ImportEntry configured : remoteImportConfig.getImports()) {
            if (ownedProjectAccess(exchange, "REMOTE_IMPORT", configured.getId(), TeamAuthorization.TEST_EXECUTION, false)) {
                try { allPaths.add(remoteImportConfig.resolveImportDirectory(configured)); }
                catch (IOException ignored) { /* unresolved or unsafe legacy import */ }
            }
        }
        List<Map<String, Object>> assets = executionService.pluginRegistry()
                .discoverAllAssets(projectRoot, allPaths);
        String projectIdentity = stableHash(projectRoot.toAbsolutePath().normalize().toString());
        String sourceProject = projectRoot.getFileName() == null ? projectRoot.toString() : projectRoot.getFileName().toString();
        List<Map<String, Object>> mappings = automationMappingManager.list();
        for (Map<String, Object> asset : assets) {
            String kind = String.valueOf(asset.getOrDefault("kind", "unknown"));
            String selection = String.valueOf(asset.getOrDefault("selection", ""));
            String assetId = "automation-" + stableHash(projectIdentity + "\n" + kind + "\n" + selection);
            asset.put("assetId", assetId);
            asset.put("projectIdentity", projectIdentity);
            asset.put("sourceProject", sourceProject);
            List<String> linkedTestCaseIds = mappings.stream()
                    .filter(mapping -> assetId.equals(String.valueOf(mapping.get("assetId"))))
                    .map(mapping -> String.valueOf(mapping.get("testCaseId")))
                    .distinct().toList();
            asset.put("linkedTestCaseIds", linkedTestCaseIds);
            asset.put("mappingStatus", linkedTestCaseIds.isEmpty() ? "unmapped" : "mapped");
            if ("cucumber".equalsIgnoreCase(kind)) asset.put("scenarios", cucumberScenarios(asset));
            Map<String, Object> latestExecution = automationExecutionManager.latest(null, assetId);
            asset.put("latestExecutionStatus", latestExecution == null ? null : latestExecution.get("status"));
            asset.put("latestExecutionId", latestExecution == null ? null : latestExecution.get("executionId"));
        }
        List<Map<String, String>> sharedPathsList = new ArrayList<>();
        for (SharedPathConfig.SharedPathEntry entry : sharedPathConfig.getSharedPaths()) {
            if (!isGlobalAdmin(exchange)
                    && !ownedProjectAccess(exchange, "SHARED_PATH", entry.getName(), TeamAuthorization.TEST_EXECUTION, false)) {
                continue;
            }
            Map<String, String> entryMap = new LinkedHashMap<>();
            entryMap.put("name", entry.getName());
            entryMap.put("path", entry.getPath());
            sharedPathsList.add(entryMap);
        }
        // Add imports list
        List<Map<String, Object>> importsList = new ArrayList<>();
        for (RemoteImportConfig.ImportEntry entry : remoteImportConfig.getImports()) {
            if (!isGlobalAdmin(exchange)
                    && !ownedProjectAccess(exchange, "REMOTE_IMPORT", entry.getId(), TeamAuthorization.TEST_EXECUTION, false)) {
                continue;
            }
            Map<String, Object> entryMap = new LinkedHashMap<>();
            entryMap.put("id", entry.getId());
            entryMap.put("name", entry.getName());
            entryMap.put("type", entry.getType());
            entryMap.put("source", entry.getSource());
            entryMap.put("importedAt", entry.getImportedAt());
            importsList.add(entryMap);
        }
        return Map.of("assets", assets, "sharedPaths", sharedPathsList, "imports", importsList);
    }

    private static String stableHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) result.append(String.format(Locale.ROOT, "%02x", item));
            return result.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is not available.", error);
        }
    }

    private void addSharedPath(HttpExchange exchange) throws Exception {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase().startsWith("application/json")) {
            throw new IllegalArgumentException("Send shared path settings as application/json.");
        }
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("Shared path settings are too large.");
        }
        Object parsed = JsonParser.parse(new String(body, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Shared path settings must be a JSON object.");
        }
        String projectId = stringField(values, "projectId").trim();
        requireProjectAccess(exchange, projectId, TeamAuthorization.TEST_EXECUTION, true);
        // Name is optional — derived from path if not provided
        String name = null;
        Object nameObj = values.get("name");
        if (nameObj instanceof String nameText && !nameText.isBlank()) {
            name = nameText;
        }
        String path = stringField(values, "path");
        Path pathObj = Path.of(path);
        if (!pathObj.isAbsolute()) {
            throw new IllegalArgumentException("Shared path must be an absolute path (e.g., C:\\shared\\tests). Relative paths are not supported.");
        }
        if (!Files.isDirectory(pathObj) || !Files.isReadable(pathObj)) {
            throw new IllegalArgumentException("Shared path must be an existing readable directory.");
        }
        path = pathObj.toRealPath().toString();
        // Derive name from path if not provided
        if (name == null || name.isBlank()) {
            name = pathObj.getFileName().toString();
        }
        sharedPathConfig.addSharedPath(name, path);
        projectOwnershipManager.assign("SHARED_PATH", name, projectId, "SHARED_PATH_CREATE", validProjectIds());
        sendJson(exchange, 200, Map.of("message", "Shared path '" + name + "' added."));
    }

    private void scanSharedPath(HttpExchange exchange, String name) throws IOException {
        SharedPathConfig.SharedPathEntry entry = sharedPathConfig.findSharedPath(name);
        if (entry == null) {
            sendError(exchange, 404, "Shared path '" + name + "' not found.");
            return;
        }
        Path sharedPath = Path.of(entry.getPath()).toRealPath();
        if (!Files.isDirectory(sharedPath)) {
            String hint;
            if (!sharedPath.isAbsolute()) {
                hint = "Directory not found: '" + entry.getPath() + "'. Use an absolute path (e.g., C:\\shared\\tests).";
            } else {
                hint = "Directory not found: " + entry.getPath();
            }
            sendError(exchange, 404, hint);
            return;
        }
        // Scan the directory and detect frameworks
        List<Path> sharedPaths = List.of(sharedPath);
        List<Map<String, Object>> assets = executionService.pluginRegistry()
                .discoverAllAssets(projectRoot, sharedPaths);
        // Filter to only assets from this shared path
        String sharedPathStr = sharedPath.toString().replace('\\', '/');
        List<Map<String, Object>> pathAssets = new ArrayList<>();
        for (Map<String, Object> asset : assets) {
            String selection = (String) asset.get("selection");
            if (selection != null && selection.contains(sharedPathStr)) {
                Path selected = Path.of(selection).toAbsolutePath().normalize();
                if (!selected.toRealPath().startsWith(sharedPath)) continue;
                pathAssets.add(asset);
            }
        }
        // Count by framework
        Map<String, Integer> frameworkCounts = new LinkedHashMap<>();
        for (Map<String, Object> asset : pathAssets) {
            String kind = (String) asset.get("kind");
            frameworkCounts.merge(kind, 1, Integer::sum);
        }
        // Build framework info with icons
        List<Map<String, String>> frameworks = new ArrayList<>();
        for (Map.Entry<String, Integer> fc : frameworkCounts.entrySet()) {
            Map<String, String> fw = new LinkedHashMap<>();
            fw.put("name", fc.getKey());
            switch (fc.getKey()) {
                case "cucumber": fw.put("label", "Cucumber/Selenium"); fw.put("icon", "🎯"); break;
                case "newman": fw.put("label", "Postman/Newman"); fw.put("icon", "🔌"); break;
                case "playwright": fw.put("label", "Playwright Java"); fw.put("icon", "🎭"); break;
                case "karate": fw.put("label", "Karate DSL"); fw.put("icon", "🥋"); break;
                case "wdio": fw.put("label", "WebdriverIO"); fw.put("icon", "🌐"); break;
                case "restassured": fw.put("label", "RestAssured"); fw.put("icon", "⚡"); break;
                case "soapui": fw.put("label", "SoapUI/ReadyAPI"); fw.put("icon", "🧪"); break;
                case "jmeter": fw.put("label", "Apache JMeter"); fw.put("icon", "⏱️"); break;
                case "jmetercli": fw.put("label", "JMeter CLI"); fw.put("icon", "⏱️"); break;
                case "appium": fw.put("label", "Appium Mobile"); fw.put("icon", "📱"); break;
                case "cypress": fw.put("label", "Cypress"); fw.put("icon", "🌲"); break;
                case "gatling": fw.put("label", "Gatling"); fw.put("icon", "🔥"); break;
                case "database": fw.put("label", "Database"); fw.put("icon", "🗄️"); break;
                case "testng": fw.put("label", "TestNG"); fw.put("icon", "🔶"); break;
                case "pytest": fw.put("label", "Pytest"); fw.put("icon", "🐍"); break;
                case "robot": fw.put("label", "Robot Framework"); fw.put("icon", "🤖"); break;
                default: fw.put("label", fc.getKey()); fw.put("icon", "📁");
           }
            fw.put("count", String.valueOf(fc.getValue()));
            frameworks.add(fw);
        }
        sendJson(exchange, 200, Map.of(
                "name", name,
                "path", entry.getPath(),
                "totalAssets", pathAssets.size(),
                "frameworks", frameworks,
                "assets", pathAssets
        ));
    }

    private void importFromGitHub(HttpExchange exchange) throws Exception {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase().startsWith("application/json")) {
            throw new IllegalArgumentException("Send GitHub URL as application/json.");
        }
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("Request body is too large.");
        }
        Object parsed = JsonParser.parse(new String(body, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Request must be a JSON object.");
        }
        String projectId = stringField(values, "projectId").trim();
        requireProjectAccess(exchange, projectId, TeamAuthorization.TEST_EXECUTION, true);
        String repoUrl = stringField(values, "repoUrl");
        if (repoUrl.isBlank()) {
            throw new IllegalArgumentException("GitHub repository URL is required.");
        }
        // Validate URL format
        if (!repoUrl.startsWith("https://github.com/") && !repoUrl.startsWith("git@github.com:")) {
            throw new IllegalArgumentException("Only GitHub repositories are supported (https://github.com/... or git@github.com:...).");
        }
        try {
            RemoteImportConfig.ImportEntry entry = remoteImportConfig.cloneGitHubRepo(repoUrl);
            projectOwnershipManager.assign("REMOTE_IMPORT", entry.getId(), projectId, "REMOTE_IMPORT_CREATE", validProjectIds());
            sendJson(exchange, 200, Map.of(
                    "message", "Repository cloned successfully.",
                    "id", entry.getId(),
                    "name", entry.getName()
            ));
        } catch (IOException | InterruptedException e) {
            sendError(exchange, 500, "Failed to clone repository: " + e.getMessage());
        }
    }

    private void importFromZip(HttpExchange exchange) throws Exception {
        // Parse multipart form data for ZIP file upload
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase().contains("multipart/form-data")) {
            throw new IllegalArgumentException("Send ZIP file as multipart/form-data.");
        }

        String projectId = queryParameter(exchange, "projectId");
        requireProjectAccess(exchange, projectId, TeamAuthorization.TEST_EXECUTION, true);

        long contentLength = -1L;
        String contentLengthHeader = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLengthHeader != null) {
            try { contentLength = Long.parseLong(contentLengthHeader); }
            catch (NumberFormatException ignored) { /* bounded read below remains authoritative */ }
        }
        long maxArchiveBytes = RemoteImportConfig.defaultZipImportLimits().maxArchiveBytes();
        if (contentLength > maxArchiveBytes) {
            throw new IllegalArgumentException("ZIP file is too large (max 50MB).");
        }

        // Read the request body
        byte[] body = exchange.getRequestBody().readNBytes(50 * 1024 * 1024 + 1); // 50MB max
        if (body.length > 50 * 1024 * 1024) {
            throw new IllegalArgumentException("ZIP file is too large (max 50MB).");
        }

        // Parse multipart manually
        String boundary = extractBoundary(contentType);
        if (boundary == null) {
            throw new IllegalArgumentException("Invalid multipart form data.");
        }

        // Find the ZIP file part
        String bodyStr = new String(body, StandardCharsets.ISO_8859_1);
        String fileName = extractFileName(bodyStr, boundary);
        if (fileName == null) {
            throw new IllegalArgumentException("No ZIP file found in the upload.");
        }

        // Extract the ZIP data from multipart
        byte[] zipData = extractZipData(body, boundary, fileName);
        if (zipData == null || zipData.length == 0) {
            throw new IllegalArgumentException("Failed to extract ZIP data from upload.");
        }

        // Write ZIP to temp file
        Path tempZip = projectRoot.resolve("target").resolve("uploads")
                .resolve(UUID.randomUUID().toString().substring(0, 8) + ".zip").normalize();
        Files.createDirectories(tempZip.getParent());
        Files.write(tempZip, zipData);

        try {
            RemoteImportConfig.ImportEntry entry = remoteImportConfig.extractZipFile(tempZip, fileName);
            projectOwnershipManager.assign("REMOTE_IMPORT", entry.getId(), projectId, "REMOTE_IMPORT_CREATE", validProjectIds());
            sendJson(exchange, 200, Map.of(
                    "message", "ZIP file extracted successfully.",
                    "id", entry.getId(),
                    "name", entry.getName()
            ));
        } finally {
            Files.deleteIfExists(tempZip);
        }
    }

    private void scanImport(HttpExchange exchange, String id) throws IOException {
        RemoteImportConfig.ImportEntry entry = remoteImportConfig.findImport(id);
        if (entry == null) {
            sendError(exchange, 404, "Import '" + id + "' not found.");
            return;
        }
        Path importPath;
        try { importPath = remoteImportConfig.resolveImportDirectory(entry); }
        catch (IOException error) {
            sendError(exchange, 404, "Import directory not found.");
            return;
        }
        // Scan the directory
        List<Path> paths = List.of(importPath);
        List<Map<String, Object>> assets = executionService.pluginRegistry()
                .discoverAllAssets(projectRoot, paths);
        String importPathStr = importPath.toString().replace('\\', '/');
        List<Map<String, Object>> pathAssets = new ArrayList<>();
        for (Map<String, Object> asset : assets) {
            String selection = (String) asset.get("selection");
            if (selection != null && selection.contains(importPathStr)) {
                Path selected = Path.of(selection).toAbsolutePath().normalize();
                if (!selected.toRealPath().startsWith(importPath)) continue;
                pathAssets.add(asset);
            }
        }
        // Count by framework
        Map<String, Integer> frameworkCounts = new LinkedHashMap<>();
        for (Map<String, Object> asset : pathAssets) {
            String kind = (String) asset.get("kind");
            frameworkCounts.merge(kind, 1, Integer::sum);
        }
        List<Map<String, String>> frameworks = new ArrayList<>();
        for (Map.Entry<String, Integer> fc : frameworkCounts.entrySet()) {
            Map<String, String> fw = new LinkedHashMap<>();
            fw.put("name", fc.getKey());
            switch (fc.getKey()) {
                case "cucumber": fw.put("label", "Cucumber/Selenium"); fw.put("icon", "🎯"); break;
                case "newman": fw.put("label", "Postman/Newman"); fw.put("icon", "🔌"); break;
                case "playwright": fw.put("label", "Playwright Java"); fw.put("icon", "🎭"); break;
                case "karate": fw.put("label", "Karate DSL"); fw.put("icon", "🥋"); break;
                case "wdio": fw.put("label", "WebdriverIO"); fw.put("icon", "🌐"); break;
                case "restassured": fw.put("label", "RestAssured"); fw.put("icon", "⚡"); break;
                case "soapui": fw.put("label", "SoapUI/ReadyAPI"); fw.put("icon", "🧪"); break;
                case "jmeter": fw.put("label", "Apache JMeter"); fw.put("icon", "⏱️"); break;
                case "jmetercli": fw.put("label", "JMeter CLI"); fw.put("icon", "⏱️"); break;
                case "appium": fw.put("label", "Appium Mobile"); fw.put("icon", "📱"); break;
                case "cypress": fw.put("label", "Cypress"); fw.put("icon", "🌲"); break;
                case "gatling": fw.put("label", "Gatling"); fw.put("icon", "🔥"); break;
                case "database": fw.put("label", "Database"); fw.put("icon", "🗄️"); break;
                case "testng": fw.put("label", "TestNG"); fw.put("icon", "🔶"); break;
                case "pytest": fw.put("label", "Pytest"); fw.put("icon", "🐍"); break;
                case "robot": fw.put("label", "Robot Framework"); fw.put("icon", "🤖"); break;
                default: fw.put("label", fc.getKey()); fw.put("icon", "📁");
           }
            fw.put("count", String.valueOf(fc.getValue()));
            frameworks.add(fw);
        }
        sendJson(exchange, 200, Map.of(
                "id", id,
                "name", entry.getName(),
                "type", entry.getType(),
                "source", entry.getSource(),
                "totalAssets", pathAssets.size(),
                "frameworks", frameworks,
                "assets", pathAssets
        ));
    }

    private String extractBoundary(String contentType) {
        for (String part : contentType.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("boundary=")) {
                return trimmed.substring("boundary=".length()).trim();
            }
        }
        return null;
    }

    private String extractFileName(String body, String boundary) {
        String separator = "--" + boundary;
        String[] parts = body.split("(?=" + java.util.regex.Pattern.quote(separator) + ")");
        for (String part : parts) {
            int contentDispIdx = part.indexOf("Content-Disposition");
            if (contentDispIdx >= 0) {
                int filenameIdx = part.indexOf("filename=\"", contentDispIdx);
                if (filenameIdx >= 0) {
                    filenameIdx += "filename=\"".length();
                    int endIdx = part.indexOf("\"", filenameIdx);
                    if (endIdx > filenameIdx) {
                        return part.substring(filenameIdx, endIdx);
                    }
                }
            }
        }
        return null;
    }

    private byte[] extractZipData(byte[] body, String boundary, String fileName) {
        String bodyStr = new String(body, StandardCharsets.ISO_8859_1);

        // Find the part containing the file
        int partStart = bodyStr.indexOf("filename=\"" + fileName + "\"");
        if (partStart < 0) return null;

        // Find the blank line after headers (CRLF CRLF)
        int headersEnd = bodyStr.indexOf("\r\n\r\n", partStart);
        if (headersEnd < 0) return null;

        // Convert to byte offset
        int dataStart = headersEnd + 4;

        // Find the end of this part
        int dataEnd = bodyStr.indexOf("\r\n--" + boundary, dataStart);
        if (dataEnd < 0) {
            dataEnd = bodyStr.length() - 2; // end of body
        }

        // Extract the raw bytes directly
        int length = dataEnd - dataStart;
        byte[] result = new byte[length];
        System.arraycopy(body, dataStart, result, 0, length);
        return result;
    }

    private void createRun(HttpExchange exchange) throws Exception {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase().startsWith("application/json")) {
            throw new IllegalArgumentException("Send run settings as application/json.");
        }
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("Run settings are too large.");
        }
        Object parsed = JsonParser.parse(new String(body, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Run settings must be a JSON object.");
        }
        Set<String> supportedFields = Set.of("kind", "selection", "selections", "targetUrl", "authorized",
                "headless", "browsers", "testCaseId", "assetId", "executionSource", "ciProvider", "pipelineRunId",
                "executionScope", "scenarioName", "scenarioTag");
        for (Object key : values.keySet()) {
            if (!(key instanceof String name) || !supportedFields.contains(name)) {
                throw new IllegalArgumentException("Run settings contain an unsupported field.");
            }
        }

        String targetUrl = stringField(values, "targetUrl");
        if (targetUrl.length() > 2048) {
            throw new IllegalArgumentException("The target URL is too long.");
        }
        boolean authorized = booleanField(values, "authorized");
        boolean headless = booleanField(values, "headless");
        String requestedTestCaseId = stringField(values, "testCaseId");
        String requestedAssetId = stringField(values, "assetId");
        String executionSource = stringField(values, "executionSource");
        String ciProvider = stringField(values, "ciProvider");
        String pipelineRunId = stringField(values, "pipelineRunId");
        String requestedScope = stringField(values, "executionScope");
        String scenarioName = stringField(values, "scenarioName");
        String scenarioTag = stringField(values, "scenarioTag");
        if (requestedTestCaseId.isBlank()) {
            if (!isGlobalAdmin(exchange)) throw new AuthorizationException(403, "A project-owned Test Case is required for this execution.");
        } else {
            requireOwnedProjectAccess(exchange, "TEST_CASE", requestedTestCaseId, TeamAuthorization.TEST_EXECUTION, true);
        }
        if (!scenarioName.isBlank() && !scenarioTag.isBlank()) {
            throw new IllegalArgumentException("Choose a scenario name or tag filter, not both.");
        }

        // Parse browser list; default to chrome if not provided
        List<String> browsers = List.of("chrome");
        Object browsersObj = values.get("browsers");
        if (browsersObj instanceof List<?> browserList && !browserList.isEmpty()) {
            browsers = new ArrayList<>();
            for (Object b : browserList) {
                if (b instanceof String browserName) {
                    browsers.add(browserName.toLowerCase());
                }
            }
            if (browsers.isEmpty()) browsers = List.of("chrome");
        }

        // Check if this is a collection run (multiple selections)
        Object selectionsObj = values.get("selections");
        String runId = "RUN-" + UUID.randomUUID().toString().substring(0, 8);
        TenantJobContext jobContext = createRunJobContext(exchange, runId, requestedTestCaseId);
        RunEntry entry;

        // Get all assets including from shared paths and imports
        List<Path> allPaths = new ArrayList<>();
        for (SharedPathConfig.SharedPathEntry configured : sharedPathConfig.getSharedPaths()) {
            if (ownedProjectAccess(exchange, "SHARED_PATH", configured.getName(), TeamAuthorization.TEST_EXECUTION, false)
                    && Files.isDirectory(Path.of(configured.getPath()))) {
                allPaths.add(Path.of(configured.getPath()).toRealPath());
            }
        }
        for (RemoteImportConfig.ImportEntry configured : remoteImportConfig.getImports()) {
            if (ownedProjectAccess(exchange, "REMOTE_IMPORT", configured.getId(), TeamAuthorization.TEST_EXECUTION, false)) {
                try { allPaths.add(remoteImportConfig.resolveImportDirectory(configured)); }
                catch (IOException ignored) { /* unresolved or unsafe legacy import */ }
            }
        }
        List<Map<String, Object>> allAssets = executionService.pluginRegistry()
                .discoverAllAssets(projectRoot, allPaths);

        if (!requestedAssetId.isBlank()) {
            if (selectionsObj instanceof List<?> selections && !selections.isEmpty()) {
                throw new IllegalArgumentException("An automation asset run must target one discovered asset.");
            }
            Map<String, Object> asset = allAssets.stream()
                    .filter(item -> requestedAssetId.equals(stableAssetId(item)))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException(
                            "The selected automation asset is unavailable. Refresh the Test Repository and try again."));
            if (requestedTestCaseId.isBlank()) {
                throw new IllegalArgumentException("A mapped Test Case is required for this automation run.");
            }
            if (testCaseManager.getById(requestedTestCaseId) == null) {
                throw new IllegalArgumentException("Test case not found: " + requestedTestCaseId);
            }
            boolean mapped = automationMappingManager.listForTestCase(requestedTestCaseId).stream()
                    .anyMatch(item -> requestedAssetId.equals(String.valueOf(item.get("assetId"))));
            if (!mapped) {
                throw new IllegalArgumentException("The selected automation asset is not mapped to this Test Case.");
            }
            String kind = String.valueOf(asset.getOrDefault("kind", ""));
            String selection = String.valueOf(asset.getOrDefault("selection", ""));
            if ((scenarioName.length() > 300) || (scenarioTag.length() > 300)) {
                throw new IllegalArgumentException("The Cucumber scenario filter is too long.");
            }
            if (!scenarioName.isBlank() || !scenarioTag.isBlank()) {
                if (!"cucumber".equalsIgnoreCase(kind)) {
                    throw new IllegalArgumentException("Scenario filters are supported only for Cucumber assets.");
                }
                List<Map<String, Object>> scenarios = cucumberScenarios(asset);
                boolean knownScenario = scenarios.stream().anyMatch(item ->
                        (!scenarioName.isBlank() && scenarioName.equals(String.valueOf(item.get("name"))))
                                || (!scenarioTag.isBlank() && item.get("tags") instanceof List<?> tags
                                && tags.stream().anyMatch(tag -> scenarioTag.equals(String.valueOf(tag)))));
                if (!knownScenario) {
                    throw new IllegalArgumentException("The selected scenario or tag was not found in the feature.");
                }
            }
            if (targetUrl.isBlank()) {
                targetUrl = String.valueOf(asset.getOrDefault("defaultTargetUrl", ""));
            }
            entry = new RunEntry(runId, kind, selection, targetUrl, jobContext);
            entry.assetId = requestedAssetId;
            entry.testCaseId = requestedTestCaseId;
            entry.executionSource = executionSource.isBlank() ? "LOCAL_RUNNER" : executionSource;
            entry.ciProvider = ciProvider;
            entry.pipelineRunId = pipelineRunId;
            entry.executionScope = (!scenarioName.isBlank() || !scenarioTag.isBlank()) ? "SCENARIO" :
                    (requestedScope.isBlank() ? "FEATURE" : requestedScope);
            entry.scenarioName = scenarioName;
            entry.scenarioTag = scenarioTag;
            entry.browsers = new ArrayList<>(browsers);
        } else if (selectionsObj instanceof List<?> selections && !selections.isEmpty()) {
            // Collection run: validate all selections are known assets
            List<String> knownSelections = allAssets.stream()
                    .map(a -> (String) a.get("selection")).toList();
            for (Object sel : selections) {
                if (!(sel instanceof String selection)) {
                    throw new IllegalArgumentException("Each selection must be a string.");
                }
                if (!knownSelections.contains(selection)) {
                    throw new IllegalArgumentException("Choose a test asset discovered in this project.");
                }
            }
            String selectionDisplay = "Collection (" + selections.size() + " features)";
            entry = new RunEntry(runId, "cucumber", selectionDisplay, targetUrl, jobContext);
            entry.selections = new ArrayList<>((List<String>) selections);
            entry.browsers = new ArrayList<>(browsers);
        } else {
            // Single run
            String pluginKind = stringField(values, "kind");
            String selection = stringField(values, "selection");
            // Validate against known assets
            boolean knownAsset = allAssets.stream()
                    .anyMatch(a -> ((String) a.get("selection")).equals(selection));
            if (!knownAsset) {
                throw new IllegalArgumentException("Choose a test asset discovered in this project.");
            }
            entry = new RunEntry(runId, pluginKind, selection, targetUrl, jobContext);
            entry.browsers = new ArrayList<>(browsers);
        }
        synchronized (runs) {
            if (activeRunId != null) {
                throw new IllegalStateException("Another test run is already in progress.");
            }
            activeRunId = entry.id;
            runs.put(entry.id, entry);
            trimRuns();
        }
        String runProject = jobContext.projectId() == null ? "" : jobContext.projectId();
        if (runProject.isBlank()) projectOwnershipManager.quarantine("TEST_RUN", runId, "RUN_WITHOUT_PROJECT");
        else projectOwnershipManager.assign("TEST_RUN", runId, runProject, "TEST_CASE_RELATIONSHIP", validProjectIds());
        List<String> finalBrowsers = entry.browsers;
        String runTargetUrl = targetUrl;
        try {
            runExecutor.execute(() -> executeRun(entry, runTargetUrl, authorized, headless, finalBrowsers));
        } catch (RejectedExecutionException error) {
            synchronized (runs) {
                runs.remove(entry.id);
                if (entry.id.equals(activeRunId)) activeRunId = null;
            }
            throw new IllegalStateException("The execution queue is unavailable.");
        }
        sendJson(exchange, 202, runResponse(entry));
    }

    private TenantJobContext createRunJobContext(HttpExchange exchange, String runId,
                                                  String requestedTestCaseId) throws IOException {
        AuthSessionManager.Session current = session(exchange);
        if (current == null) throw new AuthorizationException(401, "Authentication is required.");
        if (requestedTestCaseId == null || requestedTestCaseId.isBlank()) {
            if (!isGlobalAdmin(exchange)) {
                throw new AuthorizationException(403, "A project-owned Test Case is required for this execution.");
            }
            return new TenantJobContext(runId, null, null, current.userId(), "TEST_RUN",
                    "TEST_RUN", runId, Instant.now().toString(), "PLATFORM_ADMIN_RUN");
        }
        String projectId = projectOwnershipManager.projectId("TEST_CASE", requestedTestCaseId);
        if (projectId.isBlank()) {
            throw new AuthorizationException(403, "The test case has no authorized project ownership.");
        }
        ProjectOrganizationOwnership assignment = organizationStore.findProjectOwnership(projectId);
        if (assignment == null) {
            throw new AuthorizationException(403, "The project has no authorized organization ownership.");
        }
        try {
            tenantContextResolver.resolveProject(current.id(), assignment.organizationId(), projectId,
                    TeamAuthorization.TEST_EXECUTION, "write");
        } catch (TenantAuthorizationException error) {
            throw new AuthorizationException(error.statusCode(),
                    "The requested tenant execution is not authorized.");
        }
        return new TenantJobContext(runId, assignment.organizationId(), projectId, current.userId(),
                "TEST_RUN", "TEST_CASE", requestedTestCaseId, Instant.now().toString(),
                "TENANT_PROJECT_TEST_EXECUTION_V1");
    }

    /** Rechecks current authoritative state at every sensitive async boundary. */
    private void revalidateRunContext(RunEntry entry, String boundary) throws IOException {
        TenantJobContext context = entry.jobContext;
        if (context == null) throw new AuthorizationException(403, "The run has no tenant execution context.");
        if (context.platformJob()) {
            AuthUserDirectory.User user = authUserDirectory.findById(context.requesterUserId());
            if (user == null || !AuthUserDirectory.ACTIVE.equals(user.status())
                    || !isGlobalAdminUser(context.requesterUserId())) {
                throw new AuthorizationException(403, "The platform execution is no longer authorized.");
            }
            return;
        }
        ProjectOrganizationOwnership assignment = organizationStore.findProjectOwnership(context.projectId());
        if (assignment == null || !context.organizationId().equals(assignment.organizationId())) {
            throw new AuthorizationException(403, "The run ownership is no longer authoritative.");
        }
        try {
            tenantContextResolver.resolveUser(context.requesterUserId(), context.organizationId(),
                    context.projectId(), TeamAuthorization.TEST_EXECUTION, "write");
        } catch (TenantAuthorizationException error) {
            throw new AuthorizationException(error.statusCode(),
                    "The run is no longer authorized for this tenant.");
        }
    }

    private boolean isGlobalAdminUser(String userId) {
        return teamMemberManager.list().stream()
                .anyMatch(member -> userId.equals(member.registeredUserId())
                        && TeamMemberManager.ACTIVE.equals(member.status())
                        && "Admin".equals(member.role()));
    }

    private void executeRun(RunEntry entry, String targetUrl, boolean authorized,
                            boolean headless, List<String> browsers) {
        try {
            revalidateRunContext(entry, "execute");
            entry.status = "RUNNING";
            if (entry.selections != null && !entry.selections.isEmpty()) {
                // Collection run: dispatch to the first plugin that matches
                RunnerPlugin plugin = executionService.pluginRegistry().findByName(entry.kindName);
                if (plugin == null) {
                    throw new IllegalStateException("No plugin found for kind: " + entry.kindName);
                }
                entry.result = plugin.executeCollection(entry.selections, targetUrl,
                        authorized, headless, browsers, entry::addLog);
            } else {
                // Single run: dispatch to the matching plugin
                RunnerPlugin plugin = executionService.pluginRegistry().findByName(entry.kindName);
                if (plugin == null) {
                    throw new IllegalStateException("No plugin found for kind: " + entry.kindName);
                }
                entry.result = plugin.execute(entry.selection, targetUrl,
                        authorized, headless, browsers, entry.scenarioName, entry.scenarioTag, entry::addLog);
            }
            entry.status = entry.cancelRequested ? "CANCELLED" : resultStatus(entry.result);
            if (!entry.cancelRequested) {
                revalidateRunContext(entry, "artifact-write");
                recordRunArtifacts(entry);
            }
        } catch (Exception exception) {
            entry.error = exception.getMessage() == null
                    ? "The test run could not be completed." : exception.getMessage();
            entry.status = entry.cancelRequested ? "CANCELLED" : "ERROR";
        } finally {
            entry.finishedAt = Instant.now().toString();
            try {
                revalidateRunContext(entry, "result-write");
                persistAutomationExecution(entry);
            } catch (Exception exception) {
                entry.error = "The run result could not be authorized for persistence.";
                entry.status = "ERROR";
            }
            synchronized (runs) {
                if (entry.id.equals(activeRunId)) {
                    activeRunId = null;
                }
            }
        }
    }

    private void cancelRun(HttpExchange exchange, RunEntry entry) throws IOException {
        revalidateRunContext(entry, "cancel");
        synchronized (runs) {
            if (!entry.id.equals(activeRunId) || !(entry.status.equals("QUEUED") || entry.status.equals("RUNNING"))) {
                sendError(exchange, 409, "This run is no longer active.");
                return;
            }
            entry.cancelRequested = true;
            entry.status = "CANCELLING";
            executionService.cancel();
        }
        sendJson(exchange, 202, runResponse(entry));
    }

    private Map<String, Object> runListResponse() {
        List<RunEntry> entries;
        synchronized (runs) {
            entries = new ArrayList<>(runs.values());
        }
        entries.sort(Comparator.comparing((RunEntry entry) -> entry.startedAt).reversed());
        return Map.of("runs", entries.stream().map(this::runResponse).toList());
    }

    private Map<String, Object> runResponse(RunEntry entry) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", entry.id);
        response.put("kind", entry.kindName);
        response.put("label", entry.kindName);
        response.put("selection", entry.selection);
        response.put("targetUrl", entry.targetUrl);
        response.put("status", entry.status);
        response.put("startedAt", entry.startedAt);
        response.put("finishedAt", entry.finishedAt);
        response.put("exitCode", entry.result == null ? null : entry.result.exitCode());
        response.put("summary", entry.result == null ? null : summaryResponse(entry.result.summary()));
        response.put("reportUrl", entry.result == null ? null
                : "/api/runs/" + entry.id + "/report");
        response.put("error", entry.error);
        response.put("logs", entry.logs());
        response.put("testCaseId", entry.testCaseId);
        response.put("assetId", entry.assetId);
        response.put("framework", entry.kindName);
        response.put("executionSource", entry.executionSource);
        response.put("ciProvider", entry.ciProvider);
        response.put("pipelineRunId", entry.pipelineRunId);
        response.put("executionScope", entry.executionScope);
        response.put("scenarioName", entry.scenarioName);
        response.put("scenarioTag", entry.scenarioTag);
        return response;
    }

    private Map<String, Object> visibleCatalogResponse(HttpExchange exchange) throws IOException {
        Map<String, Object> response = catalogResponse(exchange);
        if (isGlobalAdmin(exchange)) return response;
        List<Map<String, Object>> visible = response.get("assets") instanceof List<?> values
                ? values.stream().filter(Map.class::isInstance).map(value -> (Map<String, Object>) value)
                .filter(asset -> assetAccessible(exchange, stringField(asset, "assetId"), false)).toList()
                : List.of();
        Map<String, Object> filtered = new LinkedHashMap<>(response);
        filtered.put("assets", visible);
        filtered.put("total", visible.size());
        return filtered;
    }

    private boolean assetAccessible(HttpExchange exchange, String assetId, boolean write) {
        if (assetId == null || assetId.isBlank()) return false;
        if (isGlobalAdmin(exchange)) return true;
        return automationMappingManager.list().stream()
                .filter(mapping -> assetId.equals(stringField(mapping, "assetId")))
                .anyMatch(mapping -> ownedProjectAccess(exchange, "TEST_CASE", stringField(mapping, "testCaseId"),
                        write ? TeamAuthorization.TEST_EXECUTION : TeamAuthorization.TEST_CASES, write));
    }

    private List<Map<String, Object>> cucumberScenarios(Map<String, Object> asset) {
        String selection = String.valueOf(asset.getOrDefault("selection", ""));
        Path feature = Path.of(selection);
        if (!feature.isAbsolute()) feature = projectRoot.resolve(feature);
        feature = feature.normalize();
        try {
            if (!Files.isRegularFile(feature)) return List.of();
            List<Map<String, Object>> scenarios = new ArrayList<>();
            List<String> pendingTags = new ArrayList<>();
            int index = 0;
            for (String line : Files.readAllLines(feature, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("@")) {
                    for (String tag : trimmed.split("\\s+")) if (tag.startsWith("@")) pendingTags.add(tag);
                    continue;
                }
                if (trimmed.startsWith("Scenario:") || trimmed.startsWith("Scenario Outline:")) {
                    String name = trimmed.substring(trimmed.indexOf(':') + 1).trim();
                    Map<String, Object> scenario = new LinkedHashMap<>();
                    scenario.put("id", stableHash(stableAssetId(asset) + "\\n" + name + "\\n" + index++));
                    scenario.put("name", name);
                    scenario.put("tags", List.copyOf(pendingTags));
                    scenario.put("tag", pendingTags.isEmpty() ? "" : pendingTags.get(pendingTags.size() - 1));
                    scenarios.add(scenario);
                    pendingTags = new ArrayList<>();
                }
            }
            return scenarios;
        } catch (IOException error) {
            return List.of();
        }
    }

    private String resultStatus(ExecutionService.RunResult result) {
        if (result == null) return "ERROR";
        RunSummary summary = result.summary();
        if (summary.failed() > 0) return "FAILED";
        if (summary.total() > 0 && summary.passed() == 0 && summary.skipped() > 0) return "SKIPPED";
        if (summary.total() <= 0) return "ERROR";
        if (result.exitCode() != 0) return "ERROR";
        return "PASSED";
    }

    private String stableAssetId(Map<String, Object> asset) {
        String projectIdentity = stableHash(projectRoot.toAbsolutePath().normalize().toString());
        return "automation-" + stableHash(projectIdentity + "\n"
                + String.valueOf(asset.getOrDefault("kind", "unknown")) + "\n"
                + String.valueOf(asset.getOrDefault("selection", "")));
    }

    private void persistAutomationExecution(RunEntry entry) {
        if (entry.assetId == null || entry.assetId.isBlank()) return;
        List<Map<String, Object>> mappings = automationMappingManager.listForAsset(entry.assetId);
        if (entry.testCaseId != null && !entry.testCaseId.isBlank()) {
            mappings = mappings.stream().filter(item -> entry.testCaseId.equals(String.valueOf(item.get("testCaseId")))).toList();
        }
        for (Map<String, Object> mapping : mappings) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("executionId", entry.id);
            record.put("testCaseId", String.valueOf(mapping.get("testCaseId")));
            record.put("assetId", entry.assetId);
            record.put("framework", entry.kindName);
            record.put("project", mapping.getOrDefault("sourceProject", projectRoot.getFileName().toString()));
            record.put("executionSource", entry.executionSource);
            record.put("organizationId", entry.jobContext.organizationId() == null
                    ? "" : entry.jobContext.organizationId().toString());
            record.put("projectId", entry.jobContext.projectId() == null ? "" : entry.jobContext.projectId());
            record.put("startedAt", entry.startedAt);
            record.put("finishedAt", entry.finishedAt);
            record.put("durationMs", durationMillis(entry.startedAt, entry.finishedAt));
            record.put("status", entry.status);
            record.put("summary", entry.result == null ? null : summaryResponse(entry.result.summary()));
            record.put("error", entry.error);
            record.put("reportReference", entry.result == null ? null : "/api/runs/" + entry.id + "/report");
            record.put("ciProvider", entry.ciProvider);
            record.put("pipelineRunId", entry.pipelineRunId);
            try {
                String executionProject = projectOwnershipManager.projectId("TEST_CASE", String.valueOf(mapping.get("testCaseId")));
                if (executionProject.isBlank() || !executionProject.equals(entry.jobContext.projectId())) {
                    projectOwnershipManager.quarantine("AUTOMATION_EXECUTION", entry.id, "EXECUTION_OWNERSHIP_MISMATCH");
                    continue;
                }
                ProjectOrganizationOwnership assignment = organizationStore.findProjectOwnership(executionProject);
                if (assignment == null || !assignment.organizationId().equals(entry.jobContext.organizationId())) {
                    projectOwnershipManager.quarantine("AUTOMATION_EXECUTION", entry.id, "EXECUTION_TENANT_MISMATCH");
                    continue;
                }
                automationExecutionManager.save(record);
                projectOwnershipManager.assign("AUTOMATION_EXECUTION", entry.id, executionProject,
                        "TEST_CASE_RELATIONSHIP", validProjectIds());
            } catch (IOException error) {
                System.err.println("Failed to persist automated execution " + entry.id + ": " + error.getMessage());
            }
        }
    }

    private static long durationMillis(String startedAt, String finishedAt) {
        try {
            return Math.max(0, java.time.Duration.between(Instant.parse(startedAt), Instant.parse(finishedAt)).toMillis());
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static Map<String, Object> summaryResponse(RunSummary summary) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("total", summary.total());
        values.put("passed", summary.passed());
        values.put("failed", summary.failed());
        values.put("skipped", summary.skipped());
        List<Map<String, Object>> detailList = new ArrayList<>();
        for (RunDetail detail : summary.details()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("name", detail.name());
            d.put("status", detail.status());
            d.put("request", detail.request());
            d.put("response", detail.response());
            d.put("errorMessage", detail.errorMessage());
            if (detail.screenshotPath() != null) {
                d.put("screenshotPath", detail.screenshotPath());
            }
            detailList.add(d);
        }
        values.put("details", detailList);
        return values;
    }

    private RunEntry requireRun(String id) {
        synchronized (runs) {
            RunEntry entry = runs.get(id);
            if (entry == null) {
                throw new IllegalArgumentException("The requested run was not found.");
            }
            return entry;
        }
    }

    private void servePage(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            sendError(exchange, 405, "Method not allowed.");
            return;
        }
        try (var page = RunnerServer.class.getResourceAsStream("/static/index.html")) {
            if (page == null) {
                sendError(exchange, 500, "The runner UI resource is missing.");
                return;
            }
            byte[] content = page.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
        }
    }

    private void serveScreenshot(HttpExchange exchange, String path) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            sendError(exchange, 405, "Method not allowed.");
            return;
        }
        String fileName = path.substring("/screenshots/".length());
        if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            sendError(exchange, 400, "Invalid screenshot path.");
            return;
        }
        Path screenshotsDir = projectRoot.resolve("target").resolve("screenshots");
        Path screenshot = resolveContainedMediaFile(screenshotsDir, fileName);
        if (screenshot == null) {
            sendError(exchange, 404, "Screenshot not found.");
            return;
        }
        requireArtifactAccess(exchange, "SCREENSHOT", fileName, TeamAuthorization.TEST_EXECUTION, false);
        streamFileResponse(exchange, screenshot, "image/png");
    }

    private void serveReport(HttpExchange exchange, RunEntry entry) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            sendError(exchange, 405, "Method not allowed.");
            return;
        }
        if (entry.result == null) {
            sendError(exchange, 409, "The report is not ready yet.");
            return;
        }
        Path reportRoot = projectRoot.resolve("target/runner-reports");
        Path report;
        try {
            report = ReportStorageSecurity.resolveRegularReportFile(
                    reportRoot, entry.result.reportDirectory().resolve("index.html"));
        } catch (IOException error) {
            sendError(exchange, 404, "The report is not available.");
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; "
                        + "frame-ancestors 'none'");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, Files.size(report));
        try (InputStream input = Files.newInputStream(report);
             OutputStream output = exchange.getResponseBody()) {
            input.transferTo(output);
        }
    }

    private void requireSameOrigin(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        // Same-origin requests from the browser do not include an Origin header.
        // Allow missing origin since Host header is already validated above.
        if (origin == null) {
            return;
        }
        // Accept any origin that matches the server's port (e.g., 127.0.0.1, localhost, or network IP)
        int port = server.getAddress().getPort();
        String expectedSuffix = ":" + port;
        if (!origin.endsWith(expectedSuffix)) {
            throw new IllegalArgumentException("The request origin does not match this local UI.");
        }
    }

    private AuthSessionManager.Session session(HttpExchange exchange) {
        return authSessionManager.find(requestCookie(exchange, AuthSessionManager.COOKIE));
    }

    private void requireAuthenticatedCsrf(HttpExchange exchange) {
        requireSameOrigin(exchange);
        AuthSessionManager.Session current = session(exchange);
        if (current == null) throw new AuthorizationException(401, "Authentication is required.");
        String token = exchange.getRequestHeaders().getFirst(AuthSessionManager.CSRF_HEADER);
        if (token == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), current.csrfToken().getBytes(StandardCharsets.UTF_8))) {
            throw new AuthorizationException(403, "A valid CSRF token is required.");
        }
    }

    private AuthUserDirectory.User requireAuthenticatedUser(HttpExchange exchange) {
        AuthSessionManager.Session current = session(exchange);
        if (current == null) throw new AuthorizationException(401, "Authentication is required.");
        AuthUserDirectory.User user = authUserDirectory.findById(current.userId());
        if (user == null || !AuthUserDirectory.ACTIVE.equals(user.status())) {
            authSessionManager.invalidate(current.id());
            throw new AuthorizationException(401, "Authentication is required.");
        }
        return user;
    }

    private void requireDatabaseView(HttpExchange exchange) {
        AuthUserDirectory.User user = requireAuthenticatedUser(exchange);
        boolean member = teamMemberManager.list().stream()
                .anyMatch(item -> user.id().equals(item.registeredUserId()) && TeamMemberManager.ACTIVE.equals(item.status()));
        if (!member) throw new AuthorizationException(403, "An active project member is required to view database connections.");
    }

    private void requireDatabaseManage(HttpExchange exchange) {
        requireAuthenticatedCsrf(exchange);
        AuthUserDirectory.User user = requireAuthenticatedUser(exchange);
        TeamMemberManager.Member member = teamMemberManager.list().stream()
                .filter(item -> user.id().equals(item.registeredUserId()) && TeamMemberManager.ACTIVE.equals(item.status()))
                .findFirst().orElse(null);
        if (member == null) throw new AuthorizationException(403, "An active team member is required to manage database connections.");
        if (!Set.of("Admin", "Project Manager", "QA Lead").contains(member.role())) {
            throw new AuthorizationException(403, "Your role cannot manage database connections.");
        }
    }

    private String requireDatabaseViewForOrganization(HttpExchange exchange, String rawOrganizationId) {
        UUID organizationId = parseTenantOrganizationId(rawOrganizationId);
        requireTenantOrganization(exchange, organizationId);
        requireDatabaseView(exchange);
        return organizationId.toString();
    }

    private String requireDatabaseManageForOrganization(HttpExchange exchange, String rawOrganizationId) {
        UUID organizationId = parseTenantOrganizationId(rawOrganizationId);
        requireTenantOrganization(exchange, organizationId);
        requireDatabaseManage(exchange);
        return organizationId.toString();
    }

    private void requireTenantOrganization(HttpExchange exchange, UUID organizationId) {
        try {
            AuthSessionManager.Session current = session(exchange);
            if (current == null) throw new AuthorizationException(401, "Authentication is required.");
            tenantContextResolver.resolve(current.id(), organizationId);
        } catch (TenantAuthorizationException error) {
            throw new AuthorizationException(error.statusCode(), error.statusCode() == 401
                    ? "Authentication is required." : "You are not authorized for this organization.");
        } catch (IOException error) {
            throw new IllegalStateException("Tenant authorization data could not be loaded.", error);
        }
    }

    private UUID parseTenantOrganizationId(String rawOrganizationId) {
        try {
            return UUID.fromString(OrganizationValidation.requiredText(rawOrganizationId, "organizationId", 80));
        } catch (IllegalArgumentException error) {
            throw new AuthorizationException(403, "An authorized organization scope is required.");
        }
    }

    private void requireDatabaseConnectionOrganization(String connectionId, String organizationId) {
        if (connectionId == null || connectionId.isBlank() || organizationId == null || organizationId.isBlank()) {
            throw new AuthorizationException(404, "Database connection was not found.");
        }
        try {
            if (!organizationId.equals(databaseConnectionService.connectionOrganizationId(connectionId))) {
                throw new AuthorizationException(404, "Database connection was not found.");
            }
        } catch (IllegalArgumentException error) {
            throw new AuthorizationException(404, "Database connection was not found.");
        }
    }

    private static int parseQueryInt(HttpExchange exchange, String name, int fallback) {
        String value = queryValue(exchange, name);
        if (value == null || value.isBlank()) return fallback;
        try { return Integer.parseInt(value); } catch (NumberFormatException error) { throw new IllegalArgumentException(name + " must be a number."); }
    }

    private static Map<String, Object> databaseTestToMap(ConnectionTestService.TestState state) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", state.id()); result.put("connectionId", state.connectionId()); result.put("status", state.status());
        result.put("startedAt", state.startedAt()); result.put("completedAt", state.completedAt()); result.put("error", state.error());
        return result;
    }

    private static String requestCookie(HttpExchange exchange, String name) {
        String header = exchange.getRequestHeaders().getFirst("Cookie");
        if (header == null) return "";
        for (String item : header.split(";")) {
            String[] pair = item.trim().split("=", 2);
            if (pair.length == 2 && name.equals(pair[0])) return pair[1];
        }
        return "";
    }

    private static boolean secureCookies() { return Boolean.getBoolean("qa.runner.secureCookies"); }

    private void handleOrganizationApi(HttpExchange exchange, String path, String method) throws Exception {
        if (path.equals("/api/organizations") && method.equals("POST")) {
            requireAuthenticatedCsrf(exchange);
            AuthUserDirectory.User actor = requireAuthenticatedUser(exchange);
            Map<String, Object> values = requestObject(exchange);
            String name = OrganizationValidation.requiredText(stringField(values, "organizationName"), "organizationName", 120);
            String slug = OrganizationValidation.slug(stringField(values, "organizationSlug"));
            int limit = Math.max(1, Integer.getInteger("qa.runner.organizations.maxPerUser", 20));
            long owned = organizationStore.organizations().stream().filter(item -> actor.id().equals(item.ownerUserId())).count();
            if (owned >= limit) throw new AuthorizationException(403, "The organization creation limit has been reached.");
            Organization organization = organizationStore.createOrganization(name, slug, actor.id());
            auditOrganization("ORGANIZATION_CREATED", organization.organizationId(), actor.id(), organization.organizationId().toString());
            sendJson(exchange, 201, organizationToApiMap(organization, false));
            return;
        }
        if (path.equals("/api/organizations") && method.equals("GET")) {
            AuthUserDirectory.User actor = requireAuthenticatedUser(exchange);
            List<Map<String, Object>> result = new ArrayList<>();
            for (Organization organization : organizationStore.organizations()) {
                OrganizationMembership membership = organizationStore.findMembership(organization.organizationId(), actor.id());
                if (membership != null && membership.membershipStatus() == MembershipStatus.ACTIVE
                        && organization.status() == OrganizationStatus.ACTIVE) {
                    Map<String, Object> safe = organizationToApiMap(organization, false);
                    safe.put("organizationRole", membership.organizationRole().name());
                    result.add(safe);
                }
            }
            sendJson(exchange, 200, result);
            return;
        }
        if (path.equals("/api/organization-invitations/accept") && method.equals("POST")) {
            requireAuthenticatedCsrf(exchange);
            AuthUserDirectory.User actor = requireAuthenticatedUser(exchange);
            Map<String, Object> values = requestObject(exchange);
            UUID invitationId = parseUuid(stringField(values, "invitationId"), "invitationId");
            String rawToken = OrganizationValidation.requiredText(stringField(values, "token"), "token", 512);
            OrganizationMembership membership;
            try {
                membership = organizationStore.acceptInvitation(invitationId, rawToken, actor.id(), actor.email(), Instant.now());
            } catch (IllegalArgumentException | IllegalStateException error) {
                throw new AuthorizationException(403, "The invitation is not valid for this account.");
            }
            auditOrganization("INVITATION_ACCEPTED", membership.organizationId(), actor.id(), invitationId.toString());
            sendJson(exchange, 200, membershipToApiMap(membership));
            return;
        }
        if (!path.startsWith("/api/organizations/")) throw new AuthorizationException(404, "Not found.");
        String remainder = path.substring("/api/organizations/".length());
        String[] parts = remainder.split("/");
        if (parts.length == 0 || parts[0].isBlank()) throw new AuthorizationException(404, "Not found.");
        UUID organizationId = parseUuid(parts[0], "organizationId");
        Organization organization = organizationStore.findOrganization(organizationId);
        if (organization == null) throw new AuthorizationException(404, "Organization was not found.");
        TenantContext tenant = requireOrganizationTenant(exchange, organizationId);

        if (parts.length == 1 && method.equals("GET")) {
            Map<String, Object> detail = organizationToApiMap(organization, true);
            detail.put("organizationRole", tenant.organizationRole().name());
            sendJson(exchange, 200, detail);
            return;
        }
        if (parts.length == 1 && method.equals("PATCH")) {
            requireAuthenticatedCsrf(exchange);
            requireOrganizationManager(tenant);
            if (organization.status() != OrganizationStatus.ACTIVE) throw new AuthorizationException(403, "The organization is not active.");
            Map<String, Object> values = requestObject(exchange);
            if (values.keySet().stream().anyMatch(key -> !Set.of("organizationName", "organizationSlug").contains(key))) {
                throw new IllegalArgumentException("Only organization name and slug may be changed.");
            }
            String name = values.containsKey("organizationName") ? OrganizationValidation.requiredText(stringField(values, "organizationName"), "organizationName", 120) : organization.organizationName();
            String slug = values.containsKey("organizationSlug") ? OrganizationValidation.slug(stringField(values, "organizationSlug")) : organization.organizationSlug();
            Organization updated = organizationStore.updateOrganization(organizationId, name, slug, organization.status());
            auditOrganization("ORGANIZATION_UPDATED", organizationId, tenant.authenticatedUserId(), organizationId.toString());
            sendJson(exchange, 200, organizationToApiMap(updated, true));
            return;
        }
        if (parts.length == 2 && parts[1].equals("members") && method.equals("GET")) {
            requireOrganizationManager(tenant);
            sendJson(exchange, 200, organizationStore.memberships(organizationId).stream().map(RunnerServer::membershipToApiMap).toList());
            return;
        }
        if (parts.length == 3 && parts[1].equals("members") && (method.equals("PATCH") || method.equals("DELETE"))) {
            requireAuthenticatedCsrf(exchange);
            requireOrganizationManager(tenant);
            if (organization.status() != OrganizationStatus.ACTIVE) throw new AuthorizationException(403, "The organization is not active.");
            UUID membershipId = parseUuid(parts[2], "membershipId");
            OrganizationMembership target = organizationStore.memberships(organizationId).stream().filter(item -> membershipId.equals(item.membershipId())).findFirst().orElse(null);
            if (target == null) throw new AuthorizationException(404, "Membership was not found.");
            OrganizationRole role = target.organizationRole();
            MembershipStatus status = method.equals("DELETE") ? MembershipStatus.SUSPENDED : target.membershipStatus();
            if (method.equals("PATCH")) {
                Map<String, Object> values = requestObject(exchange);
                if (values.containsKey("role")) role = parseEnum(values.get("role"), OrganizationRole.class, "role");
                if (values.containsKey("status")) status = parseEnum(values.get("status"), MembershipStatus.class, "status");
                if (tenant.organizationRole() == OrganizationRole.ADMIN && (role == OrganizationRole.OWNER || role == OrganizationRole.ADMIN || target.organizationRole() == OrganizationRole.OWNER)) {
                    throw new AuthorizationException(403, "Organization admins cannot assign or change owner/admin roles.");
                }
                if (role == OrganizationRole.OWNER && !tenant.authenticatedUserId().equals(organization.ownerUserId())) throw new AuthorizationException(403, "Only the organization owner may assign the owner role.");
            }
            OrganizationMembership updated = organizationStore.updateMembership(membershipId, role, status);
            auditOrganization(method.equals("DELETE") ? "MEMBERSHIP_REVOKED" : "MEMBERSHIP_UPDATED", organizationId, tenant.authenticatedUserId(), membershipId.toString());
            sendJson(exchange, 200, membershipToApiMap(updated));
            return;
        }
        if (parts.length == 2 && parts[1].equals("invitations") && method.equals("POST")) {
            requireAuthenticatedCsrf(exchange);
            requireOrganizationManager(tenant);
            if (organization.status() != OrganizationStatus.ACTIVE) throw new AuthorizationException(403, "The organization is not active.");
            String rateKey = exchange.getRemoteAddress().getAddress().getHostAddress() + ":" + organizationId;
            if (!organizationInvitationRateLimiter.allow(rateKey)) throw new AuthorizationException(429, "Invitation requests are temporarily unavailable. Try again later.");
            Map<String, Object> values = requestObject(exchange);
            String email = OrganizationValidation.email(stringField(values, "invitedEmail"));
            OrganizationRole intendedRole = values.containsKey("intendedRole") ? parseEnum(values.get("intendedRole"), OrganizationRole.class, "intendedRole") : OrganizationRole.MEMBER;
            if (intendedRole == OrganizationRole.OWNER || (intendedRole == OrganizationRole.ADMIN && tenant.organizationRole() != OrganizationRole.OWNER)) throw new AuthorizationException(403, "This role cannot be assigned by the current organization role.");
            String invitedUserId = optionalString(values, "invitedUserId");
            if (invitedUserId != null) invitedUserId = OrganizationValidation.userId(invitedUserId, "invitedUserId");
            String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
            OrganizationInvitation invitation = organizationStore.createInvitation(organizationId, email, invitedUserId, intendedRole, rawToken, Instant.now().plusSeconds(86400), tenant.authenticatedUserId());
            auditOrganization("INVITATION_CREATED", organizationId, tenant.authenticatedUserId(), invitation.invitationId().toString());
            Map<String, Object> response = invitationToApiMap(invitation);
            response.put("deliveryStatus", "PENDING");
            sendJson(exchange, 201, response);
            return;
        }
        if (parts.length == 2 && parts[1].equals("invitations") && method.equals("GET")) {
            requireOrganizationManager(tenant);
            sendJson(exchange, 200, organizationStore.invitations(organizationId).stream().map(RunnerServer::invitationToApiMap).toList());
            return;
        }
        if (parts.length == 2 && parts[1].equals("projects") && method.equals("GET")) {
            List<Map<String, Object>> projects = new ArrayList<>();
            for (UserStoryStore.Project project : userStoryStore.listProjects(false)) {
                ProjectOrganizationOwnership ownership = organizationStore.findProjectOwnership(project.id());
                if (ownership == null || !organizationId.equals(ownership.organizationId())) continue;
                boolean orgManager = tenant.organizationRole() == OrganizationRole.OWNER || tenant.organizationRole() == OrganizationRole.ADMIN;
                if (orgManager || hasExplicitProjectMembership(exchange, project.id())) projects.add(projectToMap(project));
            }
            sendJson(exchange, 200, projects);
            return;
        }
        if (parts.length == 2 && parts[1].equals("projects") && method.equals("POST")) {
            requireAuthenticatedCsrf(exchange);
            requireOrganizationManager(tenant);
            if (organization.status() != OrganizationStatus.ACTIVE) throw new AuthorizationException(403, "The organization is not active.");
            Map<String, Object> values = requestObject(exchange);
            UserStoryStore.Project project = userStoryStore.createProject(stringField(values, "key"), stringField(values, "name"), optionalString(values, "description"), tenant.authenticatedUserId());
            try {
                organizationStore.assignProject(project.id(), organizationId, "ORGANIZATION_API_CREATE");
                ensureCreatorProjectMembership(tenant.authenticatedUserId(), project.id());
            } catch (Exception error) {
                organizationStore.quarantineProject(project.id());
                throw new IllegalStateException("Project creation could not be completed safely.");
            }
            auditOrganization("PROJECT_CREATED", organizationId, tenant.authenticatedUserId(), project.id());
            sendJson(exchange, 201, projectToMap(project));
            return;
        }
        throw new AuthorizationException(404, "Not found.");
    }

    private TenantContext requireOrganizationTenant(HttpExchange exchange, UUID organizationId) throws IOException {
        AuthUserDirectory.User actor = requireAuthenticatedUser(exchange);
        try {
            return tenantContextResolver.resolve(session(exchange).id(), organizationId);
        } catch (TenantAuthorizationException error) {
            throw new AuthorizationException(error.statusCode(), error.statusCode() == 401 ? "Authentication is required." : "You are not authorized for this organization.");
        }
    }

    private void requireOrganizationManager(TenantContext tenant) {
        try { TenantAuthorizationPrimitives.requireOrganizationRole(tenant, OrganizationRole.OWNER, OrganizationRole.ADMIN); }
        catch (TenantAuthorizationException error) { throw new AuthorizationException(403, "An organization owner or admin is required."); }
    }

    private void ensureCreatorProjectMembership(String userId, String projectId) throws IOException {
        TeamMemberManager.Member existing = teamMemberManager.list().stream()
                .filter(member -> userId.equals(member.registeredUserId()))
                .findFirst().orElse(null);
        if (existing != null) {
            if (!TeamMemberManager.ACTIVE.equals(existing.status())) {
                throw new IllegalStateException("The project creator has an inactive team membership.");
            }
            if (existing.projectIds().contains(projectId)) return;
            List<String> projectIds = new ArrayList<>(existing.projectIds());
            projectIds.add(projectId);
            teamMemberManager.update(existing.id(), Map.of("projectIds", projectIds), validProjectIds(), teamRoleManager.list());
            return;
        }

        AuthUserDirectory.User creator = authUserDirectory.findById(userId);
        if (creator == null || !AuthUserDirectory.ACTIVE.equals(creator.status())) {
            throw new IllegalStateException("The project creator is no longer active.");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", creator.fullName());
        values.put("email", creator.email());
        values.put("username", creator.username());
        values.put("registeredUserId", creator.id());
        values.put("role", "Project Manager");
        values.put("projectIds", List.of(projectId));
        teamMemberManager.create(values, validProjectIds(), teamRoleManager.list());
    }

    private void auditOrganization(String action, UUID organizationId, String actorId, String subject) {
        try { authUserDirectory.recordAudit(action + ":" + organizationId, actorId + ":" + subject); }
        catch (IOException error) { throw new IllegalStateException("Organization audit recording failed.", error); }
    }

    private boolean hasExplicitProjectMembership(HttpExchange exchange, String projectId) {
        String actorId = authActorId(exchange);
        return teamMemberManager.list().stream().anyMatch(member -> actorId.equals(member.registeredUserId())
                && TeamMemberManager.ACTIVE.equals(member.status()) && member.projectIds().contains(projectId));
    }

    private static UUID parseUuid(String value, String field) {
        try { return UUID.fromString(OrganizationValidation.requiredText(value, field, 80)); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException(field + " is invalid."); }
    }

    private static <E extends Enum<E>> E parseEnum(Object value, Class<E> type, String field) {
        try { return Enum.valueOf(type, String.valueOf(value).trim().toUpperCase(Locale.ROOT)); }
        catch (Exception error) { throw new IllegalArgumentException(field + " is invalid."); }
    }

    private byte[] randomBytes(int size) { byte[] bytes = new byte[size]; invitationRandom.nextBytes(bytes); return bytes; }

    private static Map<String, Object> organizationToApiMap(Organization value, boolean includeOwner) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("organizationId", value.organizationId().toString()); map.put("organizationName", value.organizationName());
        map.put("organizationSlug", value.organizationSlug()); map.put("status", value.status().name());
        if (includeOwner) map.put("ownerUserId", value.ownerUserId());
        map.put("createdAt", value.createdAt().toString()); map.put("updatedAt", value.updatedAt().toString());
        return map;
    }

    private static Map<String, Object> membershipToApiMap(OrganizationMembership value) {
        return new LinkedHashMap<>(Map.of("membershipId", value.membershipId().toString(), "organizationId", value.organizationId().toString(), "userId", value.userId(), "organizationRole", value.organizationRole().name(), "membershipStatus", value.membershipStatus().name(), "createdAt", value.createdAt().toString(), "updatedAt", value.updatedAt().toString()));
    }

    private static Map<String, Object> invitationToApiMap(OrganizationInvitation value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("invitationId", value.invitationId().toString()); map.put("organizationId", value.organizationId().toString());
        map.put("invitedEmail", value.invitedEmail()); map.put("intendedRole", value.intendedRole().name());
        map.put("status", value.status().name()); map.put("expiresAt", value.expiresAt().toString()); map.put("createdAt", value.createdAt().toString());
        map.put("acceptedAt", value.acceptedAt() == null ? null : value.acceptedAt().toString());
        return map;
    }

    private static Map<String, Object> authUserToMap(AuthUserDirectory.User user) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", user.id()); result.put("fullName", user.fullName()); result.put("username", user.username()); result.put("email", user.email()); result.put("mobile", user.mobile());
        result.put("emailVerified", user.emailVerified()); result.put("mobileVerified", user.mobileVerified()); result.put("status", user.status());
        result.put("createdAt", user.createdAt()); result.put("updatedAt", user.updatedAt()); result.put("lastLoginAt", user.lastLoginAt());
        return result;
    }

    private static Map<String,Object> verificationToMap(AuthVerificationManager.Result result) {
        Map<String,Object> map=new LinkedHashMap<>(); map.put("verificationToken",result.token()); map.put("channel",result.channel()); map.put("destination",result.maskedDestination()); map.put("expiresAt",result.expiresAt().toString()); map.put("resendAt",result.resendAt().toString()); return map;
    }

    private String authActorName(HttpExchange exchange) {
        AuthSessionManager.Session current = session(exchange);
        AuthUserDirectory.User user = current == null ? null : authUserDirectory.findById(current.userId());
        return user == null ? "" : user.username();
    }

    private String authActorId(HttpExchange exchange) {
        AuthSessionManager.Session current = session(exchange);
        AuthUserDirectory.User user = current == null ? null : authUserDirectory.findById(current.userId());
        return user == null ? "" : user.id();
    }

    private Map<String, Object> wikiSpaceToMap(WikiManager.Space space) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", space.id()); map.put("name", space.name()); map.put("key", space.key()); map.put("description", space.description());
        map.put("createdBy", space.createdBy()); map.put("createdAt", space.createdAt()); map.put("updatedAt", space.updatedAt()); map.put("status", space.status());
        return map;
    }

    private Map<String, Object> wikiPageToMap(WikiManager.Page page) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", page.id()); map.put("spaceId", page.spaceId()); map.put("parentPageId", page.parentPageId()); map.put("title", page.title());
        map.put("content", page.content()); map.put("status", page.status()); map.put("createdBy", page.createdBy()); map.put("updatedBy", page.updatedBy());
        map.put("createdAt", page.createdAt()); map.put("updatedAt", page.updatedAt()); map.put("version", page.version());
        return map;
    }

    private Map<String, Object> ownershipToMap(ProjectOwnershipManager.Ownership ownership) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", ownership.id());
        map.put("resourceType", ownership.resourceType());
        map.put("resourceId", ownership.resourceId());
        map.put("projectId", ownership.projectId());
        map.put("status", ownership.status());
        map.put("source", ownership.source());
        map.put("createdAt", ownership.createdAt());
        map.put("updatedAt", ownership.updatedAt());
        return map;
    }

    private boolean projectAccess(HttpExchange exchange, String projectId, boolean write) {
        if (projectId == null || projectId.isBlank()) return false;
        TeamAuthorization authorization = teamAuthorization(exchange);
        if (!authorization.authenticated()) return false;
        List<TeamMemberManager.Member> members = teamMemberManager.list();
        return write
                ? authorization.canWriteProjectDomain(TeamAuthorization.USER_STORIES, projectId, members)
                : authorization.canReadProjectDomain(TeamAuthorization.USER_STORIES, projectId, members);
    }

    private boolean isGlobalAdmin(HttpExchange exchange) {
        TeamAuthorization authorization = teamAuthorization(exchange);
        TeamMemberManager.Member member = authorization.currentMember(teamMemberManager.list());
        return authorization.authenticated() && member != null && "Admin".equals(member.role());
    }

    private void requireAdminForUnownedResource(HttpExchange exchange, String path) {
        boolean unowned = path.equals("/api/database/overview")
                || path.equals("/api/testcases/stats")
                || path.equals("/api/testcases/bulk-delete")
                || path.equals("/api/automation-assets/scenarios");
        if (unowned && !isGlobalAdmin(exchange)) {
            throw new AuthorizationException(403, "This resource has no resolved project ownership.");
        }
    }

    private boolean tenantVisibleProject(HttpExchange exchange, String projectId) {
        try {
            ProjectOrganizationOwnership assignment = organizationStore.findProjectOwnership(projectId);
            if (assignment != null) {
                return tenantContextForProject(exchange, projectId, TeamAuthorization.USER_STORIES, "read", false) != null;
            }
            return projectAccess(exchange, projectId, false);
        } catch (IOException error) {
            throw new IllegalStateException("Tenant ownership data could not be loaded.", error);
        }
    }

    private boolean tenantProjectBelongsToOrganization(String projectId, UUID organizationId) {
        try {
            ProjectOrganizationOwnership assignment = organizationStore.findProjectOwnership(projectId);
            return assignment != null && organizationId.equals(assignment.organizationId());
        } catch (IOException error) {
            throw new IllegalStateException("Tenant ownership data could not be loaded.", error);
        }
    }

    private boolean tenantVisibleOwnedResource(HttpExchange exchange, String resourceType, String resourceId,
                                               String domain, boolean write) {
        try {
            String projectId = projectOwnershipManager.projectId(resourceType, resourceId);
            if (projectId.isBlank()) return isGlobalAdmin(exchange);
            ProjectOrganizationOwnership assignment = organizationStore.findProjectOwnership(projectId);
            if (assignment != null) {
                return tenantContextForProject(exchange, projectId, domain, write ? "write" : "read", false) != null;
            }
            return ownedProjectAccess(exchange, resourceType, resourceId, domain, write);
        } catch (IOException error) {
            throw new IllegalStateException("Tenant ownership data could not be loaded.", error);
        }
    }

    /**
     * Tenant enforcement is activated only for resources with an authoritative
     * project-to-organization assignment. Missing organization context then
     * fails closed for a specific route and is filtered out for list routes.
     * Unassigned legacy data remains on its existing Level 3 compatibility path.
     */
    private TenantContext tenantContextForProject(HttpExchange exchange, String projectId, String domain,
                                                  String action, boolean required) {
        if (projectId == null || projectId.isBlank()) {
            if (required) throw new AuthorizationException(403, "A project and organization scope are required.");
            return null;
        }
        try {
            ProjectOrganizationOwnership assignment = organizationStore.findProjectOwnership(projectId);
            if (assignment == null) {
                if (required && queryValue(exchange, "organizationId") != null) {
                    throw new AuthorizationException(403, "The project has no authorized organization ownership.");
                }
                return null;
            }
            String rawOrganizationId = queryValue(exchange, "organizationId");
            if (rawOrganizationId == null || rawOrganizationId.isBlank()) {
                if (required) throw new AuthorizationException(403, "An organization scope is required.");
                return null;
            }
            UUID organizationId;
            try {
                organizationId = UUID.fromString(rawOrganizationId.trim());
            } catch (IllegalArgumentException error) {
                throw new AuthorizationException(400, "The organization scope is invalid.");
            }
            AuthSessionManager.Session current = session(exchange);
            if (current == null) throw new AuthorizationException(401, "Authentication is required.");
            try {
                return tenantContextResolver.resolveProject(current.id(), organizationId, projectId, domain, action);
            } catch (TenantAuthorizationException error) {
                if (!required && error.statusCode() != 401 && error.statusCode() != 400) return null;
                throw new AuthorizationException(error.statusCode(), "The requested tenant resource is not authorized.");
            }
        } catch (IOException error) {
            throw new IllegalStateException("Tenant authorization data could not be loaded.", error);
        }
    }

    private boolean ownedProjectAccess(HttpExchange exchange, String resourceType, String resourceId,
                                       String domain, boolean write) {
        if (isGlobalAdmin(exchange)) return true;
        String projectId = projectOwnershipManager.projectId(resourceType, resourceId);
        return !projectId.isBlank() && projectAccessForDomain(exchange, projectId, domain, write);
    }

    private boolean automationExecutionVisible(HttpExchange exchange, Map<String, Object> execution) {
        if (isGlobalAdmin(exchange)) return true;
        String organizationText = stringField(execution, "organizationId");
        String projectId = stringField(execution, "projectId");
        if (organizationText.isBlank() || projectId.isBlank()) return false;
        try {
            UUID organizationId = UUID.fromString(organizationText);
            ProjectOrganizationOwnership assignment = organizationStore.findProjectOwnership(projectId);
            if (assignment == null || !organizationId.equals(assignment.organizationId())) return false;
            AuthSessionManager.Session current = session(exchange);
            if (current == null) return false;
            tenantContextResolver.resolveProject(current.id(), organizationId, projectId,
                    TeamAuthorization.TEST_EXECUTION, "read");
            return true;
        } catch (IllegalArgumentException | TenantAuthorizationException error) {
            return false;
        } catch (IOException error) {
            throw new IllegalStateException("Tenant authorization data could not be loaded.", error);
        }
    }

    private void requireOwnedProjectAccess(HttpExchange exchange, String resourceType, String resourceId,
                                           String domain, boolean write) {
        if (!ownedProjectAccess(exchange, resourceType, resourceId, domain, write)) {
            throw new AuthorizationException(403, "This resource has no accessible project ownership.");
        }
    }

    private boolean artifactVisible(HttpExchange exchange, String resourceType, String fileName, String domain) {
        if (isGlobalAdmin(exchange)) return true;
        String projectId = projectOwnershipManager.projectId(resourceType, fileName);
        return resolvedTenantProjectAccess(exchange, projectId, domain, false);
    }

    private void requireArtifactAccess(HttpExchange exchange, String resourceType, String fileName,
                                       String domain, boolean write) {
        if (isGlobalAdmin(exchange)) return;
        String projectId = projectOwnershipManager.projectId(resourceType, fileName);
        if (!resolvedTenantProjectAccess(exchange, projectId, domain, write)) {
            throw new AuthorizationException(403, "This artifact has no accessible project ownership.");
        }
    }

    private boolean resolvedTenantProjectAccess(HttpExchange exchange, String projectId, String domain, boolean write) {
        if (projectId == null || projectId.isBlank()) return false;
        try {
            ProjectOrganizationOwnership assignment = organizationStore.findProjectOwnership(projectId);
            if (assignment == null) return false;
            String requestedOrganizationId = queryValue(exchange, "organizationId");
            if (requestedOrganizationId != null && !requestedOrganizationId.isBlank()
                    && !assignment.organizationId().toString().equals(requestedOrganizationId.trim())) return false;
            AuthSessionManager.Session current = session(exchange);
            if (current == null) return false;
            tenantContextResolver.resolveProject(current.id(), assignment.organizationId(), projectId, domain,
                    write ? "write" : "read");
            return true;
        } catch (TenantAuthorizationException error) {
            return false;
        } catch (IOException error) {
            throw new IllegalStateException("Tenant authorization data could not be loaded.", error);
        }
    }

    private UserStoryStore.RelatedLink requireRelatedLinkAccess(HttpExchange exchange, String linkId, boolean write) {
        UserStoryStore.RelatedLink link;
        try {
            link = userStoryStore.findRelatedLink(linkId);
        } catch (IllegalArgumentException error) {
            throw new AuthorizationException(404, "Related link not found.");
        } catch (IOException error) {
            throw new IllegalStateException("Related-link authorization data could not be loaded.", error);
        }
        String projectId = projectIdForStory(link.storyId());
        if (!resolvedTenantProjectAccess(exchange, projectId, TeamAuthorization.USER_STORIES, write)) {
            throw new AuthorizationException(403, "You are not authorized for this related link.");
        }
        return link;
    }

    /** Records only artifacts modified during this run. Existing files remain unresolved/admin-only. */
    private void recordRunArtifacts(RunEntry entry) {
        String projectId = projectOwnershipManager.projectId("TEST_RUN", entry.id);
        if (projectId.isBlank()) return;
        long started = 0L;
        try { started = Instant.parse(entry.startedAt).toEpochMilli(); } catch (Exception ignored) { }
        for (String[] spec : new String[][]{{"SCREENSHOT", "target/screenshots", ".png"}, {"VIDEO", "target/videos", ""}}) {
            Path directory = projectRoot.resolve(spec[1]).normalize();
            if (!Files.isDirectory(directory)) continue;
            try (var stream = Files.list(directory)) {
                for (Path file : stream.filter(Files::isRegularFile).toList()) {
                    String name = file.getFileName().toString();
                    if (!spec[2].isEmpty() && !name.toLowerCase(Locale.ROOT).endsWith(spec[2])) continue;
                    if (started > 0 && Files.getLastModifiedTime(file).toMillis() < started) continue;
                    try {
                        projectOwnershipManager.assign(spec[0], name, projectId, "RUN_ARTIFACT", validProjectIds());
                    } catch (Exception error) {
                        System.err.println("Could not record artifact ownership: " + error.getMessage());
                    }
                }
            } catch (IOException error) {
                System.err.println("Could not inspect run artifacts: " + error.getMessage());
            }
        }
    }

    private boolean projectAccessForMap(HttpExchange exchange, Map<String, Object> value, String domain, boolean write) {
        Object projectId = value.get("projectId");
        return projectId != null && !String.valueOf(projectId).isBlank()
                && projectAccessForDomain(exchange, String.valueOf(projectId), domain, write);
    }

    private boolean projectAccessForDomain(HttpExchange exchange, String projectId, String domain, boolean write) {
        return resolvedTenantProjectAccess(exchange, projectId, domain, write);
    }

    private void requireProjectAccess(HttpExchange exchange, String projectId, String domain, boolean write) {
        if (projectId == null || projectId.isBlank()) {
            throw new AuthorizationException(403, "A project scope is required for this operation.");
        }
        if (!resolvedTenantProjectAccess(exchange, projectId, domain, write)) {
            throw new AuthorizationException(403, "You are not authorized for this project.");
        }
    }

    private void requireDefectProjectAccess(HttpExchange exchange, String defectId, boolean write) {
        Map<String, Object> defect = defectManager.list(null).stream()
                .filter(item -> defectId.equals(String.valueOf(item.get("id")))).findFirst()
                .orElseThrow(() -> new AuthorizationException(404, "Defect not found."));
        requireProjectAccess(exchange, optionalString(defect, "projectId"), TeamAuthorization.DEFECTS, write);
    }

    private void requireEpicProjectAccess(HttpExchange exchange, String epicId, boolean write) {
        try {
            UserStoryStore.Epic epic = userStoryStore.listEpics(null, true).stream()
                    .filter(item -> epicId.equals(item.id())).findFirst()
                    .orElseThrow(() -> new AuthorizationException(404, "Epic not found."));
            requireProjectAccess(exchange, epic.projectId(), TeamAuthorization.USER_STORIES, write);
        } catch (IOException error) {
            throw new IllegalStateException("Epic authorization data could not be loaded.", error);
        }
    }

    private void requireSprintProjectAccess(HttpExchange exchange, String sprintId, boolean write) {
        try {
            UserStoryStore.Sprint sprint = userStoryStore.listSprints(null, true).stream()
                    .filter(item -> sprintId.equals(item.id())).findFirst()
                    .orElseThrow(() -> new AuthorizationException(404, "Sprint not found."));
            requireProjectAccess(exchange, sprint.projectId(), TeamAuthorization.SPRINT_BOARD, write);
        } catch (IOException error) {
            throw new IllegalStateException("Sprint authorization data could not be loaded.", error);
        }
    }

    private void requireStoryProjectAccess(HttpExchange exchange, String storyId, boolean write) {
        UserStoryStore.Story story;
        try {
            story = userStoryStore.listStories(null, null, null, 0, 10000, true).stream()
                    .filter(item -> item.id().equals(storyId)).findFirst()
                    .orElseThrow(() -> new AuthorizationException(404, "User story not found."));
        } catch (java.io.IOException error) {
            throw new IllegalStateException("User story authorization data could not be loaded.", error);
        }
        requireProjectAccess(exchange, story.projectId(), TeamAuthorization.USER_STORIES, write);
    }

    private void requireCommentMutationAccess(HttpExchange exchange, String storyId, String commentId) {
        if (isGlobalAdmin(exchange)) return;
        try {
            UserStoryStore.Activity comment = userStoryStore.listActivity(storyId).stream()
                    .filter(item -> item.id().equals(commentId) && "Comment".equals(item.action()))
                    .findFirst()
                    .orElseThrow(() -> new AuthorizationException(404, "Comment not found."));
            if (!authActorId(exchange).equals(comment.actorId())) {
                throw new AuthorizationException(403, "Only the comment author or an administrator may change this comment.");
            }
        } catch (IOException error) {
            throw new IllegalStateException("Comment authorization data could not be loaded.", error);
        }
    }

    private String projectIdForStory(String storyId) {
        if (storyId == null || storyId.isBlank()) return "";
        try {
            return userStoryStore.listStories(null, null, null, 0, 100000, true).stream()
                    .filter(item -> storyId.equals(item.id())).map(UserStoryStore.Story::projectId).findFirst().orElse("");
        } catch (IOException error) {
            throw new IllegalStateException("User story ownership data could not be loaded.", error);
        }
    }

    private TeamAuthorization teamAuthorization(HttpExchange exchange) {
        AuthSessionManager.Session current = session(exchange);
        if (current == null) return new TeamAuthorization(CURRENT_USER_ID, CURRENT_USER_NAME, false, teamRoleManager.list());
        AuthUserDirectory.User user = authUserDirectory.findById(current.userId());
        if (user == null || !AuthUserDirectory.ACTIVE.equals(user.status())) {
            return new TeamAuthorization("", "", true, teamRoleManager.list());
        }
        return new TeamAuthorization(user.id(), user.username(), true, teamRoleManager.list());
    }

    private void requireTeamView(HttpExchange exchange) {
        requireAuthenticatedUser(exchange);
        if (!teamAuthorization(exchange).canViewTeam(teamMemberManager.list())) {
            throw new AuthorizationException(403, "An authenticated team membership is required to view team data.");
        }
    }

    private void requireTeamManage(HttpExchange exchange, Map<String, Object> values, String existingId) {
        List<TeamMemberManager.Member> members = teamMemberManager.list();
        TeamMemberManager.Member target = existingId == null ? null : members.stream()
                .filter(member -> existingId.equals(member.id())).findFirst().orElse(null);
        if (existingId != null && target == null) {
            throw new AuthorizationException(404, "Team member not found.");
        }
        TeamAuthorization authorization = teamAuthorization(exchange);
        if (!authorization.canManageMemberMutation(values, target, members)) {
            throw new AuthorizationException(403, "You are not authorized to manage team members.");
        }
        Object requestedRole = values.get("role");
        if (requestedRole != null && !authorization.canManageRole(String.valueOf(requestedRole), members)) {
            throw new AuthorizationException(403, "Only an authenticated Admin can grant or change team roles.");
        }
    }

    private void requireAuthenticatedAdmin(HttpExchange exchange) {
        TeamAuthorization authorization = teamAuthorization(exchange);
        if (!authorization.authenticated()) {
            throw new AuthorizationException(401, "An authenticated Admin identity is required for role management.");
        }
        TeamMemberManager.Member member = authorization.currentMember(teamMemberManager.list());
        if (member == null || !"Admin".equals(member.role())) {
            throw new AuthorizationException(403, "Only an authorized Admin can manage roles and permissions.");
        }
    }

    private String requireCiCdManage(HttpExchange exchange) {
        AuthUserDirectory.User user = requireAuthenticatedUser(exchange);
        TeamMemberManager.Member member = teamMemberManager.list().stream()
                .filter(item -> user.id().equals(item.registeredUserId())
                        && TeamMemberManager.ACTIVE.equals(item.status())).findFirst().orElse(null);
        if (member == null || !Set.of("Admin", "Project Manager", "QA Lead").contains(member.role())) {
            throw new AuthorizationException(403, "Your role cannot manage CI/CD configuration or execution.");
        }
        return requireCiCdProjectAccess(exchange, true);
    }

    private String requireCiCdProjectAccess(HttpExchange exchange, boolean write) {
        String projectId = queryValue(exchange, "projectId");
        String organizationId = queryValue(exchange, "organizationId");
        if (projectId == null || projectId.isBlank() || organizationId == null || organizationId.isBlank()) {
            throw new AuthorizationException(403, "CI/CD project and organization scope are required.");
        }
        TenantContext context = tenantContextForProject(exchange, projectId, TeamAuthorization.CICD,
                write ? "write" : "read", true);
        if (context == null || !organizationId.equals(context.organizationId().toString())) {
            throw new AuthorizationException(403, "You are not authorized for this CI/CD project.");
        }
        return projectId;
    }

    private static final class AuthorizationException extends RuntimeException {
        private final int status;

        private AuthorizationException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private static String stringField(Map<?, ?> values, String name) {
        Object value = values.get(name);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new IllegalArgumentException("Enter a value for " + name + ".");
    }

    private static boolean booleanField(Map<?, ?> values, String name) {
        Object value = values.get(name);
        if (value instanceof Boolean result) {
            return result;
        }
        throw new IllegalArgumentException("Invalid value for " + name + ".");
    }

    private static void sendJson(HttpExchange exchange, int status, Object body)
            throws IOException {
        byte[] content = json(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, content.length);
        exchange.getResponseBody().write(content);
    }

    private static void sendError(HttpExchange exchange, int status, String message)
            throws IOException {
        sendJson(exchange, status, Map.of("error",
                message == null || message.isBlank() ? "The request could not be completed."
                        : message));
    }

    private static String json(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            StringBuilder escaped = new StringBuilder("\"");
            for (char character : text.toCharArray()) {
                switch (character) {
                    case '"' -> escaped.append("\\\"");
                    case '\\' -> escaped.append("\\\\");
                    case '\b' -> escaped.append("\\b");
                    case '\f' -> escaped.append("\\f");
                    case '\n' -> escaped.append("\\n");
                    case '\r' -> escaped.append("\\r");
                    case '\t' -> escaped.append("\\t");
                    default -> {
                        if (character < 0x20) {
                            escaped.append(String.format("\\u%04x", (int) character));
                        } else {
                            escaped.append(character);
                        }
                    }
                }
            }
            return escaped.append('"').toString();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            List<String> fields = new ArrayList<>();
            map.forEach((key, child) -> fields.add(json(String.valueOf(key)) + ":" + json(child)));
            return "{" + String.join(",", fields) + "}";
        }
        if (value instanceof Iterable<?> values) {
            List<String> items = new ArrayList<>();
            values.forEach(item -> items.add(json(item)));
            return "[" + String.join(",", items) + "]";
        }
        throw new IllegalArgumentException("Unsupported JSON response value.");
    }

    private static Thread daemonThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private void trimRuns() {
        while (runs.size() > MAX_RUNS) {
            String oldest = runs.keySet().iterator().next();
            if (oldest.equals(activeRunId)) {
                break;
            }
            runs.remove(oldest);
        }
    }

    private static final class RunEntry {
        private final String id;
        private final String kindName;  // plugin name (e.g., "cucumber", "newman")
        private final String selection;
        private final String targetUrl;
        private final TenantJobContext jobContext;
        private final String startedAt = Instant.now().toString();
        private final List<String> output = new CopyOnWriteArrayList<>();
        private volatile String status = "QUEUED";
        private volatile String finishedAt;
        private volatile boolean cancelRequested;
        private volatile ExecutionService.RunResult result;
        private volatile String error;
        private volatile String testCaseId;
        private volatile String assetId;
        private volatile String executionSource = "LOCAL_RUNNER";
        private volatile String ciProvider;
        private volatile String pipelineRunId;
        private volatile String executionScope = "ASSET";
        private volatile String scenarioName;
        private volatile String scenarioTag;
        /** Populated only for collection runs; null for single runs. */
        private List<String> selections;
        /** Browsers to run tests in; defaults to ["chrome"]. */
        private List<String> browsers;

        private RunEntry(String id, String kindName, String selection,
                         String targetUrl, TenantJobContext jobContext) {
            this.id = id;
            this.kindName = kindName;
            this.selection = selection;
            this.targetUrl = targetUrl;
            this.jobContext = jobContext;
        }

        private void addLog(String line) {
            if (output.size() >= 400) {
                output.remove(0);
            }
            output.add(line);
        }

        private List<String> logs() {
            return List.copyOf(output);
        }
    }

    private List<Map<String, Object>> listScreenshots(HttpExchange exchange) {
        Path screenshotsDir = projectRoot.resolve("target").resolve("screenshots");
        List<Map<String, Object>> result = new ArrayList<>();
        if (!Files.isDirectory(screenshotsDir)) {
            return result;
        }
        try (var stream = Files.list(screenshotsDir)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".png"))
                    .sorted(Comparator.comparingLong((Path p) -> {
                        try { return p.toFile().lastModified(); } catch (Exception e) { return 0L; }
                    }).reversed())
                    .toList();
            for (Path p : files) {
                String fileName = p.getFileName().toString();
                if (!artifactVisible(exchange, "SCREENSHOT", fileName, TeamAuthorization.TEST_EXECUTION)) continue;
                String testCaseName = fileName.replaceAll("\\.png$", "");
                int dashIndex = testCaseName.lastIndexOf("-");
                if (dashIndex > 0 && dashIndex < testCaseName.length() - 10) {
                    testCaseName = testCaseName.substring(0, dashIndex).replaceAll("_", " ");
                }
                result.add(Map.of(
                        "name", testCaseName,
                        "file", fileName,
                        "size", Files.size(p),
                        "modified", p.toFile().lastModified()
                ));
            }
        } catch (IOException e) {
            System.err.println("Failed to list screenshots: " + e.getMessage());
        }
        return result;
    }

    private List<Map<String, Object>> listVideos(HttpExchange exchange) {
        Path videosDir = projectRoot.resolve("target").resolve("videos");
        List<Map<String, Object>> result = new ArrayList<>();
        if (!Files.isDirectory(videosDir)) {
            return result;
        }
        try (var stream = Files.list(videosDir)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".webm") || p.getFileName().toString().endsWith(".mp4"))
                    .sorted(Comparator.comparingLong((Path p) -> {
                        try { return p.toFile().lastModified(); } catch (Exception e) { return 0L; }
                    }).reversed())
                    .toList();
            for (Path p : files) {
                String fileName = p.getFileName().toString();
                if (!artifactVisible(exchange, "VIDEO", fileName, TeamAuthorization.TEST_EXECUTION)) continue;
                String testCaseName = fileName.replaceAll("\\.(webm|mp4)$", "");
                int dashIndex = testCaseName.lastIndexOf("-");
                if (dashIndex > 0 && dashIndex < testCaseName.length() - 10) {
                    testCaseName = testCaseName.substring(0, dashIndex).replaceAll("_", " ");
                }
                result.add(Map.of(
                        "name", testCaseName,
                        "file", fileName,
                        "size", Files.size(p),
                        "modified", p.toFile().lastModified()
                ));
            }
        } catch (IOException e) {
            System.err.println("Failed to list videos: " + e.getMessage());
        }
        return result;
    }

    // === Screenshot Delete Methods ===

    private void deleteScreenshot(HttpExchange exchange, String fileName) throws IOException {
        if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            sendError(exchange, 400, "Invalid screenshot path.");
            return;
        }
        Path screenshotsDir = projectRoot.resolve("target").resolve("screenshots");
        Path screenshot = screenshotsDir.resolve(fileName).normalize();
        if (!screenshot.startsWith(screenshotsDir) || !Files.isRegularFile(screenshot)) {
            sendError(exchange, 404, "Screenshot not found: " + fileName);
            return;
        }
        requireArtifactAccess(exchange, "SCREENSHOT", fileName, TeamAuthorization.TEST_EXECUTION, true);
        StorageWriteCoordinator.coordinatedWrite(projectRoot, "media-artifacts", () -> Files.delete(screenshot));
        sendJson(exchange, 200, Map.of("message", "Screenshot deleted: " + fileName));
    }

    private void deleteAllScreenshots(HttpExchange exchange) throws IOException {
        Path screenshotsDir = projectRoot.resolve("target").resolve("screenshots");
        if (!Files.isDirectory(screenshotsDir)) {
            sendJson(exchange, 200, Map.of("message", "No screenshots to delete."));
            return;
        }
        int count = 0;
        try (var stream = Files.list(screenshotsDir)) {
            for (Path p : stream.filter(Files::isRegularFile).toList()) {
                if (!artifactVisible(exchange, "SCREENSHOT", p.getFileName().toString(), TeamAuthorization.TEST_EXECUTION)) continue;
                try {
                    StorageWriteCoordinator.coordinatedWrite(projectRoot, "media-artifacts", () -> Files.delete(p));
                    count++;
                } catch (IOException e) {
                    System.err.println("Failed to delete screenshot: " + p + " - " + e.getMessage());
                }
            }
        }
        sendJson(exchange, 200, Map.of("message", count + " screenshot(s) deleted."));
    }

    // === Video Delete Methods ===

    private void deleteVideo(HttpExchange exchange, String fileName) throws IOException {
        if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            sendError(exchange, 400, "Invalid video path.");
            return;
        }
        Path videosDir = projectRoot.resolve("target").resolve("videos");
        Path video = resolveContainedMediaFile(videosDir, fileName);
        if (video == null) {
            sendError(exchange, 404, "Video not found: " + fileName);
            return;
        }
        requireArtifactAccess(exchange, "VIDEO", fileName, TeamAuthorization.TEST_EXECUTION, true);
        StorageWriteCoordinator.coordinatedWrite(projectRoot, "media-artifacts", () -> Files.delete(video));
        sendJson(exchange, 200, Map.of("message", "Video deleted: " + fileName));
    }

    private void deleteAllVideos(HttpExchange exchange) throws IOException {
        Path videosDir = projectRoot.resolve("target").resolve("videos");
        if (!Files.isDirectory(videosDir)) {
            sendJson(exchange, 200, Map.of("message", "No videos to delete."));
            return;
        }
        int count = 0;
        try (var stream = Files.list(videosDir)) {
            for (Path p : stream.filter(Files::isRegularFile).toList()) {
                if (!artifactVisible(exchange, "VIDEO", p.getFileName().toString(), TeamAuthorization.TEST_EXECUTION)) continue;
                try {
                    StorageWriteCoordinator.coordinatedWrite(projectRoot, "media-artifacts", () -> Files.delete(p));
                    count++;
                } catch (IOException e) {
                    System.err.println("Failed to delete video: " + p + " - " + e.getMessage());
                }
            }
        }
        sendJson(exchange, 200, Map.of("message", count + " video(s) deleted."));
    }

    private void serveVideo(HttpExchange exchange, String path) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            sendError(exchange, 405, "Method not allowed.");
            return;
        }
        String fileName = path.substring("/videos/".length());
        if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            sendError(exchange, 400, "Invalid video path.");
            return;
        }
        Path videosDir = projectRoot.resolve("target").resolve("videos");
        Path video = videosDir.resolve(fileName).normalize();
        if (!video.startsWith(videosDir) || !Files.isRegularFile(video)) {
            sendError(exchange, 404, "Video not found.");
            return;
        }
        requireArtifactAccess(exchange, "VIDEO", fileName, TeamAuthorization.TEST_EXECUTION, false);
        String contentType = fileName.endsWith(".webm") ? "video/webm" : "video/mp4";
        streamFileResponse(exchange, video, contentType);
    }

    private void streamFileResponse(HttpExchange exchange, Path file, String contentType) throws IOException {
        long contentLength = Files.size(file);
        try (InputStream input = Files.newInputStream(file)) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, contentLength);
            try (OutputStream output = exchange.getResponseBody()) {
                byte[] buffer = new byte[16 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
            }
        }
    }

    /** Resolve media through the filesystem and require the target to remain below its trusted root. */
    private static Path resolveContainedMediaFile(Path trustedRoot, String fileName) {
        try {
            Path realRoot = trustedRoot.toRealPath();
            Path lexicalCandidate = trustedRoot.resolve(fileName).normalize();
            if (!lexicalCandidate.startsWith(trustedRoot)) return null;
            Path realCandidate = lexicalCandidate.toRealPath();
            return realCandidate.startsWith(realRoot) && Files.isRegularFile(realCandidate)
                    ? realCandidate : null;
        } catch (IOException error) {
            return null;
        }
    }

    // === Dependency Check ===

    /**
     * Check local environment for required dependencies and return status for each framework.
     */
    private Map<String, Object> checkDependencies() {
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            Map<String, Object> mavenStatus = findMavenCLI();
            Map<String, Object> nodeStatus = checkCommand("node");
            Map<String, Object> npmStatus = findNpm();
            Map<String, Object> jmeterStatus = findJMeterCLI();
            Map<String, Object> appiumStatus = checkAppiumServer();

            result.put("maven", mavenStatus);
            result.put("node", nodeStatus);
            result.put("npm", npmStatus);
            result.put("git", checkCommand("git"));
            result.put("jmeter", jmeterStatus);
            result.put("appium", appiumStatus);
            result.put("java", Map.of("available", true, "version", System.getProperty("java.version")));

            // Framework-specific requirements
            List<Map<String, Object>> frameworks = new ArrayList<>();
            frameworks.add(frameworkDep("cucumber", "Cucumber", "🥒", List.of("maven"),
                    "Maven is required to run Cucumber/Selenium tests.",
                    mavenStatus, nodeStatus, npmStatus, jmeterStatus, appiumStatus));
            frameworks.add(frameworkDep("newman", "Newman", "🔌", List.of("node", "npm"),
                    "Node.js and npm are required. Run 'npm install' to install Newman.",
                    mavenStatus, nodeStatus, npmStatus, jmeterStatus, appiumStatus));
            frameworks.add(frameworkDep("playwright", "Playwright", "🎭", List.of("maven"),
                    "Maven is required. Ensure pom.xml has Playwright dependencies.",
                    mavenStatus, nodeStatus, npmStatus, jmeterStatus, appiumStatus));
            frameworks.add(frameworkDep("karate", "Karate", "🥋", List.of("maven"),
                    "Maven is required. Ensure pom.xml has Karate dependencies.",
                    mavenStatus, nodeStatus, npmStatus, jmeterStatus, appiumStatus));
            frameworks.add(frameworkDep("wdio", "WebdriverIO", "🌐", List.of("node", "npm"),
                    "Node.js and npm are required. Run 'npm install' in the WDIO project folder.",
                    mavenStatus, nodeStatus, npmStatus, jmeterStatus, appiumStatus));
            frameworks.add(frameworkDep("restassured", "RestAssured", "⚡", List.of("maven"),
                    "Maven is required to run RestAssured API tests.",
                    mavenStatus, nodeStatus, npmStatus, jmeterStatus, appiumStatus));
            frameworks.add(frameworkDep("soapui", "SoapUI", "🧪", List.of(),
                    "No external dependencies needed — runs with built-in HTTP client.",
                    mavenStatus, nodeStatus, npmStatus, jmeterStatus, appiumStatus));
            frameworks.add(frameworkDep("jmeter", "JMeter", "⏱️", List.of(),
                    "No JMeter CLI needed — runs with built-in HTTP sampler parser.",
                    mavenStatus, nodeStatus, npmStatus, jmeterStatus, appiumStatus));
            frameworks.add(frameworkDep("jmetercli", "JMeter CLI", "⏱️", List.of("jmeter"),
                    "Apache JMeter CLI is required for full load testing.",
                    mavenStatus, nodeStatus, npmStatus, jmeterStatus, appiumStatus));
            frameworks.add(frameworkDep("appium", "Appium", "📱", List.of("maven", "appium"),
                    "Appium server and Android SDK are required for real device testing. Simulation mode works without them.",
                    mavenStatus, nodeStatus, npmStatus, jmeterStatus, appiumStatus));
            frameworks.add(frameworkDep("gatling", "Gatling", "🔥", List.of("maven"),
                    "Maven is required to run Gatling simulations. Place a pom.xml with Gatling Maven plugin in the project directory.",
                    mavenStatus, nodeStatus, npmStatus, jmeterStatus, appiumStatus));
            frameworks.add(frameworkDep("database", "Database", "🗄️", List.of(),
                    "Supports H2 (embedded), MySQL, PostgreSQL, DB2, and Snowflake. JDBC drivers are bundled — no extra installation needed. Configure connection in database.properties or via target URL.",
                    mavenStatus, nodeStatus, npmStatus, jmeterStatus, appiumStatus));
            frameworks.add(frameworkDep("testng", "TestNG + Selenium", "🔶", List.of("maven"),
                    "TestNG data-driven testing with Selenium + Page Object Model. Maven is required to compile and run TestNG test classes with DataProviders.",
                    mavenStatus, nodeStatus, npmStatus, jmeterStatus, appiumStatus));
            frameworks.add(frameworkDep("pytest", "Pytest Python", "🐍", List.of("python"),
                    "Python Pytest with Selenium for UI/API testing. Python 3.8+ and pytest are required. Install with: pip install pytest selenium webdriver-manager.",
                    mavenStatus, nodeStatus, npmStatus, jmeterStatus, appiumStatus));
            frameworks.add(frameworkDep("robot", "Robot Framework", "🤖", List.of("python"),
                    "Robot Framework keyword-driven testing with SeleniumLibrary. Python 3.8+ and Robot Framework are required. Install with: pip install robotframework robotframework-seleniumlibrary.",
                    mavenStatus, nodeStatus, npmStatus, jmeterStatus, appiumStatus));
            result.put("frameworks", frameworks);
            return result;
        } catch (Exception e) {
            System.err.println("checkDependencies failed: " + e.getMessage());
            e.printStackTrace();
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("maven", Map.of("available", false, "version", null));
            fallback.put("node", Map.of("available", false, "version", null));
            fallback.put("npm", Map.of("available", false, "version", null));
            fallback.put("git", Map.of("available", false, "version", null));
            fallback.put("jmeter", Map.of("available", false, "version", null));
            fallback.put("appium", Map.of("available", false, "version", null));
            fallback.put("java", Map.of("available", true, "version", System.getProperty("java.version")));
            fallback.put("frameworks", List.of());
            return fallback;
        }
    }

    private Map<String, Object> checkCommand(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd, "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("available", false);
                r.put("version", "not found");
                return r;
            }
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            }
            String version = sb.toString().trim();
            if (version.isEmpty()) version = "installed";
            return Map.of("available", true, "version", version);
        } catch (Exception e) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("available", false);
            r.put("version", "not found");
            return r;
        }
    }

    /**
     * Find npm — checks Node.js installation directory first, then system PATH.
     * npm comes bundled with Node.js, so if Node.js is found, npm should be nearby.
     */
    private Map<String, Object> findNpm() {
        // Try to find npm.cmd next to node.exe
        String[] nodePathsToCheck = {
            "C:\\Program Files\\nodejs\\npm.cmd",
            "C:\\Program Files (x86)\\nodejs\\npm.cmd"
        };
        for (String path : nodePathsToCheck) {
            Path npmPath = Path.of(path);
            if (Files.isRegularFile(npmPath)) {
                try {
                    ProcessBuilder pb = new ProcessBuilder(npmPath.toString(), "--version");
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    boolean finished = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                    if (finished) {
                        StringBuilder sb = new StringBuilder();
                        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                                new java.io.InputStreamReader(p.getInputStream()))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                sb.append(line).append("\n");
                            }
                        }
                        String version = sb.toString().trim();
                        if (version.isEmpty()) version = "installed";
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("available", true);
                        r.put("version", "v" + version + " (local)");
                        return r;
                    }
                } catch (Exception ignored) {
                    // Fall through
                }
            }
        }
        // Also check the directory where node.exe is located
        String nodeExe = System.getenv("NODE_PATH");
        if (nodeExe != null) {
            Path npmInNodeDir = Path.of(nodeExe).resolve("npm.cmd");
            if (Files.isRegularFile(npmInNodeDir)) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("available", true);
                r.put("version", "installed (local)");
                return r;
            }
        }
        // Fall back to system PATH
        return checkCommand("npm");
    }

    /**
     * Find Maven — checks local apache-maven directory first, then system PATH.
     */
    private Map<String, Object> findMavenCLI() {
        // Check local apache-maven directory first
        Path mavenDir = projectRoot.resolve("apache-maven");
        if (Files.isDirectory(mavenDir)) {
            // Find the extracted apache-maven-X.Y.Z subdirectory
            try (var stream = Files.list(mavenDir)) {
                for (Path p : stream.filter(Files::isDirectory).toList()) {
                    String name = p.getFileName().toString();
                    if (name.startsWith("apache-maven-")) {
                        Path mvnCmd = p.resolve("bin").resolve("mvn.cmd");
                        Path mvnSh = p.resolve("bin").resolve("mvn");
                        if (Files.exists(mvnCmd) || Files.exists(mvnSh)) {
                            // Extract version from directory name: apache-maven-3.9.9 -> 3.9.9
                            String version = name.substring("apache-maven-".length());
                            Map<String, Object> r = new LinkedHashMap<>();
                            r.put("available", true);
                            r.put("version", "Apache Maven " + version + " (local)");
                            return r;
                        }
                    }
                }
            } catch (Exception ignored) {
                // Fall through to check direct mvn.cmd in apache-maven/bin
            }
            // Check if mvn.cmd is directly in apache-maven/bin (flat extraction)
            Path directMvn = mavenDir.resolve("bin").resolve("mvn.cmd");
            if (Files.exists(directMvn)) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("available", true);
                r.put("version", "installed (local)");
                return r;
            }
        }
        // Fall back to system PATH
        return checkCommand("mvn");
    }

    private Map<String, Object> findJMeterCLI() {
        // Check bundled JMeter first
        Path bundled = projectRoot.resolve("apache-jmeter-5.6.3").resolve("bin").resolve("jmeter.bat");
        if (Files.isRegularFile(bundled)) {
            return Map.of("available", true, "path", bundled.toString(), "version", "5.6.3");
        }
        // Check JMETER_HOME
        String jmeterHome = System.getenv("JMETER_HOME");
        if (jmeterHome != null) {
            Path jmeterBat = Path.of(jmeterHome).resolve("bin").resolve("jmeter.bat");
            if (Files.isRegularFile(jmeterBat)) {
                return Map.of("available", true, "path", jmeterBat.toString(), "version", "bundled");
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("available", false);
        r.put("path", "not found");
        r.put("installHint", "Download from https://jmeter.apache.org/download_jmeter.cgi");
        return r;
    }

    private Map<String, Object> checkAppiumServer() {
        try {
            var client = java.net.http.HttpClient.newHttpClient();
            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://127.0.0.1:4723/wd/hub/status"))
                    .timeout(java.time.Duration.ofSeconds(3))
                    .build();
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return Map.of("available", true, "version", "running");
            }
        } catch (Exception e) {
            // Appium not running
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("available", false);
        r.put("version", "offline");
        r.put("installHint", "Install Appium: npm install -g appium, then start with 'appium' command");
        return r;
    }

    // === Dependency Installer ===

    /**
     * Install missing dependencies automatically.
     * Reads the request body for a list of tools to install, or installs all missing tools.
     */
    private void installDependencies(HttpExchange exchange) throws IOException {
        List<String> toolsToInstall = new ArrayList<>();
        try {
            byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
            if (body.length > 0 && body.length <= MAX_REQUEST_BYTES) {
                Object parsed = JsonParser.parse(new String(body, StandardCharsets.UTF_8));
                if (parsed instanceof Map<?, ?> values) {
                    Object tools = values.get("tools");
                    if (tools instanceof List<?> toolList) {
                        for (Object t : toolList) {
                            if (t instanceof String ts && !ts.isBlank()) {
                                toolsToInstall.add(ts);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // No body or invalid JSON — install all missing tools
        }

        Map<String, Object> deps = checkDependencies();
        Map<String, Object> results = new LinkedHashMap<>();
        List<String> logs = new ArrayList<>();

        // Determine what to install
        if (toolsToInstall.isEmpty()) {
            // Install all missing tools
            if (!Boolean.TRUE.equals((deps.get("maven") instanceof Map ? ((Map<?,?>)deps.get("maven")).get("available") : false))) {
                toolsToInstall.add("maven");
            }
            if (!Boolean.TRUE.equals((deps.get("npm") instanceof Map ? ((Map<?,?>)deps.get("npm")).get("available") : false))) {
                toolsToInstall.add("npm");
            }
            if (!Boolean.TRUE.equals((deps.get("node") instanceof Map ? ((Map<?,?>)deps.get("node")).get("available") : false))) {
                toolsToInstall.add("node");
            }
            if (!Boolean.TRUE.equals((deps.get("jmeter") instanceof Map ? ((Map<?,?>)deps.get("jmeter")).get("available") : false))) {
                toolsToInstall.add("jmeter");
            }
            if (!Boolean.TRUE.equals((deps.get("appium") instanceof Map ? ((Map<?,?>)deps.get("appium")).get("available") : false))) {
                toolsToInstall.add("appium");
            }
        }

        for (String tool : toolsToInstall) {
            String result = installTool(tool, logs);
            results.put(tool, result);
        }

        // Also run npm install for Newman if node/npm is available
        try {
            Map<String, Object> npmStatus = (Map<String, Object>) deps.get("npm");
            Map<String, Object> nodeStatus = (Map<String, Object>) deps.get("node");
            if (Boolean.TRUE.equals(npmStatus.get("available")) && Boolean.TRUE.equals(nodeStatus.get("available"))) {
                logs.add("[runner] Installing npm packages (Newman, WDIO, Cypress)...");
                ProcessBuilder pb = new ProcessBuilder("npm", "install");
                pb.directory(projectRoot.toFile());
                pb.redirectErrorStream(true);
                Process p = pb.start();
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        logs.add("[npm] " + line);
                    }
                }
                int exitCode = p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS) ? p.exitValue() : -1;
                if (exitCode == 0) {
                    results.put("npm-packages", "installed");
                    logs.add("[runner] ✅ npm packages installed successfully");
                } else {
                    results.put("npm-packages", "failed");
                    logs.add("[runner] ❌ npm install failed with exit code " + exitCode);
                }
            }
        } catch (Exception e) {
            results.put("npm-packages", "error: " + e.getMessage());
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("results", results);
        response.put("logs", logs);
        sendJson(exchange, 200, response);
    }

    /**
     * Install a single tool and return the result.
     */
    private String installTool(String tool, List<String> logs) {
        switch (tool) {
            case "maven":
                return installMaven(logs);
            case "node":
                return installNode(logs);
            case "npm":
                return "npm is bundled with Node.js — install Node.js first";
            case "jmeter":
                return installJMeter(logs);
            case "appium":
                return installAppium(logs);
            default:
                return "unknown tool: " + tool;
        }
    }

    /**
     * Download and install Apache Maven.
     */
    private String installMaven(List<String> logs) {
        Path mavenDir = projectRoot.resolve("apache-maven");
        Path mavenZip = projectRoot.resolve("temp").resolve("apache-maven.zip");
        String mavenVersion = "3.9.9";
        String url = "https://archive.apache.org/dist/maven/maven-3/" + mavenVersion + "/binaries/apache-maven-" + mavenVersion + "-bin.zip";

        try {
            logs.add("[runner] Downloading Maven " + mavenVersion + "...");
            Files.createDirectories(mavenDir);
            Files.createDirectories(projectRoot.resolve("temp"));

            // Download Maven
            downloadFile(url, mavenZip, logs);

            // Extract using Java's ZipFile
            logs.add("[runner] Extracting Maven...");
            extractZip(mavenZip, mavenDir);

            // Find the actual maven directory (apache-maven-X.Y.Z)
            Path extractedDir = null;
            try (var stream = Files.list(mavenDir)) {
                for (Path p : stream.filter(Files::isDirectory).toList()) {
                    if (p.getFileName().toString().startsWith("apache-maven-")) {
                        extractedDir = p;
                        break;
                    }
                }
            }

            if (extractedDir != null) {
                // Move contents to mavenDir
                Path mavenBin = extractedDir.resolve("bin").resolve("mvn.cmd");
                if (Files.exists(mavenBin)) {
                    logs.add("[runner] ✅ Maven installed at: " + mavenDir);
                    // Clean up
                    Files.deleteIfExists(mavenZip);
                    return "installed";
                }
            }

            // If extraction didn't work as expected, try alternative approach
            logs.add("[runner] Maven extracted to: " + mavenDir);
            Files.deleteIfExists(mavenZip);
            return "installed (check PATH)";

        } catch (Exception e) {
            logs.add("[runner] ❌ Maven install failed: " + e.getMessage());
            return "error: " + e.getMessage();
        }
    }

    /**
     * Download and install Node.js (Windows binary).
     */
    private String installNode(List<String> logs) {
        Path nodeDir = projectRoot.resolve("nodejs");
        Path nodeZip = projectRoot.resolve("temp").resolve("nodejs.zip");
        String url = "https://nodejs.org/dist/v20.11.0/node-v20.11.0-win-x64.zip";

        try {
            logs.add("[runner] Downloading Node.js v20.11.0...");
            Files.createDirectories(nodeDir);
            Files.createDirectories(projectRoot.resolve("temp"));

            downloadFile(url, nodeZip, logs);
            logs.add("[runner] Extracting Node.js...");
            extractZip(nodeZip, nodeDir);

            Path nodeExe = nodeDir.resolve("node.exe");
            if (Files.exists(nodeExe)) {
                logs.add("[runner] ✅ Node.js installed at: " + nodeDir);
                Files.deleteIfExists(nodeZip);
                return "installed";
            }

            logs.add("[runner] ❌ Node.js install failed — node.exe not found");
            Files.deleteIfExists(nodeZip);
            return "error: node.exe not found after extraction";

        } catch (Exception e) {
            logs.add("[runner] ❌ Node.js install failed: " + e.getMessage());
            return "error: " + e.getMessage();
        }
    }

    /**
     * Download and install Apache JMeter.
     */
    private String installJMeter(List<String> logs) {
        Path jmeterDir = projectRoot.resolve("apache-jmeter-5.6.3");
        if (Files.exists(jmeterDir.resolve("bin").resolve("jmeter.bat"))) {
            return "already installed";
        }

        Path jmeterZip = projectRoot.resolve("temp").resolve("jmeter.zip");
        String url = "https://archive.apache.org/dist/jmeter/binaries/apache-jmeter-5.6.3.zip";

        try {
            logs.add("[runner] Downloading Apache JMeter 5.6.3...");
            Files.createDirectories(projectRoot.resolve("temp"));

            downloadFile(url, jmeterZip, logs);
            logs.add("[runner] Extracting JMeter...");
            extractZip(jmeterZip, projectRoot);

            if (Files.exists(jmeterDir.resolve("bin").resolve("jmeter.bat"))) {
                logs.add("[runner] ✅ JMeter installed at: " + jmeterDir);
                Files.deleteIfExists(jmeterZip);
                return "installed";
            }

            logs.add("[runner] ❌ JMeter install failed");
            Files.deleteIfExists(jmeterZip);
            return "error: jmeter.bat not found";

        } catch (Exception e) {
            logs.add("[runner] ❌ JMeter install failed: " + e.getMessage());
            return "error: " + e.getMessage();
        }
    }

    /**
     * Install Appium via npm.
     */
    private String installAppium(List<String> logs) {
        try {
            // Check if npm is available
            ProcessBuilder pb = new ProcessBuilder("npm", "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished || p.exitValue() != 0) {
                return "npm not found — install Node.js first";
            }

            logs.add("[runner] Installing Appium via npm...");
            pb = new ProcessBuilder("npm", "install", "-g", "appium@latest");
            pb.redirectErrorStream(true);
            p = pb.start();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logs.add("[npm] " + line);
                }
            }
            boolean done = p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
            if (done && p.exitValue() == 0) {
                logs.add("[runner] ✅ Appium installed successfully");
                return "installed";
            }
            logs.add("[runner] ❌ Appium install failed");
            return "error: npm install failed";

        } catch (Exception e) {
            logs.add("[runner] ❌ Appium install failed: " + e.getMessage());
            return "error: " + e.getMessage();
        }
    }

    /**
     * Download a file from a URL to a local path.
     */
    private void downloadFile(String url, Path dest, List<String> logs) throws Exception {
        var client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofMinutes(5))
                .build();
        var request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .timeout(java.time.Duration.ofMinutes(10))
                .build();

        Files.createDirectories(dest.getParent());

        // Download with progress
        java.net.http.HttpResponse<java.nio.file.Path> response =
                client.send(request, java.net.http.HttpResponse.BodyHandlers.ofFile(dest));

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            long size = Files.size(dest);
            logs.add("[runner] Downloaded " + dest.getFileName() + " (" + formatSize(size) + ")");
        } else {
            Files.deleteIfExists(dest);
            throw new Exception("Download failed with status " + response.statusCode());
        }
    }

    /**
     * Extract a ZIP file to a target directory.
     */
    private void extractZip(Path zipFile, Path targetDir) throws Exception {
        Files.createDirectories(targetDir);
        try (var zipfs = java.nio.file.FileSystems.newFileSystem(zipFile, (java.lang.ClassLoader) null)) {
            for (var entry : zipfs.getRootDirectories()) {
                copyDirectory(entry, targetDir);
            }
        }
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (var stream = Files.list(source)) {
            for (Path entry : stream.toList()) {
                Path targetEntry = target.resolve(entry.getFileName().toString());
                if (Files.isDirectory(entry)) {
                    copyDirectory(entry, targetEntry);
                } else {
                    Files.copy(entry, targetEntry, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private Map<String, Object> frameworkDep(String id, String name, String icon,
                                              List<String> deps, String installHint,
                                              Map<String, Object> mavenStatus, Map<String, Object> nodeStatus,
                                              Map<String, Object> npmStatus, Map<String, Object> jmeterStatus,
                                              Map<String, Object> appiumStatus) {
        Map<String, Object> dep = new LinkedHashMap<>();
        dep.put("id", id);
        dep.put("name", name);
        dep.put("icon", icon);
        dep.put("installHint", installHint);
        List<Map<String, Object>> depStatus = new ArrayList<>();
        boolean allAvailable = true;
        for (String d : deps) {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("name", d);
            boolean available = false;
            if (d.equals("maven")) {
                available = Boolean.TRUE.equals(mavenStatus.get("available"));
            } else if (d.equals("node")) {
                available = Boolean.TRUE.equals(nodeStatus.get("available"));
            } else if (d.equals("npm")) {
                available = Boolean.TRUE.equals(npmStatus.get("available"));
            } else if (d.equals("jmeter")) {
                available = Boolean.TRUE.equals(jmeterStatus.get("available"));
            } else if (d.equals("appium")) {
                available = Boolean.TRUE.equals(appiumStatus.get("available"));
            }
            status.put("available", available);
            if (!available) allAvailable = false;
            depStatus.add(status);
        }
        dep.put("dependencies", depStatus);
        dep.put("ready", allAvailable);
        return dep;
    }

    // ==================== CI/CD Integration ====================

    private final CiCdConfig ciCdConfig;
    private final WorkflowGenerator workflowGenerator = new WorkflowGenerator();
    private final PipelineStatusService pipelineStatusService = new PipelineStatusService();

    /**
     * GET /api/cicd/config — return current CI/CD configuration (without token).
     */
    private Map<String, Object> getCiCdConfig(String projectId) {
        CiCdConfig.CiCdSettings settings = ciCdConfig.load(projectId);
        if (settings == null) {
            return Map.of("configured", false);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", true);
        result.put("platform", settings.platform());
        result.put("repoUrl", settings.repoUrl());
        result.put("branch", settings.branch());
        result.put("frameworks", settings.frameworks());
        result.put("triggerType", settings.triggerType());
        result.put("scheduleCron", settings.scheduleCron());
        result.put("jenkinsUrl", settings.jenkinsUrl());
        result.put("hasToken", !settings.token().isEmpty());
        return result;
    }

    /**
     * POST /api/cicd/config — save CI/CD configuration.
     */
    private void saveCiCdConfig(HttpExchange exchange) throws IOException {
        try {
            byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
            if (body.length > MAX_REQUEST_BYTES) {
                throw new IllegalArgumentException("Request body too large.");
            }
            Object parsed = JsonParser.parse(new String(body, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> values)) {
                throw new IllegalArgumentException("Request must be a JSON object.");
            }
            String platform = stringField(values, "platform");
            String projectId = stringField(values, "projectId").trim();
            if (!projectId.equals(queryValue(exchange, "projectId"))) {
                throw new AuthorizationException(403, "The CI/CD project scope does not match the authorized request.");
            }
            String organizationId = stringField(values, "organizationId").trim();
            if (!organizationId.equals(queryValue(exchange, "organizationId"))) {
                throw new AuthorizationException(403, "The CI/CD organization scope does not match the authorized request.");
            }
            String repoUrl = CiCdConfig.normalizeRepoUrl(stringField(values, "repoUrl"));
            String token = stringField(values, "token");
            String branch = stringField(values, "branch");
            if (branch.isEmpty()) branch = "main";
            String triggerType = stringField(values, "triggerType");
            if (triggerType.isEmpty()) triggerType = "push";
            String scheduleCron = "";
            Object scheduleCronObj = values.get("scheduleCron");
            if (scheduleCronObj instanceof String scheduleCronText) scheduleCron = scheduleCronText;
            String jenkinsUrl = "";
            Object jenkinsUrlObj = values.get("jenkinsUrl");
            if (jenkinsUrlObj instanceof String jenkinsUrlText) jenkinsUrl = jenkinsUrlText;

            List<String> frameworks = new ArrayList<>();
            Object fwObj = values.get("frameworks");
            if (fwObj instanceof List<?> fwList) {
                for (Object fw : fwList) {
                    if (fw instanceof String s) frameworks.add(s);
                }
            }

            CiCdConfig.CiCdSettings settings = new CiCdConfig.CiCdSettings(
                    platform, repoUrl, token, branch, frameworks, triggerType, scheduleCron, jenkinsUrl);
            ciCdConfig.save(settings, projectId);
            sendJson(exchange, 200, Map.of("message", "CI/CD configuration saved."));
        } catch (Exception e) {
            sendJson(exchange, 400, Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /api/cicd/test-connection — test repo access with provided token.
     */
    private void testCiCdConnection(HttpExchange exchange) throws IOException {
        try {
            byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
            if (body.length > MAX_REQUEST_BYTES) {
                throw new IllegalArgumentException("Request body too large.");
            }
            Object parsed = JsonParser.parse(new String(body, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> values)) {
                throw new IllegalArgumentException("Request must be a JSON object.");
            }
            String platform = stringField(values, "platform");
            String repoUrl = CiCdConfig.normalizeRepoUrl(stringField(values, "repoUrl"));
            String token = stringField(values, "token");
            String jenkinsUrl = "";
            Object jenkinsUrlObj = values.get("jenkinsUrl");
            if (jenkinsUrlObj instanceof String jenkinsUrlText) jenkinsUrl = jenkinsUrlText;

            CiCdConfig.CiCdSettings settings = new CiCdConfig.CiCdSettings(
                    platform, repoUrl, token, "main", List.of(), "push", "", jenkinsUrl);
            String result = ciCdConfig.testConnection(settings);
            boolean success = result.toLowerCase().contains("connected") || result.toLowerCase().contains("success");
            sendJson(exchange, 200, Map.of("success", success, "message", result));
        } catch (Exception e) {
            sendJson(exchange, 400, Map.of("success", false, "message", e.getMessage()));
        }
    }

    /**
     * GET /api/cicd/generate — generate workflow file based on current config.
     */
    private void generateWorkflow(HttpExchange exchange, String projectId) throws IOException {
        try {
            CiCdConfig.CiCdSettings settings = ciCdConfig.load(projectId);
            if (settings == null) {
                sendError(exchange, 400, "CI/CD not configured. Save configuration first.");
                return;
            }
            String platform = settings.platform();
            String content;
            String filename;
            String contentType;
            if ("github".equals(platform)) {
                content = workflowGenerator.generateGitHubActions(settings);
                filename = "qa-runner.yml";
                contentType = "text/yaml";
            } else if ("jenkins".equals(platform)) {
                content = workflowGenerator.generateJenkinsfile(settings);
                filename = "Jenkinsfile";
                contentType = "text/x-groovy";
            } else {
                sendError(exchange, 400, "Unknown platform: " + platform);
                return;
            }
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=" + filename);
            exchange.sendResponseHeaders(200, bytes.length);
            try (java.io.OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (Exception e) {
            sendError(exchange, 500, "Failed to generate workflow: " + e.getMessage());
        }
    }

    /**
     * GET /api/cicd/status — return recent pipeline runs.
     */
    private Map<String, Object> getCiCdStatus(String projectId) {
        CiCdConfig.CiCdSettings settings = ciCdConfig.load(projectId);
        return pipelineStatusService.getLatestStatus(settings, projectId);
    }

    /**
     * POST /api/cicd/commit — commit generated workflow file to the repo.
     */
    private void commitWorkflow(HttpExchange exchange, String projectId) throws IOException {
        try {
            System.out.println("[COMMIT] Starting commit workflow...");
            CiCdConfig.CiCdSettings settings = ciCdConfig.load(projectId);
            if (settings == null || settings.token().isEmpty()) {
                System.out.println("[COMMIT] No config or token found.");
                sendJson(exchange, 400, Map.of("success", false, "message", "CI/CD not configured or token missing."));
                return;
            }
            System.out.println("[COMMIT] Credential loaded for the authorized CI/CD project.");
            String platform = settings.platform();
            String content;
            String path;
            if ("github".equals(platform)) {
                content = workflowGenerator.generateGitHubActions(settings);
                path = ".github/workflows/qa-runner.yml";
            } else if ("jenkins".equals(platform)) {
                content = workflowGenerator.generateJenkinsfile(settings);
                path = "Jenkinsfile";
            } else {
                sendJson(exchange, 400, Map.of("success", false, "message", "Unknown platform."));
                return;
            }
            String[] parts = parseRepoUrl(settings.repoUrl());
            String owner = parts[0];
            String repo = parts[1];
            if (owner == null || repo == null) {
                sendJson(exchange, 400, Map.of("success", false, "message", "Invalid repo URL."));
                return;
            }
            System.out.println("[COMMIT] Detecting default branch for " + owner + "/" + repo + "...");
            // Detect the actual default branch from the repo
            String branch = detectDefaultBranch(owner, repo, settings.token(), settings.branch());
            System.out.println("[COMMIT] Detected branch: " + branch);
            // Try git command line first - it works better with fine-grained tokens
            boolean gitSuccess = commitViaGit(owner, repo, branch, path, content, settings.token());
            if (gitSuccess) {
                System.out.println("[COMMIT] Git commit succeeded!");
                sendJson(exchange, 200, Map.of("success", true, "message", "Workflow file committed to repo via git."));
                return;
            }
            System.out.println("[COMMIT] Git commit failed, falling back to GitHub API...");
            // Fallback to GitHub API if git is not available
            // Base64 encode the content (required by GitHub Contents API)
            String base64Content = java.util.Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
            // Try to get the latest commit SHA for the file
            System.out.println("[COMMIT] Getting latest commit SHA...");
            String sha = getLatestCommitSha(owner, repo, path, settings.token(), branch);
            System.out.println("[COMMIT] SHA: " + sha);
            String apiUrl = "https://api.github.com/repos/" + owner + "/" + repo + "/contents/" + path;
            System.out.println("[COMMIT] Calling GitHub API: " + apiUrl);
            StringBuilder body = new StringBuilder();
            body.append("{\"message\":\"Add QA Runner CI/CD workflow\",\"content\":\"");
            body.append(base64Content);
            body.append("\",\"branch\":\"").append(branch).append("\"");
            if (sha != null) {
                body.append(",\"sha\":\"").append(sha).append("\"");
            }
            body.append("}");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) java.net.URI.create(apiUrl).toURL().openConnection();
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "token " + settings.token());
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            System.out.println("[COMMIT] Sending request to GitHub API...");
            int code = conn.getResponseCode();
            System.out.println("[COMMIT] GitHub API response code: " + code);
            if (code == 200 || code == 201) {
                System.out.println("[COMMIT] Success! Workflow committed.");
                sendJson(exchange, 200, Map.of("success", true, "message", "Workflow file committed to repo."));
            } else {
                String error = readErrorBody(conn);
                System.out.println("[COMMIT] Error response: " + error);
                // Add helpful hint for workflow scope issues, but always show the real error
                String message = "Failed to commit: " + error;
                if ((code == 403 || code == 404) && path != null && path.startsWith(".github/workflows/")) {
                    message += " (Note: For classic tokens, ensure both 'repo' AND 'workflow' scopes are checked. For fine-grained tokens, ensure both 'Contents' AND 'Actions' permissions are set to 'Read & write'.)";
                }
                sendJson(exchange, code, Map.of("success", false, "message", message));
            }
        } catch (Exception e) {
            System.out.println("[COMMIT] Exception: " + e.getClass().getName() + " - " + e.getMessage());
            e.printStackTrace();
            sendJson(exchange, 500, Map.of("success", false, "message", "Commit failed: " + e.getMessage()));
        }
    }

    /**
     * Commit workflow file using git command line - works better with fine-grained tokens.
     */
    private boolean commitViaGit(String owner, String repo, String branch, String path, String content, String token) {
        try {
            String tempDir = System.getProperty("java.io.tmpdir") + "/qa-runner-git-" + System.currentTimeMillis();
            java.nio.file.Path dir = java.nio.file.Paths.get(tempDir);
            java.nio.file.Files.createDirectories(dir);
            System.out.println("[COMMIT] Cloning repo to: " + tempDir);
            // Clone the repo
            runGitCommand(tempDir, "init");
            runGitCommand(tempDir, "remote", "add", "origin", "https://" + token + "@github.com/" + owner + "/" + repo + ".git");
            runGitCommand(tempDir, "fetch", "origin", branch);
            runGitCommand(tempDir, "checkout", "-b", branch, "origin/" + branch);
            // Create the file
            java.nio.file.Path filePath = dir.resolve(path);
            java.nio.file.Files.createDirectories(filePath.getParent());
            java.nio.file.Files.writeString(filePath, content);
            // Commit and push
            runGitCommand(tempDir, "add", path);
            runGitCommand(tempDir, "config", "user.email", "qa-runner@localhost");
            runGitCommand(tempDir, "config", "user.name", "QA Runner");
            runGitCommand(tempDir, "commit", "-m", "Add QA Runner CI/CD workflow");
            String pushResult = runGitCommand(tempDir, "push", "origin", branch);
            System.out.println("[COMMIT] Push result: " + pushResult);
            // Cleanup
            deleteDirectory(dir.toFile());
            return pushResult.contains("To") || pushResult.contains("branch");
        } catch (Exception e) {
            System.out.println("[COMMIT] Git commit failed: " + e.getMessage());
            return false;
        }
    }

    private String runGitCommand(String dir, String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new java.io.File(dir));
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        int exitCode = p.waitFor();
        if (exitCode != 0) {
            System.out.println("[GIT] Command failed: " + String.join(" ", cmd) + " - " + output.trim());
            throw new RuntimeException("Git command failed: " + output.trim());
        }
        return output.trim();
    }

    private void deleteDirectory(java.io.File dir) {
        java.io.File[] files = dir.listFiles();
        if (files != null) {
            for (java.io.File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        dir.delete();
    }

    /**
     * Create a directory in the repository if it doesn't exist by creating a .gitkeep file.
     */
    private void createDirectoryIfNotExists(String owner, String repo, String dirPath, String token, String branch) {
        try {
            String url = "https://api.github.com/repos/" + owner + "/" + repo + "/contents/" + dirPath + "/.gitkeep";
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
            conn.setRequestProperty("Authorization", "token " + token);
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            int code = conn.getResponseCode();
            if (code == 404) {
                // Directory doesn't exist, create it with .gitkeep
                System.out.println("[COMMIT] Creating directory: " + dirPath);
                String gitkeepContent = java.util.Base64.getEncoder().encodeToString("Keep this directory".getBytes(StandardCharsets.UTF_8));
                String body = "{\"message\":\"Create directory " + dirPath + "\",\"content\":\"" + gitkeepContent + "\",\"branch\":\"" + branch + "\"}";
                conn = (java.net.HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
                conn.setDoOutput(true);
                conn.setRequestProperty("Authorization", "token " + token);
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                try (java.io.OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
                int createCode = conn.getResponseCode();
                System.out.println("[COMMIT] Create directory response: " + createCode);
            } else if (code == 200) {
                System.out.println("[COMMIT] Directory already exists: " + dirPath);
            }
        } catch (Exception e) {
            System.out.println("[COMMIT] Failed to create directory " + dirPath + ": " + e.getMessage());
        }
    }

    /**
     * Detect the default branch of the repository. Falls back to the configured branch if detection fails.
     */
    private String detectDefaultBranch(String owner, String repo, String token, String configuredBranch) {
        try {
            String url = "https://api.github.com/repos/" + owner + "/" + repo;
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
            conn.setRequestProperty("Authorization", "token " + token);
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            int code = conn.getResponseCode();
            if (code == 200) {
                String response = readResponseBody(conn);
                Object parsed = JsonParser.parse(response);
                if (parsed instanceof Map<?, ?> map) {
                    String defaultBranch = map.get("default_branch").toString();
                    if (defaultBranch != null && !defaultBranch.isEmpty()) {
                        return defaultBranch;
                    }
                }
            }
        } catch (Exception e) {
            // Fall back to configured branch
        }
        return configuredBranch;
    }

    private String getLatestCommitSha(String owner, String repo, String path, String token, String branch) {
        try {
            String url = "https://api.github.com/repos/" + owner + "/" + repo + "/contents/" + path
                    + "?ref=" + branch;
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
            conn.setRequestProperty("Authorization", "token " + token);
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            int code = conn.getResponseCode();
            if (code == 200) {
                String response = readResponseBody(conn);
                Object parsed = JsonParser.parse(response);
                if (parsed instanceof Map<?, ?> map) {
                    return map.get("sha").toString();
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String[] parseRepoUrl(String repoUrl) {
        if (repoUrl == null || repoUrl.isEmpty()) return new String[]{null, null};
        String clean = repoUrl.replace("https://github.com/", "")
                .replace("git@github.com:", "").replace(".git", "").trim();
        int slash = clean.lastIndexOf('/');
        if (slash > 0) return new String[]{clean.substring(0, slash), clean.substring(slash + 1)};
        return new String[]{null, clean};
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String readResponseBody(java.net.HttpURLConnection conn) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private String readErrorBody(java.net.HttpURLConnection conn) {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (Exception e) {
            return "Unknown error";
        }
    }

    // === Test Case Management Helpers ===

    private Map<String, Object> testCaseToMap(TestCaseManager.TestCase tc) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", tc.id());
        map.put("caseId", tc.caseId());
        map.put("name", tc.name());
        map.put("description", tc.description());
        map.put("priority", tc.priority());
        map.put("tags", tc.tags());
        map.put("framework", tc.framework());
        map.put("status", tc.status());
        map.put("lastRunId", tc.lastRunId());
        map.put("lastRunTimestamp", tc.lastRunTimestamp());
        map.put("suiteId", tc.suiteId());
        map.put("storyId", tc.storyId());
        map.put("steps", tc.steps());
        map.put("module", tc.module());
        map.put("testType", tc.testType());
        map.put("testMethod", tc.testMethod());
        map.put("assignedTo", tc.assignedTo());
        map.put("automationStatus", tc.automationStatus());
        map.put("archived", testCaseManager.isArchived(tc.id()));
        return map;
    }

    private Map<String, Object> storyToMap(TestCaseManager.Story story) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", story.id());
        map.put("storyCode", story.storyCode());
        map.put("title", story.title());
        map.put("description", story.description());
        map.put("status", story.status());
        map.put("priority", story.priority());
        map.put("assignee", story.assignee());
        map.put("storyPoints", story.storyPoints());
        return map;
    }

    private Map<String, Object> suiteToMap(TestCaseManager.TestSuite suite) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", suite.id());
        map.put("suiteCode", suite.suiteCode());
        map.put("name", suite.name());
        map.put("parentId", suite.parentId());
        map.put("sortOrder", suite.sortOrder());
        map.put("testCaseCount", testCaseManager.countTestCasesInSuite(suite.id()));
        return map;
    }

    /**
     * POST /api/testcases — create a new test case.
     */
    @SuppressWarnings("unchecked")
    private void createTestCase(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("Request body is too large.");
        }
        Object parsed = JsonParser.parse(new String(body, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Request body must be a JSON object.");
        }
        String name = stringField(values, "name");
        String requestedProjectId = optionalString(values, "projectId");
        String description = optionalString(values, "description");
        String priority = optionalString(values, "priority");
        String framework = optionalString(values, "framework");

        List<String> tags = new ArrayList<>();
        Object tagsObj = values.get("tags");
        if (tagsObj instanceof List<?> tagList) {
            for (Object tag : tagList) {
                if (tag instanceof String t) tags.add(t);
            }
        }

        String suiteId = optionalString(values, "suiteId");
        String storyId = optionalString(values, "storyId");
        String storyProject = projectIdForStory(storyId);
        String projectId = requestedProjectId.isBlank() ? storyProject : requestedProjectId;
        requireProjectAccess(exchange, projectId, TeamAuthorization.TEST_CASES, true);
        if (!storyProject.isBlank() && !projectId.equals(storyProject)) {
            throw new AuthorizationException(403, "The linked User Story belongs to another project.");
        }
        // Parse steps if present
        List<Map<String, Object>> steps = new ArrayList<>();
        Object stepsObj = values.get("steps");
        if (stepsObj instanceof List<?> stepList) {
            for (Object step : stepList) {
                if (step instanceof Map<?, ?> s) {
                    Map<String, Object> stepMap = new LinkedHashMap<>();
                    stepMap.put("action", s.get("action") != null ? s.get("action").toString() : "");
                    stepMap.put("expected", s.get("expected") != null ? s.get("expected").toString() : "");
                    steps.add(stepMap);
                }
            }
        }
        String module = optionalString(values, "module");
        String testType = optionalString(values, "testType");
        String testMethod = optionalString(values, "testMethod");
        String assignedTo = optionalString(values, "assignedTo");
        String automationStatus = optionalString(values, "automationStatus");
        TestCaseManager.TestCase created = testCaseManager.create(name, description, priority, tags, framework, suiteId, storyId, steps, module, testType, testMethod, assignedTo, automationStatus);
        projectOwnershipManager.assign("TEST_CASE", created.id(), projectId, "TEST_CASE_CREATE", validProjectIds());
        sendJson(exchange, 201, testCaseToMap(created));
    }

    /**
     * PUT /api/testcases/{id} — update an existing test case.
     */
    @SuppressWarnings("unchecked")
    private void updateTestCase(HttpExchange exchange, String id) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("Request body is too large.");
        }
        Object parsed = JsonParser.parse(new String(body, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Request body must be a JSON object.");
        }
        String ownedProject = projectOwnershipManager.projectId("TEST_CASE", id);
        if (ownedProject.isBlank()) throw new AuthorizationException(403, "This test case has no resolved project ownership.");
        String requestedStory = optionalString(values, "storyId");
        String storyProject = projectIdForStory(requestedStory);
        if (!storyProject.isBlank() && !ownedProject.equals(storyProject)) {
            throw new AuthorizationException(403, "The linked User Story belongs to another project.");
        }
        String name = optionalString(values, "name");
        String description = optionalString(values, "description");
        String priority = optionalString(values, "priority");
        String framework = optionalString(values, "framework");

        List<String> tags = new ArrayList<>();
        Object tagsObj = values.get("tags");
        if (tagsObj instanceof List<?> tagList) {
            for (Object tag : tagList) {
                if (tag instanceof String t) tags.add(t);
            }
        }

        String suiteId = optionalString(values, "suiteId");
        String storyId = optionalString(values, "storyId");
        // Parse steps if present
        List<Map<String, Object>> steps = new ArrayList<>();
        Object stepsObj = values.get("steps");
        if (stepsObj instanceof List<?> stepList) {
            for (Object step : stepList) {
                if (step instanceof Map<?, ?> s) {
                    Map<String, Object> stepMap = new LinkedHashMap<>();
                    stepMap.put("action", s.get("action") != null ? s.get("action").toString() : "");
                    stepMap.put("expected", s.get("expected") != null ? s.get("expected").toString() : "");
                    steps.add(stepMap);
                }
            }
        }
        String module = optionalString(values, "module");
        String testType = optionalString(values, "testType");
        String testMethod = optionalString(values, "testMethod");
        String assignedTo = optionalString(values, "assignedTo");
        String automationStatus = optionalString(values, "automationStatus");
        TestCaseManager.TestCase before = testCaseManager.getById(id);
        try {
            TestCaseManager.TestCase updated = testCaseManager.update(id, name, description, priority, tags, framework, suiteId, storyId, steps, module, testType, testMethod, assignedTo, automationStatus);
            testCaseManager.recordHistory(id, testCaseToMap(before), testCaseToMap(updated), "Local User");
            sendJson(exchange, 200, testCaseToMap(updated));
        } catch (IllegalArgumentException e) {
            sendError(exchange, 404, e.getMessage());
        }
    }

    /**
     * POST /api/testcases/sync — sync test case statuses from a pipeline or local run.
     * Accepts: {runId} for local runs, or {runId, source:"github"} for GitHub pipeline runs.
     */
    @SuppressWarnings("unchecked")
    private void syncTestCases(HttpExchange exchange, String projectId) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("Request body is too large.");
        }
        Object parsed = JsonParser.parse(new String(body, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Request body must be a JSON object.");
        }
        String runId = optionalString(values, "runId");
        String source = optionalString(values, "source");

        if ("github".equals(source) && runId != null) {
            // Sync from GitHub pipeline run
            CiCdConfig.CiCdSettings settings = ciCdConfig.load(projectId);
            if (settings == null || settings.token().isEmpty()) {
                sendError(exchange, 400, "CI/CD config not found. Save your CI/CD configuration first.");
                return;
            }
            String[] parts = parseRepoUrl(settings.repoUrl());
            List<RunDetail> jobs = pipelineStatusService.getRunJobs(parts[0], parts[1], runId, settings.token());
            if (jobs.isEmpty()) {
                sendError(exchange, 404, "No job details found for run " + runId);
                return;
            }
            testCaseManager.syncFromRun(runId, jobs);
            sendJson(exchange, 200, Map.of("message", "Test cases synced from GitHub run " + runId, "matched", jobs.size()));
        } else if (runId != null) {
            // Sync from local run
            RunEntry entry = runs.get(runId);
            if (entry != null && entry.jobContext != null
                    && projectId.equals(entry.jobContext.projectId())
                    && entry.result != null && entry.result.summary() != null) {
                testCaseManager.syncFromRun(runId, entry.result.summary().details());
                sendJson(exchange, 200, Map.of("message", "Test cases synced from run " + runId));
            } else {
                sendError(exchange, 404, "Run not found: " + runId);
            }
        } else {
            // Auto-sync: sync from latest GitHub pipeline run
            CiCdConfig.CiCdSettings settings = ciCdConfig.load(projectId);
            if (settings == null || settings.token().isEmpty()) {
                sendError(exchange, 400, "CI/CD config not found. Save your CI/CD configuration first.");
                return;
            }
            List<CiCdConfig.PipelineRun> pipelineRuns = pipelineStatusService.getRuns(settings, projectId);
            if (pipelineRuns.isEmpty()) {
                sendError(exchange, 404, "No pipeline runs found");
                return;
            }
            CiCdConfig.PipelineRun latest = pipelineRuns.get(0);
            String[] parts = parseRepoUrl(settings.repoUrl());
            List<RunDetail> jobs = pipelineStatusService.getRunJobs(parts[0], parts[1], latest.id(), settings.token());
            if (jobs.isEmpty()) {
                sendError(exchange, 404, "No job details found for run " + latest.id());
                return;
            }
            testCaseManager.syncFromRun(latest.id(), jobs);
            sendJson(exchange, 200, Map.of("message", "Test cases synced from latest pipeline run", "runId", latest.id()));
        }
    }

    /**
     * POST /api/testcases/bulk-delete — delete multiple test cases by ID list.
     */
    @SuppressWarnings("unchecked")
    private void bulkDeleteTestCases(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("Request body is too large.");
        }
        Object parsed = JsonParser.parse(new String(body, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Request body must be a JSON object.");
        }
        Object idsObj = values.get("ids");
        if (!(idsObj instanceof List<?> idsList)) {
            sendError(exchange, 400, "Request must contain an 'ids' array.");
            return;
        }
        List<String> ids = new ArrayList<>();
        for (Object id : idsList) {
            if (id instanceof String s && !s.isBlank()) {
                ids.add(s);
            }
        }
        if (ids.isEmpty()) {
            sendError(exchange, 400, "No valid IDs provided.");
            return;
        }
        int deleted = testCaseManager.bulkDelete(ids);
        sendJson(exchange, 200, Map.of("message", deleted + " test case(s) deleted.", "deleted", deleted));
    }

    @SuppressWarnings("unchecked")
    private static String optionalString(Map<?, ?> values, String name) {
        Object value = values.get(name);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        return null;
    }

    private static String configuredUser(String environmentName, String propertyName, String fallback) {
        String property = System.getProperty(propertyName);
        if (property != null && !property.isBlank()) return property.trim();
        String environment = System.getenv(environmentName);
        return environment == null || environment.isBlank() ? fallback : environment.trim();
    }

    private Map<String, Object> projectToMap(UserStoryStore.Project project) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", project.id()); map.put("key", project.key()); map.put("name", project.name());
        map.put("description", project.description()); map.put("owner", project.owner()); map.put("archived", project.archived());
        map.put("createdAt", project.createdAt()); map.put("updatedAt", project.updatedAt());
        return map;
    }

    private Map<String, Object> listTeamMembers(HttpExchange exchange, TenantContext tenant) {
        String search = queryValueOrEmpty(exchange, "search").trim().toLowerCase(Locale.ROOT);
        String status = queryValueOrEmpty(exchange, "status").trim().toUpperCase(Locale.ROOT);
        String role = queryValueOrEmpty(exchange, "role").trim();
        String projectId = queryValueOrEmpty(exchange, "projectId").trim();
        String sort = queryValueOrEmpty(exchange, "sort").trim().toLowerCase(Locale.ROOT);
        boolean descending = "desc".equalsIgnoreCase(queryValueOrEmpty(exchange, "direction"));
        List<TeamMemberManager.Member> allMembers = teamMemberManager.list().stream()
                .collect(java.util.stream.Collectors.toMap(TeamMemberManager.Member::id, member -> member,
                        (first, ignored) -> first, LinkedHashMap::new)).values().stream().toList();
        TeamAuthorization authorization = teamAuthorization(exchange);
        TeamMemberManager.Member actor = authorization.currentMember(allMembers);
        boolean admin = tenant == null && isGlobalAdmin(exchange);
        final Set<String> actorProjects = tenant == null
                ? (actor == null ? Set.of() : Set.copyOf(actor.projectIds()))
                : Set.of(tenant.projectId());
        if (!admin && !projectId.isBlank() && !authorization.canAccessProject(projectId, allMembers)) {
            throw new AuthorizationException(403, "You are not authorized to view this project's members.");
        }
        List<TeamMemberManager.Member> visibleMembers = tenant != null ? allMembers.stream()
                .filter(member -> member.projectIds().contains(tenant.projectId()))
                .toList() : admin ? allMembers : allMembers.stream()
                .filter(member -> member.projectIds().stream().anyMatch(actorProjects::contains))
                .toList();
        List<TeamMemberManager.Member> filtered = visibleMembers.stream()
                .filter(member -> search.isBlank() || (member.name() + " " + member.email() + " " + member.username()).toLowerCase(Locale.ROOT).contains(search))
                .filter(member -> status.isBlank() || member.status().equals(status))
                .filter(member -> role.isBlank() || member.role().equals(role))
                .filter(member -> projectId.isBlank() || member.projectIds().contains(projectId))
                .sorted((left, right) -> {
                    int result = memberSortValue(left, sort).compareToIgnoreCase(memberSortValue(right, sort));
                    return descending ? -result : result;
                }).toList();
        int total = filtered.size();
        int offset = Math.max(0, queryInt(exchange, "offset", 0));
        int limit = Math.min(50, Math.max(1, queryInt(exchange, "limit", 10)));
        List<Map<String, Object>> items = filtered.stream().skip(Math.min(offset, total)).limit(limit).map(this::teamMemberToMap).toList();
        List<Map<String, Object>> projectCounts = new ArrayList<>();
        try {
            for (UserStoryStore.Project project : userStoryStore.listProjects(true)) {
                if (tenant != null && !tenant.projectId().equals(project.id())) continue;
                if (tenant == null && !admin && !actorProjects.contains(project.id())) continue;
                if (tenant != null && !tenantProjectBelongsToOrganization(project.id(), tenant.organizationId())) continue;
                long count = visibleMembers.stream().filter(member -> member.projectIds().contains(project.id())).count();
                Map<String, Object> projectData = new LinkedHashMap<>();
                projectData.put("id", project.id()); projectData.put("key", project.key());
                projectData.put("name", project.name()); projectData.put("count", count);
                projectCounts.add(projectData);
            }
        } catch (IOException error) {
            throw new IllegalStateException("Project membership counts could not be loaded.", error);
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", visibleMembers.size());
        stats.put("active", visibleMembers.stream().filter(member -> TeamMemberManager.ACTIVE.equals(member.status())).count());
        stats.put("roles", visibleMembers.stream().map(TeamMemberManager.Member::role).distinct().count());
        stats.put("assigned", visibleMembers.stream().filter(member -> !member.projectIds().isEmpty()).count());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("items", items); response.put("total", total); response.put("offset", offset); response.put("limit", limit);
        response.put("stats", stats); response.put("projectCounts", projectCounts);
        return response;
    }

    private String memberSortValue(TeamMemberManager.Member member, String sort) {
        return switch (sort) {
            case "email" -> member.email();
            case "username" -> member.username();
            case "role" -> member.role();
            case "status" -> member.status();
            case "updatedat" -> member.updatedAt();
            default -> member.name();
        };
    }

    private Set<String> validProjectIds() throws IOException {
        return userStoryStore.listProjects(true).stream().map(UserStoryStore.Project::id).collect(java.util.stream.Collectors.toSet());
    }

    private void validateRegisteredUser(HttpExchange exchange, Map<String, Object> values) {
        Object value = values.get("registeredUserId");
        if (value == null || String.valueOf(value).isBlank()) return;
        AuthUserDirectory.User registered = authUserDirectory.findById(String.valueOf(value));
        if (registered == null || !AuthUserDirectory.ACTIVE.equals(registered.status())
                || (!registered.emailVerified() && !registered.mobileVerified())) {
            throw new IllegalArgumentException("The selected registered user is not active and verified.");
        }
        if (!teamAuthorization(exchange).authenticated()) {
            throw new AuthorizationException(401, "An authenticated identity is required to link a registered user.");
        }
    }

    private Map<String, Object> normalizeMemberRole(Map<String, Object> values) {
        if (!values.containsKey("role") && !values.containsKey("roleId")) return values;
        String requestedId = values.get("roleId") == null ? "" : String.valueOf(values.get("roleId"));
        String requestedName = values.get("role") == null ? "" : String.valueOf(values.get("role"));
        TeamRoleManager.Role role = teamRoleManager.list().stream()
                .filter(item -> item.active() && ((!requestedId.isBlank() && item.id().equals(requestedId))
                        || (!requestedName.isBlank() && item.name().equals(requestedName))))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown or inactive team role."));
        Map<String, Object> normalized = new LinkedHashMap<>(values);
        normalized.put("role", role.name()); normalized.put("roleId", role.id());
        return normalized;
    }

    private Map<String, Object> teamMemberToMap(TeamMemberManager.Member member) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", member.id()); map.put("name", member.name()); map.put("email", member.email());
        map.put("username", member.username()); map.put("status", member.status()); map.put("role", member.role());
        map.put("roleId", member.roleId());
        map.put("registeredUserId", member.registeredUserId()); map.put("projectIds", member.projectIds());
        map.put("projectRoles", member.projectRoles());
        map.put("createdAt", member.createdAt()); map.put("updatedAt", member.updatedAt());
        return map;
    }

    private Map<String, Object> teamRoleToMap(TeamRoleManager.Role role, List<TeamMemberManager.Member> members) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", role.id()); map.put("name", role.name()); map.put("description", role.description());
        map.put("builtIn", role.builtIn()); map.put("active", role.active()); map.put("permissions", role.permissions());
        map.put("memberCount", teamRoleManager.memberCount(role, members));
        map.put("createdAt", role.createdAt()); map.put("updatedAt", role.updatedAt());
        return map;
    }

    private Map<String, Object> epicToMap(UserStoryStore.Epic epic) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", epic.id()); map.put("projectId", epic.projectId()); map.put("key", epic.key()); map.put("name", epic.name());
        map.put("description", epic.description()); map.put("status", epic.status()); map.put("priority", epic.priority());
        map.put("archived", epic.archived()); map.put("createdAt", epic.createdAt()); map.put("updatedAt", epic.updatedAt());
        return map;
    }

    private Map<String, Object> userStoryToMap(UserStoryStore.Story story) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", story.id()); map.put("projectId", story.projectId()); map.put("epicId", story.epicId());
        map.put("title", story.title()); map.put("description", story.description()); map.put("asA", story.asA());
        map.put("iWant", story.iWant()); map.put("soThat", story.soThat()); map.put("acceptanceCriteria", story.acceptanceCriteria());
        map.put("priority", story.priority()); map.put("status", story.status()); map.put("storyPoints", story.storyPoints());
        map.put("businessValue", story.businessValue()); map.put("risk", story.risk());
        UserStoryStore.Creator creator;
        try { creator = userStoryStore.creatorFor(story.id()); } catch (IOException e) { creator = new UserStoryStore.Creator("legacy:unknown", "Unknown"); }
        map.put("assignee", story.assignee()); map.put("createdById", creator.userId()); map.put("createdBy", creator.displayName()); map.put("sprintId", story.sprintId()); map.put("archived", story.archived());
        map.put("createdAt", story.createdAt()); map.put("updatedAt", story.updatedAt()); map.put("changedById", story.changedById()); map.put("changedBy", story.changedBy()); map.put("changedAt", story.changedAt());
        return map;
    }

    private Map<String, Object> attachmentToMap(UserStoryStore.Attachment attachment) {
        return new LinkedHashMap<>(Map.of("id", attachment.id(), "storyId", attachment.storyId(), "filename", attachment.originalName(),
                "size", attachment.size(), "contentType", attachment.contentType(), "uploaderId", attachment.uploaderId(),
                "uploader", attachment.uploaderName(), "createdAt", attachment.createdAt(),
                "downloadUrl", "/api/user-stories/attachments/" + attachment.id(),
                "previewable", attachment.contentType().startsWith("image/") || "application/pdf".equals(attachment.contentType())));
    }

    private Map<String, Object> relatedLinkToMap(UserStoryStore.RelatedLink link) {
        return Map.of("id", link.id(), "storyId", link.storyId(), "title", link.title(), "url", link.url(), "type", link.type(), "createdAt", link.createdAt(), "updatedAt", link.updatedAt());
    }
    private Map<String, Object> activityToMap(UserStoryStore.Activity activity) {
        return Map.of("id", activity.id(), "storyId", activity.storyId(), "action", activity.action(), "detail", activity.detail(), "actorId", activity.actorId(), "actorName", activity.actorName(), "createdAt", activity.createdAt(), "updatedAt", activity.updatedAt(), "edited", activity.edited(), "deleted", activity.deleted());
    }
    private Map<String, Object> workItemToMap(UserStoryStore.WorkItem item) {
        return Map.of("id", item.id(), "title", item.title(), "type", item.type(), "projectId", item.projectId());
    }
    private void validateRelatedWorkItemTarget(String target, String type) {
        if (!"Test Case".equals(type) || target == null || target.startsWith("http://") || target.startsWith("https://")) return;
        if (testCaseManager.list().stream().noneMatch(testCase -> testCase.id().equals(target) || testCase.caseId().equals(target))) {
            throw new IllegalArgumentException("Test case was not found: " + target);
        }
    }

    private Map<String, Object> uploadUserStoryAttachment(HttpExchange exchange, String storyId) throws IOException {
        userStoryStore.listAttachments(storyId); // validates story ownership/existence before accepting bytes
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        Matcher boundaryMatcher = contentType == null ? null : MULTIPART_BOUNDARY.matcher(contentType);
        if (boundaryMatcher == null || !boundaryMatcher.find()) throw new IllegalArgumentException("Attachment upload must use multipart/form-data.");
        long contentLength = parseContentLength(exchange.getRequestHeaders().getFirst("Content-Length"));
        if (contentLength > MAX_ATTACHMENT_BYTES + 64 * 1024L) throw new IllegalArgumentException("Attachment request exceeds the configured 10 MB limit.");
        Path directory = projectRoot.resolve(ATTACHMENT_DIRECTORY).toAbsolutePath().normalize();
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, ".upload-", ".tmp");
        String originalName = "";
        String declaredType = "application/octet-stream";
        boolean keep = false;
        try (InputStream input = new BufferedInputStream(exchange.getRequestBody())) {
            String boundary = boundaryMatcher.group(1);
            String line;
            do { line = readMultipartLine(input); } while (line != null && !line.equals("--" + boundary) && !line.equals("--" + boundary + "--"));
            if (line == null || line.endsWith("--")) throw new IllegalArgumentException("No attachment file was supplied.");
            while ((line = readMultipartLine(input)) != null && !line.isEmpty()) {
                if (line.toLowerCase(Locale.ROOT).startsWith("content-disposition:")) originalName = multipartParameter(line, "filename");
                if (line.toLowerCase(Locale.ROOT).startsWith("content-type:")) declaredType = line.substring(line.indexOf(':') + 1).trim();
            }
            if (originalName.isBlank()) throw new IllegalArgumentException("The attachment filename is missing.");
            long size = copyMultipartFile(input, temporary, ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1));
            String safeName = safeUploadName(originalName);
            String validatedType = validateAttachmentFile(temporary, safeName, declaredType, size);
            String storageName = UUID.randomUUID() + extensionOf(safeName);
            Path target = directory.resolve(storageName).normalize();
            if (!target.getParent().equals(directory)) throw new IllegalArgumentException("Invalid attachment path.");
            StorageWriteCoordinator.coordinatedWrite(projectRoot, "attachments",
                    () -> Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING));
            try {
                UserStoryStore.Attachment saved = userStoryStore.addAttachment(storyId, safeName, storageName, size, validatedType, CURRENT_USER_ID, CURRENT_USER_NAME);
                String storyProject = projectIdForStory(storyId);
                if (storyProject.isBlank()) {
                    projectOwnershipManager.quarantine("ATTACHMENT", saved.id(), "STORY_OWNERSHIP_UNRESOLVED");
                } else {
                    projectOwnershipManager.assign("ATTACHMENT", saved.id(), storyProject,
                            "USER_STORY_RELATIONSHIP", validProjectIds());
                }
                userStoryStore.recordActivity(storyId, "Attachment added", safeName, CURRENT_USER_ID, CURRENT_USER_NAME);
                keep = true;
                return attachmentToMap(saved);
            } finally {
                if (!keep) StorageWriteCoordinator.coordinatedWrite(projectRoot, "attachments", () -> Files.deleteIfExists(target));
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void serveUserStoryAttachment(HttpExchange exchange, String attachmentId, boolean download) throws IOException {
        UserStoryStore.Attachment attachment = userStoryStore.findAttachment(attachmentId);
        Path directory = projectRoot.resolve(ATTACHMENT_DIRECTORY).toAbsolutePath().normalize();
        Path file = directory.resolve(attachment.storageName()).normalize();
        if (!file.getParent().equals(directory) || !Files.isRegularFile(file)) throw new IllegalArgumentException("Attachment is not available.");
        exchange.getResponseHeaders().set("Content-Type", attachment.contentType());
        exchange.getResponseHeaders().set("Content-Disposition", (download ? "attachment" : "inline") + "; filename=\"" + safeHeaderFilename(attachment.originalName()) + "\"");
        exchange.sendResponseHeaders(200, Files.size(file));
        try (InputStream input = Files.newInputStream(file); OutputStream output = exchange.getResponseBody()) { input.transferTo(output); }
    }

    private void deleteUserStoryAttachment(HttpExchange exchange, String attachmentId) throws IOException {
        UserStoryStore.Attachment attachment = userStoryStore.findAttachment(attachmentId);
        Path directory = projectRoot.resolve(ATTACHMENT_DIRECTORY).toAbsolutePath().normalize();
        Path file = directory.resolve(attachment.storageName()).normalize();
        userStoryStore.removeAttachment(attachmentId);
        userStoryStore.recordActivity(attachment.storyId(), "Attachment removed", attachment.originalName(), CURRENT_USER_ID, CURRENT_USER_NAME);
        if (file.getParent().equals(directory)) {
            StorageWriteCoordinator.coordinatedWrite(projectRoot, "attachments", () -> Files.deleteIfExists(file));
        }
        sendJson(exchange, 204, Map.of());
    }

    private static long parseContentLength(String value) { try { return value == null ? -1 : Long.parseLong(value); } catch (NumberFormatException e) { return -1; } }
    private static String readMultipartLine(InputStream input) throws IOException { StringBuilder line = new StringBuilder(); int value; while ((value = input.read()) != -1) { if (value == '\n') break; if (value != '\r') line.append((char) value); if (line.length() > 8192) throw new IllegalArgumentException("Multipart header is too large."); } return value == -1 && line.isEmpty() ? null : line.toString(); }
    private static String multipartParameter(String line, String name) { String marker = name + "="; int start = line.toLowerCase(Locale.ROOT).indexOf(marker); if (start < 0) return ""; String value = line.substring(start + marker.length()).trim(); if (value.startsWith("\"")) { int end = value.indexOf('"', 1); return end > 0 ? value.substring(1, end) : ""; } int end = value.indexOf(';'); return (end < 0 ? value : value.substring(0, end)).trim(); }
    private static long copyMultipartFile(InputStream input, Path target, byte[] marker) throws IOException {
        byte[] pending = new byte[marker.length]; int pendingSize = 0; long written = 0;
        try (OutputStream output = Files.newOutputStream(target)) {
            int value;
            while ((value = input.read()) != -1) {
                if (pendingSize == marker.length) { output.write(pending[0]); System.arraycopy(pending, 1, pending, 0, marker.length - 1); pendingSize--; written++; if (written > MAX_ATTACHMENT_BYTES) throw new IllegalArgumentException("Attachment exceeds the configured 10 MB limit."); }
                pending[pendingSize++] = (byte) value;
                if (pendingSize == marker.length && startsWith(pending, marker)) { int next = input.read(); int next2 = input.read(); if (next == '-' && next2 == '-' || next == '\r' && next2 == '\n') return written; throw new IllegalArgumentException("Invalid multipart boundary."); }
            }
        }
        throw new IllegalArgumentException("Incomplete attachment upload.");
    }
    private static boolean startsWith(byte[] value, byte[] prefix) { if (value.length != prefix.length) return false; for (int i = 0; i < prefix.length; i++) if (value[i] != prefix[i]) return false; return true; }
    private static String safeUploadName(String name) { String value = name.replace('\\', '/'); value = value.substring(value.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_").trim(); if (value.isBlank() || value.length() > 180) throw new IllegalArgumentException("Invalid attachment filename."); return value; }
    private static String extensionOf(String name) { int dot = name.lastIndexOf('.'); return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT); }
    private static String validateAttachmentFile(Path file, String name, String declaredType, long size) throws IOException {
        if (size <= 0 || size > MAX_ATTACHMENT_BYTES) throw new IllegalArgumentException("Each attachment must be between 1 byte and 10 MB.");
        String extension = extensionOf(name); if (!List.of(".png", ".jpg", ".jpeg", ".pdf", ".txt", ".log", ".csv").contains(extension)) throw new IllegalArgumentException("Unsupported attachment type.");
        byte[] header;
        try (InputStream input = Files.newInputStream(file)) { header = input.readNBytes(8); }
        boolean binaryValid = extension.equals(".png") && header.length >= 8 && header[0] == (byte)0x89 && header[1] == 0x50 && header[2] == 0x4e && header[3] == 0x47 || (extension.equals(".jpg") || extension.equals(".jpeg")) && header.length >= 3 && header[0] == (byte)0xff && header[1] == (byte)0xd8 && header[2] == (byte)0xff || extension.equals(".pdf") && new String(header, StandardCharsets.ISO_8859_1).startsWith("%PDF");
        if (extension.equals(".png") || extension.equals(".jpg") || extension.equals(".jpeg") || extension.equals(".pdf")) { if (!binaryValid) throw new IllegalArgumentException("File content does not match its extension."); return extension.equals(".pdf") ? "application/pdf" : extension.equals(".png") ? "image/png" : "image/jpeg"; }
        if (containsNul(file)) throw new IllegalArgumentException("Text attachments must not contain binary content.");
        return extension.equals(".csv") ? "text/csv" : "text/plain";
    }
    private static boolean containsNul(Path file) throws IOException { try (InputStream input = Files.newInputStream(file)) { byte[] buffer = new byte[8192]; int count; while ((count = input.read(buffer)) >= 0) for (int i = 0; i < count; i++) if (buffer[i] == 0) return true; } return false; }
    private static String safeHeaderFilename(String value) { return value.replaceAll("[^A-Za-z0-9._ -]", "_"); }

    private Map<String, Object> sprintToMap(UserStoryStore.Sprint sprint) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", sprint.id()); map.put("projectId", sprint.projectId()); map.put("name", sprint.name());
        map.put("goal", sprint.goal()); map.put("startDate", sprint.startDate()); map.put("endDate", sprint.endDate());
        map.put("status", sprint.status()); map.put("createdAt", sprint.createdAt()); map.put("updatedAt", sprint.updatedAt());
        return map;
    }

    private Map<String, Object> linkToMap(UserStoryStore.TestCaseLink link) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("storyId", link.storyId()); map.put("testCaseId", link.testCaseId()); map.put("createdAt", link.createdAt());
        return map;
    }

    private List<Map<String, Object>> linkedTestCases(String storyId, String search, int offset, int limit) throws IOException {
        String query = search == null ? "" : search.trim().toLowerCase();
        List<TestCaseManager.TestCase> allTestCases = testCaseManager.list();
        Map<String, TestCaseManager.TestCase> testCasesById = allTestCases.stream().collect(java.util.stream.Collectors.toMap(TestCaseManager.TestCase::id, testCase -> testCase, (a, b) -> a, LinkedHashMap::new));
        Map<String, UserStoryStore.TestCaseLink> links = userStoryStore.listTestCaseLinks(storyId).stream()
                .collect(java.util.stream.Collectors.toMap(UserStoryStore.TestCaseLink::testCaseId, link -> link, (a, b) -> a, LinkedHashMap::new));
        List<Map<String, Object>> result = new ArrayList<>();
        for (TestCaseManager.TestCase testCase : allTestCases) {
            UserStoryStore.TestCaseLink link = links.get(testCase.id());
            if (link == null) continue;
            String searchable = (testCase.id() + " " + testCase.caseId() + " " + testCase.name()).toLowerCase();
            if (!query.isBlank() && !searchable.contains(query)) continue;
            Map<String, Object> map = testCaseToMap(testCase);
            map.put("linkCreatedAt", link.createdAt());
            result.add(map);
        }
        for (UserStoryStore.TestCaseLink link : userStoryStore.listTestCaseLinks(storyId)) {
            if (testCasesById.containsKey(link.testCaseId())) continue;
            if (!query.isBlank() && !link.testCaseId().toLowerCase().contains(query)) continue;
            result.add(new LinkedHashMap<>(Map.of("id", link.testCaseId(), "caseId", link.testCaseId(), "name", "Unavailable test case", "unavailable", true, "linkCreatedAt", link.createdAt())));
        }
        int from = Math.min(result.size(), Math.max(0, offset));
        return result.subList(from, Math.min(result.size(), from + Math.max(1, limit)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> requestObject(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) throw new IllegalArgumentException("Request body is too large.");
        Object parsed = JsonParser.parse(new String(body, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> values)) throw new IllegalArgumentException("Request body must be a JSON object.");
        return (Map<String, Object>) values;
    }

    private static String queryParameter(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return null;
        for (String part : query.split("&")) {
            String[] pieces = part.split("=", 2);
            if (pieces.length == 2 && pieces[0].equals(name)) {
                return java.net.URLDecoder.decode(pieces[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private UserStoryStore.Project createUserStoryProject(HttpExchange exchange) throws IOException {
        Map<String, Object> v = requestObject(exchange);
        return userStoryStore.createProject(stringField(v, "key"), stringField(v, "name"), optionalString(v, "description"), optionalString(v, "owner"));
    }

    private UserStoryStore.Project updateUserStoryProject(HttpExchange exchange, String id) throws IOException {
        Map<String, Object> v = requestObject(exchange);
        return userStoryStore.updateProject(id, optionalString(v, "key"), optionalString(v, "name"), optionalString(v, "description"), optionalString(v, "owner"));
    }

    private UserStoryStore.Epic createUserStoryEpic(HttpExchange exchange) throws IOException {
        Map<String, Object> v = requestObject(exchange);
        String projectId = stringField(v, "projectId");
        requireProjectAccess(exchange, projectId, TeamAuthorization.USER_STORIES, true);
        return userStoryStore.createEpic(projectId, stringField(v, "key"), stringField(v, "name"), optionalString(v, "description"), optionalString(v, "status"), optionalString(v, "priority"));
    }

    private UserStoryStore.Epic updateUserStoryEpic(HttpExchange exchange, String id) throws IOException {
        Map<String, Object> v = requestObject(exchange);
        return userStoryStore.updateEpic(id, optionalString(v, "key"), optionalString(v, "name"), optionalString(v, "description"), optionalString(v, "status"), optionalString(v, "priority"));
    }

    private UserStoryStore.Story createUserStory(HttpExchange exchange) throws IOException {
        Map<String, Object> v = requestObject(exchange);
        String projectId = stringField(v, "projectId");
        requireProjectAccess(exchange, projectId, TeamAuthorization.USER_STORIES, true);
        return userStoryStore.createStory(projectId, optionalString(v, "epicId"), stringField(v, "title"), sanitizeRichText(optionalString(v, "description")), optionalString(v, "asA"), optionalString(v, "iWant"), optionalString(v, "soThat"), sanitizeRichText(optionalString(v, "acceptanceCriteria")), optionalString(v, "priority"), optionalString(v, "status"), numberField(v, "storyPoints", 0), optionalString(v, "assignee"), optionalString(v, "reporter"), authActorId(exchange), authActorName(exchange));
    }

    private UserStoryStore.Story updateUserStory(HttpExchange exchange, String id) throws IOException {
        Map<String, Object> v = requestObject(exchange);
        return userStoryStore.updateStory(id, optionalString(v, "epicId"), optionalString(v, "title"), sanitizeRichText(optionalString(v, "description")), optionalString(v, "asA"), optionalString(v, "iWant"), optionalString(v, "soThat"), sanitizeRichText(optionalString(v, "acceptanceCriteria")), optionalString(v, "priority"), optionalString(v, "status"), numberField(v, "storyPoints", 0), optionalString(v, "assignee"), optionalString(v, "reporter"), numberField(v, "businessValue", 0), optionalString(v, "risk"), authActorId(exchange), authActorName(exchange), optionalString(v, "sprintId"));
    }

    private UserStoryStore.Sprint createSprint(HttpExchange exchange) throws IOException {
        Map<String, Object> v = requestObject(exchange);
        String projectId = stringField(v, "projectId");
        requireProjectAccess(exchange, projectId, TeamAuthorization.SPRINT_BOARD, true);
        return userStoryStore.createSprint(projectId, stringField(v, "name"), optionalString(v, "goal"), stringField(v, "startDate"), stringField(v, "endDate"));
    }

    private UserStoryStore.Sprint updateSprint(HttpExchange exchange, String id) throws IOException {
        Map<String, Object> v = requestObject(exchange);
        return userStoryStore.updateSprint(id, optionalString(v, "name"), optionalString(v, "goal"), optionalString(v, "startDate"), optionalString(v, "endDate"));
    }

    private static int numberField(Map<?, ?> values, String key, int fallback) {
        Object value = values.get(key);
        return value instanceof Number n ? n.intValue() : fallback;
    }

    /** Lightweight server-side allow-list sanitizer; no client-supplied HTML is trusted. */
    static String sanitizeRichText(String value) {
        if (value == null || value.isBlank()) return "";
        String input = value.replaceAll("(?is)<!--.*?-->", "")
                .replaceAll("(?is)<(script|style|iframe|object|embed|svg|math)[^>]*>.*?</\\1>", "")
                .replaceAll("(?is)<(script|style|iframe|object|embed|svg|math)[^>]*/?>", "");
        Matcher matcher = Pattern.compile("(?is)<\\s*(/?)\\s*([a-z0-9]+)([^>]*)>").matcher(input);
        StringBuilder output = new StringBuilder();
        int end = 0;
        while (matcher.find()) {
            appendEscapedText(output, input.substring(end, matcher.start()));
            String closing = matcher.group(1);
            String tag = matcher.group(2).toLowerCase(Locale.ROOT);
            if (List.of("strong", "b", "em", "i", "u", "ul", "ol", "li", "p", "br", "a").contains(tag)) {
                if ("/".equals(closing)) output.append("</").append(tag).append('>');
                else if ("br".equals(tag)) output.append("<br>");
                else if ("a".equals(tag)) {
                    Matcher href = Pattern.compile("(?i)href\\s*=\\s*[\\\"'](https?://[^\\\"']+)[\\\"']").matcher(matcher.group(3));
                    output.append(href.find() ? "<a href=\"" + safeHtmlAttribute(href.group(1)) + "\" rel=\"noopener noreferrer\" target=\"_blank\">" : "<a>");
                } else output.append('<').append(tag).append('>');
            }
            end = matcher.end();
        }
        appendEscapedText(output, input.substring(end));
        return output.toString().trim();
    }
    private static void appendEscapedText(StringBuilder output, String text) { output.append(text.replaceAll("&(?!#?[A-Za-z0-9]+;)", "&amp;").replace("<", "&lt;").replace(">", "&gt;")); }
    private static String safeHtmlAttribute(String value) { return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "%3C").replace(">", "%3E"); }

    private static String queryValue(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return null;
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length == 2 && name.equals(pair[0])) return java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
        }
        return null;
    }

    private static String queryValueOrEmpty(HttpExchange exchange, String name) {
        String value = queryValue(exchange, name);
        return value == null ? "" : value;
    }

    private static boolean queryBoolean(HttpExchange exchange, String name) { return "true".equalsIgnoreCase(queryValue(exchange, name)); }
    private static int queryInt(HttpExchange exchange, String name, int fallback) { try { return Integer.parseInt(queryValue(exchange, name)); } catch (Exception e) { return fallback; } }

    // === Test Suite Helpers ===

    /**
     * POST /api/suites — create a new test suite.
     */
    @SuppressWarnings("unchecked")
    private void createSuite(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("Request body is too large.");
        }
        Object parsed = JsonParser.parse(new String(body, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Request body must be a JSON object.");
        }
        String name = stringField(values, "name");
        String parentId = optionalString(values, "parentId");
        TestCaseManager.TestSuite created = testCaseManager.createSuite(name, parentId);
        sendJson(exchange, 201, suiteToMap(created));
    }

    /**
     * PUT /api/suites/{id} — update an existing test suite.
     */
    @SuppressWarnings("unchecked")
    private void updateSuite(HttpExchange exchange, String id) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("Request body is too large.");
        }
        Object parsed = JsonParser.parse(new String(body, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Request body must be a JSON object.");
        }
        String name = optionalString(values, "name");
        String parentId = optionalString(values, "parentId");
        try {
            TestCaseManager.TestSuite updated = testCaseManager.updateSuite(id, name, parentId);
            sendJson(exchange, 200, suiteToMap(updated));
        } catch (IllegalArgumentException e) {
            sendError(exchange, 404, e.getMessage());
        }
    }

    // === Story Helpers ===

    /**
     * POST /api/stories — create a new story.
     */
    @SuppressWarnings("unchecked")
    private void createStory(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("Request body is too large.");
        }
        Object parsed = JsonParser.parse(new String(body, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Request body must be a JSON object.");
        }
        String title = stringField(values, "title");
        String description = optionalString(values, "description");
        String status = optionalString(values, "status");
        String priority = optionalString(values, "priority");
        String assignee = optionalString(values, "assignee");
        int storyPoints = 0;
        Object sp = values.get("storyPoints");
        if (sp instanceof Number n) storyPoints = n.intValue();
        TestCaseManager.Story created = testCaseManager.createStory(title, description, status, priority, assignee, storyPoints);
        sendJson(exchange, 201, storyToMap(created));
    }

    /**
     * PUT /api/stories/{id} — update an existing story.
     */
    @SuppressWarnings("unchecked")
    private void updateStory(HttpExchange exchange, String id) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("Request body is too large.");
        }
        Object parsed = JsonParser.parse(new String(body, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Request body must be a JSON object.");
        }
        String title = optionalString(values, "title");
        String description = optionalString(values, "description");
        String status = optionalString(values, "status");
        String priority = optionalString(values, "priority");
        String assignee = optionalString(values, "assignee");
        int storyPoints = 0;
        Object sp = values.get("storyPoints");
        if (sp instanceof Number n) storyPoints = n.intValue();
        try {
            TestCaseManager.Story updated = testCaseManager.updateStory(id, title, description, status, priority, assignee, storyPoints);
            sendJson(exchange, 200, storyToMap(updated));
        } catch (IllegalArgumentException e) {
            sendError(exchange, 404, e.getMessage());
        }
    }

    // === AI Assistant Handler ===

    /**
     * POST /api/ai/ask — AI assistant that helps with coding, frameworks, and testing questions.
     */
    @SuppressWarnings("unchecked")
    private void handleAiAsk(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("Request body is too large.");
        }
        Object parsed = JsonParser.parse(new String(body, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Request body must be a JSON object.");
        }
        String query = stringField(values, "query");
        if (query.isBlank()) {
            throw new IllegalArgumentException("Query is required.");
        }
        String response = generateAiResponse(query.trim());
        sendJson(exchange, 200, Map.of("response", response, "timestamp", java.time.Instant.now().toString()));
    }

    /**
     * Generate an AI-like response based on the user's query.
     * Uses pattern matching against known frameworks, testing concepts, and coding patterns.
     */
    private String generateAiResponse(String query) {
        String q = query.toLowerCase().trim();

        // Framework-specific help
        if (q.contains("cucumber") || q.contains("bdd") || q.contains("gherkin")) {
            return """
                **Cucumber BDD Framework Help**
                
                Cucumber uses Gherkin syntax for behavior-driven development. Here's how to write a feature:
                
                ```gherkin
                Feature: User Login
                  Scenario: Successful login
                    Given I am on the login page
                    When I enter valid credentials
                    And I click the login button
                    Then I should see the dashboard
                ```
                
                **Step Definition Example:**
                ```java
                @Given("I am on the login page")
                public void iAmOnTheLoginPage() {
                    driver.get("https://example.com/login");
                }
                
                @When("I enter valid credentials")
                public void iEnterValidCredentials() {
                    driver.findElement(By.id("username")).sendKeys("admin");
                    driver.findElement(By.id("password")).sendKeys("password");
                }
                
                @Then("I should see the dashboard")
                public void iShouldSeeTheDashboard() {
                    assertTrue(driver.getCurrentUrl().contains("dashboard"));
                }
                ```
                
                **Key Concepts:**
                - Use `@Given` for preconditions
                - Use `@When` for actions
                - Use `@Then` for assertions
                - Use `@And` for additional steps
                - Group related scenarios with `Background`
                
                Need help with a specific Cucumber scenario?""";
        }

        if (q.contains("selenium") || q.contains("webdriver") || q.contains("browser")) {
            return """
                **Selenium WebDriver Help**
                
                Selenium is used for browser automation. Here are common patterns:
                
                **Element Locators:**
                ```java
                // By ID
                WebElement element = driver.findElement(By.id("username"));
                
                // By CSS Selector
                WebElement element = driver.findElement(By.cssSelector(".class-name"));
                
                // By XPath
                WebElement element = driver.findElement(By.xpath("//div[@class='name']"));
                
                // By Class Name
                WebElement element = driver.findElement(By.className("my-class"));
                
                // By Name
                WebElement element = driver.findElement(By.name("field-name"));
                ```
                
                **Common Actions:**
                ```java
                // Click
                element.click();
                
                // Type text
                element.sendKeys("Hello World");
                
                // Get text
                String text = element.getText();
                
                // Wait for element
                WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
                wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("element")));
                
                // Handle dropdowns
                Select dropdown = new Select(driver.findElement(By.id("dropdown")));
                dropdown.selectByVisibleText("Option 1");
                ```
                
                **Explicit Waits (Recommended):**
                ```java
                WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
                wait.until(ExpectedConditions.elementToBeClickable(By.id("submit")));
                ```
                
                What specific Selenium task do you need help with?""";
        }

        if (q.contains("playwright")) {
            return """
                **Playwright Help**
                
                Playwright is a modern browser automation framework. Here are common patterns:
                
                **Basic Setup:**
                ```java
                try (Playwright playwright = Playwright.create()) {
                    Browser browser = playwright.chromium().launch();
                    Page page = browser.newPage();
                    page.navigate("https://example.com");
                }
                ```
                
                **Common Actions:**
                ```java
                // Click
                page.click("#button-id");
                
                // Fill input
                page.fill("#input-id", "text");
                
                // Get text
                String text = page.textContent("#element");
                
                // Wait for element
                page.waitForSelector(".element-class");
                
                // Screenshot
                page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("screenshot.png")));
                
                // Handle multiple tabs
                Page newPage = page.waitForPopup(() -> page.click("#link"));
                ```
                
                **Assertions:**
                ```java
                import static com.microsoft.playwright.assertions.PlaywrightAssertions.*;
                
                expect(page.locator("#element")).toHaveText("Expected text");
                expect(page.locator("#element")).toBeVisible();
                ```
                
                What Playwright functionality do you need help with?""";
        }

        if (q.contains("rest assured") || q.contains("restassured") || q.contains("api test")) {
            return """
                **REST Assured API Testing Help**
                
                REST Assured simplifies API testing in Java:
                
                **GET Request:**
                ```java
                given()
                    .baseUri("https://jsonplaceholder.typicode.com")
                    .header("Content-Type", "application/json")
                .when()
                    .get("/posts/1")
                .then()
                    .statusCode(200)
                    .body("title", notNullValue());
                ```
                
                **POST Request:**
                ```java
                String json = "{\"title\": \"foo\", \"body\": \"bar\", \"userId\": 1}";
                
                given()
                    .baseUri("https://jsonplaceholder.typicode.com")
                    .contentType(ContentType.JSON)
                    .body(json)
                .when()
                    .post("/posts")
                .then()
                    .statusCode(201)
                    .body("id", greaterThan(0));
                ```
                
                **PUT Request:**
                ```java
                given()
                    .baseUri("https://jsonplaceholder.typicode.com")
                    .contentType(ContentType.JSON)
                    .body(json)
                .when()
                    .put("/posts/1")
                .then()
                    .statusCode(200);
                ```
                
                **DELETE Request:**
                ```java
                given()
                    .baseUri("https://jsonplaceholder.typicode.com")
                .when()
                    .delete("/posts/1")
                .then()
                    .statusCode(200);
                ```
                
                **JSON Path Assertions:**
                ```java
                .body("title", equalTo("foo"))
                .body("userId", equalTo(1))
                .body("id", notNullValue())
                ```
                
                What API testing scenario do you need help with?""";
        }

        if (q.contains("junit") || q.contains("junit5")) {
            return """
                **JUnit 5 Testing Help**
                
                JUnit 5 is the standard Java testing framework:
                
                **Basic Test Structure:**
                ```java
                @Test
                void shouldReturnTrueWhenValid() {
                    // Arrange
                    Calculator calc = new Calculator();
                    
                    // Act
                    int result = calc.add(2, 3);
                    
                    // Assert
                    assertEquals(5, result);
                }
                ```
                
                **Lifecycle Methods:**
                ```java
                @BeforeEach  // Runs before each test
                void setUp() {
                    // Initialize test data
                }
                
                @AfterEach  // Runs after each test
                void tearDown() {
                    // Clean up
                }
                
                @BeforeAll  // Runs once before all tests
                static void initAll() {
                    // Static setup
                }
                
                @AfterAll  // Runs once after all tests
                static void cleanupAll() {
                    // Static cleanup
                }
                ```
                
                **Parameterized Tests:**
                ```java
                @ParameterizedTest
                @ValueSource(strings = {"hello", "world"})
                void testWithStringParameter(String input) {
                    assertNotNull(input);
                }
                ```
                
                **Assertions:**
                ```java
                assertEquals(expected, actual)
                assertTrue(condition)
                assertFalse(condition)
                assertNotNull(object)
                assertNull(object)
                assertThrows(Exception.class, () -> { /* code */ })
                ```
                
                What JUnit functionality do you need help with?""";
        }

        if (q.contains("testng")) {
            return """
                **TestNG Help**
                
                TestNG is a powerful testing framework with advanced features:
                
                **Basic Test:**
                ```java
                @Test
                public void testLogin() {
                    assertEquals("success", loginService.login("user", "pass"));
                }
                ```
                
                **Lifecycle Annotations:**
                ```java
                @BeforeSuite
                public void beforeSuite() { }
                
                @AfterSuite
                public void afterSuite() { }
                
                @BeforeTest
                public void beforeTest() { }
                
                @AfterTest
                public void afterTest() { }
                
                @BeforeMethod
                public void beforeMethod() { }
                
                @AfterMethod
                public void afterMethod() { }
                ```
                
                **Data-Driven Testing:**
                ```java
                @Test(dataProvider = "loginData")
                public void testLogin(String user, String pass, boolean expected) {
                    boolean result = loginService.login(user, pass);
                    assertEquals(expected, result);
                }
                
                @DataProvider(name = "loginData")
                public Object[][] provideData() {
                    return new Object[][] {
                        {"user1", "pass1", true},
                        {"user2", "wrong", false}
                    };
                }
                ```
                
                **Parallel Execution:**
                ```xml
                <suite name="Suite" parallel="tests" thread-count="3">
                    <test name="Test1">...</test>
                    <test name="Test2">...</test>
                </suite>
                ```
                
                What TestNG feature do you need help with?""";
        }

        if (q.contains("jmeter") || q.contains("performance") || q.contains("load test")) {
            return """
                **JMeter Performance Testing Help**
                
                JMeter is used for load and performance testing:
                
                **Test Plan Structure:**
                1. Thread Group - Simulates users
                2. HTTP Request Defaults - Base URL configuration
                3. HTTP Request Samplers - Individual API calls
                4. Listeners - Results visualization
                
                **Key Configurations:**
                ```
                Thread Group:
                - Number of Threads: 100 (users)
                - Ramp-Up Period: 10 (seconds)
                - Loop Count: 10 (iterations)
                
                HTTP Request Defaults:
                - Server Name: api.example.com
                - Protocol: https
                - Port: 443
                ```
                
                **Assertions:**
                - Response Code: 200
                - Response Message: OK
                - Response Time: < 500ms
                
                **Listeners:**
                - View Results Tree - Debug individual requests
                - Summary Report - Aggregate statistics
                - Graph Results - Response time trends
                
                **Common Metrics:**
                - Average Response Time
                - 90th/95th Percentile
                - Throughput (requests/sec)
                - Error Rate
                
                Need help with a specific JMeter scenario?""";
        }

        if (q.contains("maven")) {
            return """
                **Maven Help**
                
                Maven manages project dependencies and build lifecycle:
                
                **Common Commands:**
                ```bash
                mvn clean install          # Clean and build with tests
                mvn clean package -DskipTests  # Build without tests
                mvn test                   # Run all tests
                mvn test -Dtest=ClassName  # Run specific test class
                mvn dependency:tree        # Show dependency tree
                mvn clean compile          # Compile only
                ```
                
                **Adding Dependencies (pom.xml):**
                ```xml
                <dependencies>
                    <dependency>
                        <groupId>org.seleniumhq.selenium</groupId>
                        <artifactId>selenium-java</artifactId>
                        <version>4.15.0</version>
                    </dependency>
                </dependencies>
                ```
                
                **Running Specific Test:**
                ```bash
                mvn test -Dtest=LoginTest#testSuccessfulLogin
                ```
                
                What Maven task do you need help with?""";
        }

        if (q.contains("docker") || q.contains("container")) {
            return """
                **Docker Help**
                
                Docker containerizes your application:
                
                **Basic Dockerfile:**
                ```dockerfile
                FROM eclipse-temurin:25-jre
                WORKDIR /app
                COPY target/*.jar app.jar
                EXPOSE 8081
                ENTRYPOINT ["java", "-jar", "app.jar"]
                ```
                
                **Common Commands:**
                ```bash
                docker build -t myapp .              # Build image
                docker run -p 8081:8081 myapp        # Run container
                docker ps                            # List running containers
                docker stop <container-id>           # Stop container
                docker logs <container-id>           # View logs
                ```
                
                **Docker Compose:**
                ```yaml
                version: '3'
                services:
                  app:
                    image: myapp
                    ports:
                      - "8081:8081"
                  db:
                    image: postgres:15
                    environment:
                      POSTGRES_DB: testdb
                ```
                
                What Docker task do you need help with?""";
        }

        if (q.contains("git") || q.contains("github")) {
            return """
                **Git Help**
                
                Git is a version control system:
                
                **Common Commands:**
                ```bash
                git status                    # Check status
                git add .                     # Stage all changes
                git commit -m "message"       # Commit changes
                git push                      # Push to remote
                git pull                      # Pull from remote
                git branch                    # List branches
                git checkout -b new-branch    # Create new branch
                git merge branch-name         # Merge branch
                git log --oneline             # View commit history
                ```
                
                **GitHub Workflow:**
                1. Create feature branch: `git checkout -b feature/my-feature`
                2. Make changes and commit
                3. Push: `git push origin feature/my-feature`
                4. Create Pull Request on GitHub
                5. Review and merge
                
                What Git operation do you need help with?""";
        }

        if (q.contains("ci/cd") || q.contains("pipeline") || q.contains("jenkins") || q.contains("github action")) {
            return """
                **CI/CD Pipeline Help**
                
                CI/CD automates building, testing, and deployment:
                
                **GitHub Actions Example:**
                ```yaml
                name: CI Pipeline
                on: [push, pull_request]
                jobs:
                  test:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/checkout@v3
                      - uses: actions/setup-java@v3
                        with:
                          java-version: '25'
                      - run: mvn clean test
                      - run: mvn package
                ```
                
                **Jenkins Pipeline:**
                ```groovy
                pipeline {
                    agent any
                    stages {
                        stage('Build') {
                            steps {
                                sh 'mvn clean package'
                            }
                        }
                        stage('Test') {
                            steps {
                                sh 'mvn test'
                            }
                        }
                    }
                }
                ```
                
                **Best Practices:**
                - Run tests on every commit
                - Fail fast on errors
                - Use caching for dependencies
                - Store artifacts for deployment
                
                What CI/CD task do you need help with?""";
        }

        if (q.contains("page object") || q.contains("page factory") || q.contains("pof")) {
            return """
                **Page Object Pattern Help**
                
                Page Object is a design pattern for maintaining test code:
                
                **Base Page:**
                ```java
                public class BasePage {
                    protected WebDriver driver;
                    
                    public BasePage(WebDriver driver) {
                        this.driver = driver;
                    }
                    
                    protected void click(By locator) {
                        driver.findElement(locator).click();
                    }
                    
                    protected void type(By locator, String text) {
                        driver.findElement(locator).sendKeys(text);
                    }
                }
                ```
                
                **Login Page:**
                ```java
                public class LoginPage extends BasePage {
                    private By username = By.id("username");
                    private By password = By.id("password");
                    private By loginBtn = By.id("login");
                    
                    public LoginPage(WebDriver driver) {
                        super(driver);
                    }
                    
                    public void login(String user, String pass) {
                        type(username, user);
                        type(password, pass);
                        click(loginBtn);
                    }
                }
                ```
                
                **Test Using Page Object:**
                ```java
                @Test
                void testLogin() {
                    LoginPage loginPage = new LoginPage(driver);
                    loginPage.login("admin", "password");
                    
                    DashboardPage dashboard = new DashboardPage(driver);
                    assertTrue(dashboard.isDisplayed());
                }
                ```
                
                What Page Object pattern question do you have?""";
        }

        if (q.contains("assertion") || q.contains("assert") || q.contains("verify")) {
            return """
                **Assertions Help**
                
                Assertions verify expected vs actual results:
                
                **JUnit Assertions:**
                ```java
                assertEquals(expected, actual)
                assertEquals(expected, actual, "message")
                assertTrue(condition)
                assertFalse(condition)
                assertNotNull(object)
                assertNull(object)
                assertSame(obj1, obj2)
                assertNotSame(obj1, obj2)
                assertThrows(Exception.class, () -> { /* code */ })
                assertAll("group", () -> assert1(), () -> assert2())
                ```
                
                **Hamcrest Matchers:**
                ```java
                import static org.hamcrest.Matchers.*;
                
                assertThat(actual, is(expected))
                assertThat(actual, containsString("text"))
                assertThat(actual, hasItem("item"))
                assertThat(actual, greaterThan(10))
                assertThat(actual, notNullValue())
                ```
                
                **Soft Assertions (TestNG):**
                ```java
                SoftAssertions soft = new SoftAssertions();
                soft.assertEquals(actual, expected);
                soft.assertTrue(condition);
                soft.assertAll();  // Throws if any failed
                ```
                
                What assertion scenario do you need help with?""";
        }

        if (q.contains("wait") || q.contains("timeout") || q.contains("flaky")) {
            return """
                **Waits and Timeouts Help**
                
                Proper waits prevent flaky tests:
                
                **Explicit Wait (Recommended):**
                ```java
                WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
                
                // Wait for element to be visible
                wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("element")));
                
                // Wait for element to be clickable
                wait.until(ExpectedConditions.elementToBeClickable(By.id("button")));
                
                // Wait for text to appear
                wait.until(ExpectedConditions.textToBePresentInElement(
                    By.id("status"), "Success"));
                
                // Wait for element to disappear
                wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("loading")));
                ```
                
                **Fluent Wait:**
                ```java
                Wait<WebDriver> wait = new FluentWait<>(driver)
                    .withTimeout(Duration.ofSeconds(30))
                    .pollingEvery(Duration.ofSeconds(2))
                    .ignoring(NoSuchElementException.class);
                
                wait.until(d -> d.findElement(By.id("element")).isDisplayed());
                ```
                
                **Implicit Wait:**
                ```java
                driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10));
                ```
                
                **Thread.sleep (Avoid):**
                ```java
                Thread.sleep(2000);  // Not recommended - use explicit waits instead
                ```
                
                What wait-related issue are you facing?""";
        }

        if (q.contains("exception") || q.contains("error") || q.contains("debug") || q.contains("debugging")) {
            return """
                **Debugging Help**
                
                Common debugging techniques:
                
                **Try-Catch with Logging:**
                ```java
                try {
                    // Code that might fail
                    element.click();
                } catch (Exception e) {
                    System.out.println("Error: " + e.getMessage());
                    e.printStackTrace();
                    throw e;
                }
                ```
                
                **Common Exceptions:**
                - `NoSuchElementException` - Element not found
                - `TimeoutException` - Wait timed out
                - `StaleElementReferenceException` - Element changed
                - `ElementNotInteractableException` - Element not visible/enabled
                
                **Debugging Tips:**
                1. Take screenshots on failure
                2. Log element states before actions
                3. Use explicit waits
                4. Check locators are correct
                5. Verify page loaded completely
                
                **Screenshot on Failure:**
                ```java
                @AfterEach
                void takeScreenshotOnFailure(ExtensionContext context) {
                    if (context.getExecutionException().isPresent()) {
                        TakesScreenshot ts = (TakesScreenshot) driver;
                        File screenshot = ts.getScreenshotAs(OutputType.FILE);
                        // Save screenshot
                    }
                }
                ```
                
                What error are you encountering?""";
        }

        if (q.contains("test data") || q.contains("data driven") || q.contains("parameterized")) {
            return """
                **Data-Driven Testing Help**
                
                Run the same test with different data:
                
                **JUnit Parameterized:**
                ```java
                @ParameterizedTest
                @CsvFileSource(resources = "/test-data.csv")
                void testLogin(String user, String pass, boolean expected) {
                    boolean result = loginService.login(user, pass);
                    assertEquals(expected, result);
                }
                ```
                
                **TestNG Data Provider:**
                ```java
                @Test(dataProvider = "data")
                public void test(String input, int expected) {
                    assertEquals(expected, service.process(input));
                }
                
                @DataProvider(name = "data")
                public Object[][] data() {
                    return new Object[][] {
                        {"input1", 1},
                        {"input2", 2}
                    };
                }
                ```
                
                **JSON Test Data:**
                ```java
                // Read from JSON file
                String json = Files.readString(Path.of("test-data.json"));
                ObjectMapper mapper = new ObjectMapper();
                List<Map<String, Object>> data = mapper.readValue(json, List.class);
                ```
                
                What data-driven testing scenario do you need?""";
        }

        if (q.contains("report") || q.contains("allure") || q.contains("extent")) {
            return """
                **Test Reporting Help**
                
                Generate test execution reports:
                
                **Allure Reports:**
                ```java
                @Test
                @Severity(SeverityLevel.CRITICAL)
                @Description("Test user login functionality")
                @Step("Login with valid credentials")
                void testLogin() {
                    Allure.step("Navigate to login page", () -> {
                        driver.get("https://example.com/login");
                    });
                    
                    Allure.step("Enter credentials", () -> {
                        loginPage.login("admin", "password");
                    });
                    
                    assertTrue(dashboard.isDisplayed());
                }
                ```
                
                **Extent Reports:**
                ```java
                ExtentReports report = new ExtentReports();
                report.attachReporter(new ExtentHtmlReporter("report.html"));
                
                ExtentTest test = report.createTest("Login Test");
                test.pass("Login successful");
                test.fail("Assertion failed");
                
                report.flush();
                ```
                
                What reporting feature do you need?""";
        }

        if (q.contains("test case") || q.contains("test plan") || q.contains("test strategy")) {
            return """
                **Test Planning Help**
                
                Creating effective test cases:
                
                **Test Case Structure:**
                ```
                Test Case ID: TC-001
                Title: Verify user login with valid credentials
                Priority: High
                Precondition: User account exists
                Steps:
                  1. Navigate to login page
                  2. Enter valid username
                  3. Enter valid password
                  4. Click login button
                Expected Result: User is redirected to dashboard
                ```
                
                **Test Types:**
                - **Smoke Testing** - Critical path verification
                - **Regression Testing** - Re-run after changes
                - **Integration Testing** - Component interactions
                - **End-to-End Testing** - Full user workflows
                - **Performance Testing** - Load and stress
                - **Security Testing** - Vulnerability checks
                
                **Test Coverage:**
                - Happy path (positive scenarios)
                - Edge cases (boundary values)
                - Error scenarios (negative testing)
                - Data validation
                
                What aspect of test planning do you need help with?""";
        }

        if (q.contains("java") || q.contains("code") || q.contains("programming")) {
            return """
                **Java Coding Help**
                
                Common Java patterns for testing:
                
                **Utility Methods:**
                ```java
                public class TestUtils {
                    
                    public static String generateRandomString(int length) {
                        return RandomStringUtils.randomAlphanumeric(length);
                    }
                    
                    public static String generateRandomEmail() {
                        return "user" + System.currentTimeMillis() + "@test.com";
                    }
                    
                    public static void waitForPageLoad(WebDriver driver, int seconds) {
                        new WebDriverWait(driver, Duration.ofSeconds(seconds))
                            .until(driver -> 
                                ((JavascriptExecutor) driver)
                                .executeScript("return document.readyState")
                                .equals("complete"));
                    }
                }
                ```
                
                **Singleton Pattern:**
                ```java
                public class DriverManager {
                    private static DriverManager instance;
                    private WebDriver driver;
                    
                    private DriverManager() {
                        driver = new ChromeDriver();
                    }
                    
                    public static synchronized DriverManager getInstance() {
                        if (instance == null) {
                            instance = new DriverManager();
                        }
                        return instance;
                    }
                }
                ```
                
                What Java code do you need help with?""";
        }

        if (q.contains("sql") || q.contains("database") || q.contains("query")) {
            return """
                **Database Testing Help**
                
                Verify data in databases:
                
                **JDBC Connection:**
                ```java
                Connection conn = DriverManager.getConnection(
                    "jdbc:mysql://localhost:3306/testdb", "user", "password");
                
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT * FROM users WHERE id = 1");
                
                if (rs.next()) {
                    String name = rs.getString("name");
                    assertEquals("John", name);
                }
                
                rs.close();
                stmt.close();
                conn.close();
                ```
                
                **Common Queries:**
                ```sql
                -- Check record exists
                SELECT COUNT(*) FROM users WHERE email = 'test@example.com';
                
                -- Verify data integrity
                SELECT * FROM orders WHERE user_id = 1 AND status = 'completed';
                
                -- Check recent inserts
                SELECT * FROM logs ORDER BY created_at DESC LIMIT 10;
                ```
                
                What database testing task do you need?""";
        }

        if (q.contains("json") || q.contains("xml") || q.contains("parse")) {
            return """
                **JSON/XML Parsing Help**
                
                Parse and validate data formats:
                
                **JSON Parsing (Jackson):**
                ```java
                ObjectMapper mapper = new ObjectMapper();
                
                // String to Object
                User user = mapper.readValue(jsonString, User.class);
                
                // Object to String
                String json = mapper.writeValueAsString(user);
                
                // Parse JSON nodes
                JsonNode root = mapper.readTree(jsonString);
                String name = root.path("name").asText();
                int age = root.path("age").asInt();
                ```
                
                **JSON Path (REST Assured):**
                ```java
                Response response = given().get("/users/1");
                String name = response.jsonPath().getString("name");
                int id = response.jsonPath().getInt("id");
                List<String> roles = response.jsonPath().getList("roles");
                ```
                
                What parsing task do you need help with?""";
        }

        if (q.contains("file") || q.contains("upload") || q.contains("download")) {
            return """
                **File Operations Help**
                
                Handle file uploads and downloads:
                
                **File Upload:**
                ```java
                WebElement uploadInput = driver.findElement(By.id("file-upload"));
                uploadInput.sendKeys("/path/to/file.pdf");
                ```
                
                **File Download:**
                ```java
                // Set download directory
                ChromeOptions options = new ChromeOptions();
                options.addArguments("--download-directory=/downloads");
                
                // Click download
                driver.findElement(By.id("download-btn")).click();
                Thread.sleep(5000);  // Wait for download
                
                // Verify file exists
                File downloaded = new File("/downloads/file.pdf");
                assertTrue(downloaded.exists());
                ```
                
                **Read File Content:**
                ```java
                String content = Files.readString(Path.of("file.txt"));
                List<String> lines = Files.readAllLines(Path.of("file.txt"));
                ```
                
                What file operation do you need?""";
        }

        if (q.contains("auth") || q.contains("authentication") || q.contains("login") || q.contains("token")) {
            return """
                **Authentication Testing Help**
                
                Test authentication flows:
                
                **Session-Based Auth:**
                ```java
                // Login and store session
                driver.get("https://example.com/login");
                driver.findElement(By.id("username")).sendKeys("admin");
                driver.findElement(By.id("password")).sendKeys("password");
                driver.findElement(By.id("login-btn")).click();
                
                // Cookie is automatically stored
                ```
                
                **Token-Based Auth (JWT):**
                ```java
                // Get token
                Response response = given()
                    .contentType(ContentType.JSON)
                    .body("{\"username\":\"admin\",\"password\":\"pass\"}")
                .when()
                    .post("/api/auth/login");
                
                String token = response.jsonPath().getString("token");
                
                // Use token in subsequent requests
                given()
                    .header("Authorization", "Bearer " + token)
                .when()
                    .get("/api/protected")
                .then()
                    .statusCode(200);
                ```
                
                **OAuth 2.0:**
                ```java
                // Authorization Code Flow
                String authUrl = "https://provider.com/oauth/authorize?client_id=xxx&redirect_uri=xxx";
                driver.get(authUrl);
                // User grants permission
                // Redirect back with code
                // Exchange code for token
                ```
                
                What authentication scenario do you need?""";
        }

        if (q.contains("framework") || q.contains("integrated") || q.contains("list")) {
            return """
                **Integrated Frameworks Overview**
                
                This Unified QA Runner integrates 18+ testing frameworks:
                
                **Web Automation:**
                - Selenium WebDriver - Browser automation
                - Playwright - Modern browser automation
                - Cypress - JavaScript testing
                
                **API Testing:**
                - REST Assured - REST API testing
                - HTTP Client - Low-level HTTP requests
                
                **BDD:**
                - Cucumber - Behavior-driven development
                - JBehave - BDD framework
                
                **Unit Testing:**
                - JUnit 5 - Java unit testing
                - TestNG - Advanced testing framework
                
                **Performance:**
                - JMeter - Load and performance testing
                
                **Mobile:**
                - Appium - Mobile automation
                
                **Database:**
                - JDBC - Database testing
                
                **Reporting:**
                - Allure - Test reports
                - Extent Reports - HTML reports
                
                **Build & CI/CD:**
                - Maven - Build automation
                - GitHub Actions - CI/CD
                
                Which framework would you like help with?""";
        }

        if (q.contains("help") || q.contains("what can you") || q.contains("how do i")) {
            return """
                **AI Assistant - How Can I Help?**
                
                I can help you with:
                
                **Framework Questions:**
                - Cucumber BDD features and step definitions
                - Selenium WebDriver automation
                - Playwright browser testing
                - REST Assured API testing
                - JUnit 5 and TestNG
                - JMeter performance testing
                
                **Coding Help:**
                - Java code patterns and utilities
                - Page Object design pattern
                - Assertions and verification
                - Waits and timeouts
                - Exception handling
                
                **Testing Concepts:**
                - Test case design
                - Test planning strategies
                - Data-driven testing
                - Test reporting
                
                **DevOps:**
                - Maven build commands
                - Git version control
                - CI/CD pipeline configuration
                - Docker containers
                
                **Other:**
                - Database testing with SQL
                - JSON/XML parsing
                - File operations
                - Authentication testing
                
                Just ask your question and I'll provide code examples and best practices!""";
        }

        // Default response for unrecognized queries
        return """
            **I can help with testing and automation!**
            
            I'm not sure I understood your question completely, but I can help with:
            
            - **Framework questions**: Cucumber, Selenium, Playwright, REST Assured, JUnit, TestNG, JMeter
            - **Coding help**: Java patterns, Page Object, assertions, waits
            - **Testing concepts**: Test design, planning, data-driven testing
            - **DevOps**: Maven, Git, CI/CD, Docker
            - **Database**: SQL queries and testing
            - **API testing**: REST endpoints and authentication
            
            Could you rephrase your question or be more specific about what you need help with?
            
            Try asking:
            - "How do I write a Cucumber feature?"
            - "Help with Selenium waits"
            - "REST Assured POST request example"
            - "Page Object pattern example"
            - "What frameworks are integrated?"
            """;
    }

    // ==========================================
    // Ticket Management
    // ==========================================

    private final java.util.List<Map<String, Object>> tickets = new java.util.ArrayList<>();
    private int nextTicketNum = 1;

    private java.util.List<Map<String, Object>> getTickets() {
        return new java.util.ArrayList<>(tickets);
    }

    @SuppressWarnings("unchecked")
    private void handleCreateTicket(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            sendError(exchange, 400, "Request body is too large.");
            return;
        }
        Object parsed = JsonParser.parse(new String(body, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> values)) {
            sendError(exchange, 400, "Request must be a JSON object.");
            return;
        }
        String title = stringField(values, "title");
        String description = stringField(values, "description");
        if (title == null || title.isBlank()) {
            sendError(exchange, 400, "Title is required.");
            return;
        }
        if (description == null || description.isBlank()) {
            sendError(exchange, 400, "Description is required.");
            return;
        }
        String category = (String) values.get("category");
        if (category == null || category.isBlank()) category = "other";
        String priority = (String) values.get("priority");
        if (priority == null || priority.isBlank()) priority = "medium";
        String steps = (String) values.get("steps");
        if (steps == null) steps = "";

        String ticketId = String.format("TKT-%03d", nextTicketNum++);
        String now = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        Map<String, Object> ticket = new java.util.LinkedHashMap<>();
        ticket.put("ticketId", ticketId);
        ticket.put("title", title);
        ticket.put("description", description);
        ticket.put("category", category);
        ticket.put("priority", priority);
        ticket.put("steps", steps);
        ticket.put("status", "open");
        ticket.put("createdAt", now);
        ticket.put("response", null);
        tickets.add(ticket);

        sendJson(exchange, 201, Map.of("ticketId", ticketId, "message", "Ticket created successfully."));
    }

}
