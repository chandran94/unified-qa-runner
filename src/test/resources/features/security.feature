@security
Feature: API client security
  The API client must protect configured credentials and avoid exposing
  sensitive details when reporting failed requests.

  Scenario: Reject credentials sent over plain HTTP
    Given API credentials are configured for "http://api.example.test"
    When I create the API client
    Then the API configuration is rejected without revealing credentials

  Scenario: Accept credentials only for HTTPS requests
    Given API credentials are configured for "https://api.example.test"
    When I prepare an authenticated API request
    Then the request uses HTTPS and keeps credentials out of its URL

  Scenario: Do not send authentication when credentials are absent
    Given the API client has no credentials
    When I prepare an API request
    Then the request has no Authorization header

  Scenario: Keep sensitive error response fields out of safe summaries
    Given the local API returns an error containing a synthetic national ID
    When I create a safe error summary
    Then the summary reports the status without exposing the national ID

  Scenario: Reject invalid TLS certificates
    Given the API server presents an untrusted TLS certificate
    When I send an authenticated API request to the untrusted server
    Then the HTTPS connection is rejected
    And credentials are not transmitted

  Scenario: Prevent credential leakage across redirects
    Given API credentials are configured for a trusted HTTPS host
    When the trusted server redirects to an untrusted HTTPS host
    Then credentials are not forwarded to the untrusted host
    When the trusted server redirects to an HTTP destination
    Then the HTTP downgrade destination does not receive credentials
    When the trusted server redirects repeatedly
    Then the client stops after the configured redirect limit
    And the error summary contains no credentials

  Scenario: Do not expose credentials in logs
    Given API credentials are configured
    When an API request fails
    Then application logs do not contain the credentials

  Scenario: Reject malformed API configuration
    Given API credentials are configured for an invalid API URL
    When I create the API client
    Then the configuration is rejected safely
    And the error message does not reveal credentials

  Scenario: Handle unauthorized API responses
    Given the API server returns HTTP status 401
    When I create a safe error summary
    Then the summary reports status 401
    And the summary does not expose credentials

  Scenario: Handle forbidden API responses
    Given the API server returns HTTP status 403
    When I create a safe error summary
    Then the summary reports status 403
    And the summary does not expose sensitive response fields

  Scenario: Reject credentials in URL parameters
    Given API credentials are configured
    When I prepare an authenticated API request
    Then credentials are not included in the URL
    And credentials are sent only through the intended authentication mechanism

  Scenario: Do not expose personal data in error logs
    Given the API returns an error containing a synthetic national ID
    When the error is logged and summarized
    Then the national ID is absent from the logs
    And the test token is absent from the logs
    And the national ID is absent from the safe summary

  Scenario: Reject empty or whitespace-only credentials
    Given the API client has an empty or whitespace-only token
    When I prepare an authenticated API request
    Then the request has no Authorization header

  Scenario: Reject unsupported URL schemes
    Given API credentials are configured for "ftp://api.example.test"
    When I create the API client
    Then the configuration is rejected safely
    And the error message does not reveal credentials

  Scenario: Do not expose secrets in exception messages
    Given API credentials are configured
    And the API request fails with an exception containing a secret
    When I create a safe error summary
    Then the summary does not expose the secret
    And exception logs and monitoring events do not expose sensitive data

  Scenario: Do not retry unsafe requests automatically
    Given an authenticated API request fails
    When the client considers retrying the request
    Then the retry follows the configured safe retry policy
    And credentials are sent only to the approved destination

  Scenario: Prevent sensitive data in request URLs
    Given an API request contains synthetic personal information
    When I prepare the request
    Then sensitive personal information is not included in the URL

  Scenario: Handle unexpected server responses safely
    Given the API returns a malformed or unexpected error response
    When I create a safe error summary
    Then the client handles the response without crashing
    And the summary does not expose sensitive response fields

  Scenario: Do not expose uploaded document contents in error logs
    Given a document verification request contains a synthetic test document
    When the API returns an error
    Then application logs do not contain the document contents
    And the safe error summary contains no sensitive document fields

  Scenario: Reject TLS certificates with hostname mismatch
    Given the API server presents a trusted certificate for a different hostname
    When I send an authenticated API request
    Then the HTTPS connection is rejected
    And credentials are not transmitted

  Scenario: Prevent access to unapproved API destinations
    Given API credentials are configured for an unapproved host
    When I create the API client
    Then the API destination is rejected by the host allowlist
    And credentials are not transmitted

  Scenario: Handle API timeouts without exposing secrets
    Given API credentials are configured
    And the API server does not respond before the timeout
    When I send an authenticated API request
    Then the request fails safely
    And the error message does not expose credentials
    And application logs do not contain credentials

  Scenario: Do not log sensitive request or response bodies
    Given a request contains a synthetic national ID and document data
    When the API request fails with an error response containing sensitive data
    Then application logs do not contain the sensitive request data
    And application logs do not contain the sensitive response data
    And the safe summary and monitoring event do not contain sensitive data

  Scenario: Limit oversized API error responses
    Given the API server returns an oversized error response
    When I create a safe error summary
    Then the response is handled within configured size limits
    And the summary does not expose sensitive response fields

  Scenario: Do not reuse rotated credentials
    Given the API client's credentials have been rotated
    When I prepare a new authenticated API request
    Then the request uses the current credentials
    And the request does not use the old credentials

  Scenario: Reject prohibited API destinations
    Given API credentials are configured
    And the destination resolves to a prohibited network address
    When I send an authenticated API request
    Then the request is rejected safely
    And credentials are not transmitted to the prohibited destination

  Scenario: Isolate credentials across concurrent requests
    Given two API clients have different credentials
    When both clients send requests concurrently
    Then each request uses only its configured credentials
    And neither request exposes the other client's credentials

  Scenario: Reject oversized document uploads
    Given a synthetic document exceeds the configured upload limit
    When I submit the document for verification
    Then the request is rejected safely
    And document contents are not written to application logs

  Scenario: Redact sensitive data from monitoring
    Given an API request contains a synthetic national ID and test token
    When the request fails and monitoring events are recorded
    Then monitoring events do not contain the national ID
    And monitoring events do not contain the test token

  # Deferred: activate these scenarios when the application API and document
  # verification build exposes the protected-resource and storage boundaries.
  # Scenario: Reject unauthorized access to protected API resources
  #   Given a protected API endpoint requires valid authorization
  #   And the request has missing or insufficient credentials
  #   When I send the API request
  #   Then access is denied
  #   And the response does not expose protected data
  #
  # Scenario: Prevent replay of sensitive verification operations
  #   Given a document verification operation has already been processed
  #   When the same operation is submitted again
  #   Then the configured replay or idempotency policy is enforced
  #   And duplicate side effects are prevented where required
  #
  # Scenario: Clean up temporary document files after failures
  #   Given a synthetic document is stored temporarily for verification
  #   When document verification fails
  #   Then temporary files are cleaned up according to policy
  #   And unauthorized users cannot access the document
  #
  # Scenario: Redact sensitive data from test reports
  #   Given a security test fails using synthetic credentials and personal data
  #   When the test report is generated
  #   Then credentials are absent from the report
  #   And sensitive document fields are absent from the report
