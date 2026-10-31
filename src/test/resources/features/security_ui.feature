@manual @uiSecurity @noScreenshot
Feature: Frontend security
  UI security scenarios for an application with login and document verification.
  Use synthetic accounts and documents only. These scenarios require the real
  application and corresponding UI/API test support; they are excluded from the
  default automated suite until those are available.

  Scenario: SEC-UI-01 Reject invalid login credentials
    Given I am on the login page
    When I submit invalid username and password credentials
    Then login is rejected
    And the protected dashboard is not displayed
    And the error message does not expose the password

  Scenario: SEC-UI-02 Prevent protected access after logout
    Given I am logged in with a test account and viewing a protected page
    When I log out
    And I use the browser Back button and reload the protected page
    Then a fresh request is denied or redirected to login
    And protected data is not retrieved
    # A cached page shown by Back alone does not fail this scenario.

  Scenario: SEC-UI-03 Accept an allowed test document
    Given an approved synthetic PDF document is available
    When I upload the document through the expected flow
    Then the upload is accepted
    And the UI displays the correct processing status

  Scenario: SEC-UI-04 Reject an unsupported file
    Given an unsupported synthetic file is available
    When I attempt to upload the file
    Then the UI rejects the file with a clear error message
    And the file is not submitted for processing
    # The browser accept attribute is not a substitute for server-side validation.

  Scenario: SEC-UI-05 Reject a document over the configured size limit
    Given the configured document upload size limit is known
    And a synthetic document exceeds that limit
    When I attempt to upload the document
    Then the upload is rejected with a clear validation error
    And the application remains responsive

  Scenario: SEC-UI-06 Avoid exposing sensitive data in public output
    Given a synthetic document verification failure can be triggered
    When I inspect the browser console, network responses, and displayed errors
    Then document identifiers, tokens, and full document contents are not exposed unnecessarily
    And any data in an authorized request is justified by the operation and authorization

  Scenario: SEC-UI-07 Deny direct protected document access when logged out
    Given I am logged out
    When I open a protected document page URL directly
    Then I am redirected to login or shown an access-denied response
    And protected document data is not retrieved

  Scenario: SEC-UI-08 Render user-controlled text without executing it
    Given an authorized test input contains a harmless XSS test marker
    When the document name or verification result is displayed
    Then the input is rendered as text
    And no user-controlled script executes in the browser

  Scenario: SEC-UI-09 Show safe errors for backend failures
    Given the test API is configured to return a controlled HTTP 500 response
    When the UI handles the failed request
    Then the user sees a safe generic error message
    And no stack trace, token, or raw document content is exposed

  Scenario: SEC-UI-10 Apply the required session cookie attributes
    Given I am logged in over HTTPS in an authorized test environment
    When I inspect the session cookie attributes
    Then the Secure attribute is set
    And HttpOnly is set where JavaScript access is not required
    And the SameSite policy matches the application's security requirements

  Scenario: SEC-UI-11 Do not trust client-side verification status
    Given a document verification result is displayed
    When I modify the displayed status in the browser
    Then the backend verification result remains authoritative
    And the modified UI value does not authorize protected actions

  Scenario: SEC-UI-12 Deny access to document details after logout
    Given I am logged in and viewing a document result
    When I log out and request the protected page again
    Then a fresh request for the document details is denied
    And the logged-out session cannot retrieve protected document data
