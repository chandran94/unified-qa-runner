# Unified QA Runner

A local web application for running project-owned Selenium and
RestAssured Java test classes, Cucumber features, and Newman collections. It
writes an HTML report and CSV aggregate summary for every run under
`target/runner-reports/<run-id>/`.

## Start the local web runner

Prerequisites:

- Windows 10/11
- JDK 17 or later
- Maven 3.8 or later on `PATH`
- Node.js 18 or later for Newman
- Chrome or Firefox for the selected Selenium tests

Build and launch from the project folder in PowerShell:

```powershell
.\scripts\run-qa-runner.ps1
```

To build a double-clickable Windows app instead, run `mvn -DskipTests package`.
Then open `target\qa-runner-app\Unified QA Runner\Unified QA Runner.exe`.
This app starts the local runner and opens the live dashboard automatically;
you do not need to use the PowerShell launcher. If the app cannot find the test
project beside itself, it asks you to select the project folder containing
`pom.xml`. To give the app to another Windows user, share the entire
`target\qa-runner-app\Unified QA Runner` folder, not only the `.exe`.
Recipients also need the test project files, Maven 3.8+ on `PATH`, and Node.js
18+ on `PATH` to run Newman collections. The app bundles its Java runtime.
Use **Exit runner** in the dashboard to close the app.

The runner starts an HTTP server bound only to `127.0.0.1` on an available
port, then opens the live dashboard in your browser. **Do not open
`index.html` from the starter ZIP with a `file:///` URL**; that file is a
standalone prototype and cannot discover project tests or run them. Start the
app using the Windows app above or the optional script; the dashboard then
lists the runnable Cucumber feature files and lets you select one to run. The
equivalent manual launch is `java -jar
target/unified-qa-runner-1.0-SNAPSHOT.jar`. The dashboard’s **Run** button
executes the selected test; the launcher itself does not run tests.

Install Newman once if you want to run collections:

```powershell
npm install
```

For a local end-to-end Newman smoke run, start the included fixture in a
separate PowerShell window, then choose `local-health.postman_collection.json`
in the runner with `http://127.0.0.1:8080` as the target:

```powershell
.\scripts\local-health-server.ps1
```

The dashboard discovers Java test classes, Cucumber feature files, and
`*collection*.json` Postman collections under `src/test/resources/api`. Choose a
test, enter its target URL, then press **Run**. Newman collections may use `{{baseUrl}}` in request URLs; pre-request and test
scripts are rejected so a collection cannot make unreviewed requests outside
its validated request list. Every request must use the selected scheme, host,
and port. Newman runs with redirects disabled.

`RunnerSeleniumSmokeIT` is a browser smoke test included for the runner. It is
discoverable in the desktop app but is not part of the default `mvn test` suite.

Targets on the local machine (`localhost` or loopback addresses) work by
default. A remote target requires the explicit authorization checkbox, must
resolve only to public addresses, and must match the host configured in the
collection. Private, link-local, and special-use destinations are blocked.
Only choose test code and targets you own or are authorized to test. Existing
test classes/features are executable project code and can have side effects;
review them before running. Remote Selenium pages may load resources or follow
links independently of the target URL check, so use an isolated, authorized
test environment.

The runner deliberately omits the opt-in Amazon features and the manual
`security_ui.feature` scenarios from discovery. Those flows require special
authorization or application integration. The existing API client also retains
its exact-host and address validation.

Reports contain aggregate counts only; test names, exception text, response
bodies, and credentials are not copied into the reports. Console output is
shown during the run with common credential patterns redacted.

