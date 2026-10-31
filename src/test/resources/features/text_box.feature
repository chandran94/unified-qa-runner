@ui @noScreenshot @manual
Feature: DemoQA Text Box form
  As a DemoQA visitor
  I want to submit valid contact details
  So that the page displays the submitted values

  Scenario: Submit the form
    Given I open the DemoQA Text Box page
    When I submit valid text box details
    Then the submitted details are displayed
