@ui @manual
Feature: DemoQA home page categories
  As a DemoQA visitor
  I want to open a main category
  So that I can access its page

  Scenario Outline: Open a main category
    Given I open the DemoQA home page
    When I open the "<category>" category
    Then the "<category>" page is opened

    Examples:
      | category                 |
      | Elements                 |
#      | Forms                    |
#      | Alerts, Frame & Windows  |
#      | Widgets                  |
#      | Interactions             |
