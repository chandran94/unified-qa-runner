@ui
Feature: Screenshot demo - intentionally fails to capture screenshot

  @screenshotDemo
  Scenario: Try to find a non-existent element to trigger screenshot
    Given I navigate to DemoQA home page
    When I look for a missing element on the page
    Then the element should exist
