@ui
Feature: Screenshot demo - verifies missing elements safely

  @screenshotDemo
  Scenario: Missing element is detected without failing the suite
    Given I navigate to DemoQA home page
    When I look for a missing element on the page
    Then the element should be absent