A Java 17 Maven project using Selenium WebDriver with Cucumber for end-to-end UI
testing of [DemoQA](https://demoqa.com/). A reusable Rest Assured API client is
also available to Java tests. The Text Box feature submits valid form data in
Chrome and Firefox, and the Elements feature exercises all nine
tools in separate Chrome scenarios after navigating from the homepage. The
home page feature also opens each enabled main category page in its own Chrome
scenario. Cucumber runs the browser-tagged scenarios concurrently (two workers
by default), with an isolated WebDriver per scenario. The worker count is
configurable; `src/test/resources/junit-platform.properties` sets it to two.

## Prerequisites

- JDK 17 or later
- Maven 3.8 or later
- Chrome and/or Firefox installed for the browser scenarios you run

Selenium Manager resolves the matching browser drivers automatically. Copy
`src/test/resources/test.properties.example` to `test.properties` and set
`ui.baseUrl` to the UI test target. The local properties file is Git-ignored;
`UI_BASE_URL` or the legacy `DEMOQA_BASE_URL` environment variable/system
property overrides the file value.

## Run

Run the full cross-browser suite:

```powershell
mvn test
```

Run only one browser or a subset of browsers:

```powershell
mvn test '-Dcucumber.filter.tags=@chrome'
mvn test '-Dcucumber.filter.tags=@firefox'
```

Run the Elements feature in Chrome and Practice Form feature in Firefox together
with two parallel workers:

```powershell
mvn test '-Dcucumber.filter.tags=@elements or @practiceForm' '-Dcucumber.execution.parallel.config.fixed.parallelism=2'
```

The checked-in example configuration runs headlessly by default. To show the
browser windows for the parallel Elements and Practice Form run:

```powershell
mvn test '-DTEST_HEADLESS=false' '-Dcucumber.filter.tags=@elements or @practiceForm' '-Dcucumber.execution.parallel.config.fixed.parallelism=2'
```

Alternatively, set `test.headless=false` in your local
`src/test/resources/test.properties`. The `TEST_HEADLESS` system property or
environment variable overrides that file setting.

Each UI scenario captures its final browser page after execution, whether it
passes or fails. Screenshots are attached to the Cucumber scenario report and
saved as PNG files under `target/screenshots`.
Scenarios tagged `@noScreenshot` skip both screenshot saving and report
attachments. The Text Box and Practice Form features use this tag because their
pages display contact details.

`TEST_BROWSER` (or `-DTEST_BROWSER=firefox`) sets the default browser for any
scenario without a browser tag. Browser tags on the included scenarios select
that specific browser. Cucumber uses two parallel workers by default; the
Practice Form Scenario Outline has two Examples so the same flow can be
exercised concurrently with separate browser sessions. Override the worker
count with `-Dcucumber.execution.parallel.config.fixed.parallelism=2`.

## Project layout

- `src/test/resources/features` — Cucumber scenarios
- `src/test/java/com/example/doctests/steps` — Cucumber step definitions, including the Forms Practice Form flow
- `src/test/java/com/example/doctests/api` — Cucumber step definitions for API tests
- `src/test/java/com/example/doctests/pages` — Selenium page objects and shared `BasePage` browser interaction helpers (waits, elements, forms, mouse actions, alerts, frames, uploads, and windows)
- `src/test/java/com/example/doctests/support` — WebDriver lifecycle, hooks, reusable JDBC `DatabaseClient`, and Java API client helpers

## Amazon storefront scenarios

The opt-in Amazon feature checks search suggestions, product result data and
links, browser frame/window handling, and adding a product to the cart. It does
not sign in or proceed to checkout. Amazon is a live third-party site, so these
scenarios are excluded from the default suite; run them explicitly:

```powershell
mvn test '-Dtest=RunCucumberTest' '-Dcucumber.filter.tags=@amazon'
```

The storefront defaults to `https://www.amazon.com`. Set `AMAZON_BASE_URL` or
`amazon.baseUrl` in `src/test/resources/test.properties` to use the appropriate
Amazon regional site or an authorized test environment. Amazon may present
regional consent, bot checks, or changing page content that prevents live UI
automation from completing.

The separate sign-in scenario requires `AMAZON_EMAIL` and `AMAZON_PASSWORD` as
environment variables and is also opt-in. Do not put account credentials in
source files, `test.properties`, or command-line arguments. The scenario skips
screenshot capture to avoid saving account details. Amazon may require a CAPTCHA
or one-time code; the test reports that challenge rather than attempting to
bypass it.

```powershell
mvn test '-Dtest=RunCucumberTest' '-Dcucumber.filter.tags=@amazonLogin'
```

## API tests with Cucumber

The Posts API scenarios use Cucumber feature syntax with the reusable Rest
Assured `ApiClient`; they run through the same `RunCucumberTest` suite as the UI
features. Configure `api.baseUrl` and the comma-separated, exact `api.allowedHosts`
in `src/test/resources/test.properties`, or override them with `API_BASE_URL` and
`API_ALLOWED_HOSTS` (or matching Java system properties). The configured URL host
must exactly match an approved host; wildcards and suffix matches are not used.
Requests also reject DNS results in loopback, private, link-local, shared, and
special-use address ranges, and automatic redirects remain disabled. The desktop
runner opts the configured API client into loopback only when the chosen target
resolves exclusively to loopback addresses. Local test servers can also be
enabled explicitly with `ApiClient.forTestServer`; do not use that test-only
escape hatch for configured API destinations.

Run only the API scenarios:

```powershell
mvn test '-Dtest=RunCucumberTest' '-Dcucumber.filter.tags=@api'
```

## Data security and privacy tests

Run the local security-focused tests without launching a browser or contacting
the external demo site:

```powershell
mvn test '-Dtest=ApiClientDataProtectionTest,ApiClientPrivacyTest,DatabaseClientPrivacyTest,IdDocumentUploadValidatorTest'
```

Run the API client security scenarios in Cucumber:

```powershell
mvn test '-Dtest=RunCucumberTest' '-Dcucumber.filter.tags=@security'
```

The separate `security_ui.feature` file records twelve frontend security
scenarios under `@uiSecurity`. They are tagged `@manual` and excluded from the
default Cucumber run because this project does not include the target
application's login, session, or document-verification UI/API. The scenarios
require integration with that application and synthetic test accounts/documents
before they can be automated. A browser Back button showing cached content alone
is not treated as protected-data access; verify a fresh server request.

Recommended verification methods when integrating the real application:

| Security check | Scenarios | Recommended method |
| --- | --- | --- |
| Login and upload validation | SEC-UI-01–05 | Selenium Java |
| Browser console errors and displayed errors | SEC-UI-06, SEC-UI-09 | Selenium browser logs or DevTools |
| Network requests and responses | SEC-UI-02, SEC-UI-06, SEC-UI-07, SEC-UI-09, SEC-UI-11, SEC-UI-12 | Chrome DevTools Protocol or an authorized proxy |
| API authorization and server-side status enforcement | SEC-UI-02, SEC-UI-07, SEC-UI-11, SEC-UI-12 | API integration tests |
| Token leakage in server logs | SEC-UI-06, SEC-UI-09 | Log inspection or automated log assertions |
| HTTPS and certificate validation | Existing `@security` API scenarios | TLS integration tests |
| XSS execution | SEC-UI-08 | Selenium checks and OWASP ZAP in an authorized environment |
| Session cookie attributes | SEC-UI-10 | Selenium and browser DevTools |

These methods are recommendations, not implemented step definitions. Network
inspection must account for whether data is necessary and authorized; do not
classify every occurrence in a legitimate request as a leak. Use only harmless
XSS test markers and run scanners only against authorized environments.

These tests check that API credentials require HTTPS, stay in the authorization
header rather than the URL, and are not exposed with synthetic document fields
in request URLs. They also check generic errors for invalid API configuration,
a local error response containing a synthetic national ID, and that
`ApiClient.safeErrorSummary` returns only a generic status summary suitable for
display/logging instead of the response body. This client helper does not alter
or sanitize the raw `Response`; callers must not log or display its body. The
tests also verify that database query values
remain parameterized data while unselected names, national IDs, dates of birth,
and other columns are not returned. The API client does not log rejected
configuration or send requests for it. This project has no application logging
or authentication/session service to test. Use synthetic test values only; do not
submit real personal data to the public DemoQA site or store it in test
reports. These checks cover this test project's helpers, not the security or
privacy of DemoQA or any other application.

The SSRF checks apply to the configured outbound API client only. DNS addresses
are validated before each request, but the HTTP transport does not pin the
validated DNS answer to its socket; enforce outbound network restrictions at the
deployment/firewall layer as defense against DNS rebinding. This repository has
no document-verification service that fetches URLs from uploaded documents, so
that server-side fetch boundary still requires implementation and dedicated
integration tests in the actual service.

### When the ID-document application/API is available

The `IdDocumentUploadValidator` is a test-project preflight helper only. It
allows PDF, PNG, and JPEG files up to 10 MiB and checks their filename extension
and file signature; it does not fully parse files, scan for malware, or protect
an application endpoint. Implement and test the following controls in the
actual application, on the server side:

- **Authentication and authorization:** require authenticated users for
  document operations; enforce document ownership/tenant access for every
  read, update, and delete. Test that another user cannot access a document by
  changing its identifier (IDOR), including through download links.
- **Transport and errors:** require HTTPS for credentials and document traffic;
  return generic client errors without credentials, document contents, or
  internal details, and use non-sensitive correlation IDs for troubleshooting.
- **Upload handling:** enforce allow-listed formats and size limits server-side;
  inspect actual content rather than trusting the filename or supplied MIME
  type; safely parse documents, reject malformed files, and use malware
  scanning where appropriate. Generate storage names server-side and prevent
  path traversal. Add tests for spoofed types, malformed files, boundary sizes,
  and rejected uploads.
- **Storage and lifecycle:** encrypt documents at rest using managed keys,
  restrict storage access to the service, prevent public access, and define
  retention, deletion, and backup policies. Verify these controls in the real
  storage and deployment configuration.
- **Logging and audit:** redact passwords, tokens, national IDs, names, dates of
  birth, and document contents from application logs, errors, and telemetry.
  Record access events only with the minimum necessary non-sensitive metadata.

Add integration tests against the real application and storage service before
considering these controls covered; tests of this repository's helpers alone do
not establish that the application is secure.

## Database tests

`DatabaseClient` connects through JDBC using the same JDBC URL, username, and
password configured for your DBeaver connection. Configure `DB_URL`, `DB_USER`,
and `DB_PASSWORD` as environment variables or Java system properties. Do not
commit database credentials. The database's JDBC driver must also be added as a
Maven dependency; DBeaver's installed driver is not automatically available to
this project.

```java
try (DatabaseClient database = DatabaseClient.connectFromEnvironment()) {
    List<Map<String, Object>> users = database.query(
            "SELECT id, name FROM users WHERE status = ?", "active");
    int updated = database.update(
            "UPDATE users SET status = ? WHERE id = ?", "inactive", 42);
}
```

For custom settings, use
`DatabaseClient.connect(jdbcUrl, username, password)`. Queries and updates use
prepared statements for values; table and column names must be part of the SQL
text. `query` returns rows as maps keyed by column label, and `update` returns
the number of affected rows.
