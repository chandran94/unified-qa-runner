@ui @practiceForm @noScreenshot @manual
Feature: DemoQA Practice Form
  As a DemoQA visitor
  I want to submit the Practice Form
  So that I can update and verify student registration data

  Scenario Outline: Submit the Practice Form with student data
    Given I open the Practice Form from the Forms category
    When I enter and submit practice data "<firstName>" "<lastName>" "<email>" "<mobile>" "<subject>" "<hobby>" "<address>" "<state>" "<city>"
    Then the submitted practice form shows the entered data

    Examples:
      | firstName | lastName | email                       | mobile     | subject | hobby   | address            | state   | city   |
      | Alex      | Tester   | alex.tester@example.com     | 9876543210 | Maths   | Reading | 10 Practice Street | NCR     | Delhi  |
#      | Jamie     | Student  | jamie.student@example.com   | 9876543211 | Physics | Music   | 22 Test Avenue     | Haryana | Karnal |
