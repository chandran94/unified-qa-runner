@ui @elements @manual
Feature: DemoQA Elements tools
  As a DemoQA visitor
  I want to use each tool in the Elements section
  So that I can verify its basic functionality

  Scenario Outline: Use an Elements tool
    Given I open Elements from the DemoQA home page
    When I use the "<tool>" tool
    Then the "<expected result>" result is shown

#    @elementTextBox
#    Examples: Text Box
#      | tool                  | expected result        |
#      | Text Box              | DemoQA Test User       |

    @elementCheckBox
    Examples: Check Box
      | tool                  | expected result        |
      | Check Box             | Home                   |
#
#    @elementRadioButton
#    Examples: Radio Button
#      | tool                  | expected result        |
#      | Radio Button          | Yes                    |
#
#    @elementWebTables
#    Examples: Web Tables
#      | tool                  | expected result        |
#      | Web Tables            | elements.test@example.com |
#
#    @elementButtons
#    Examples: Buttons
#      | tool                  | expected result        |
#      | Buttons               | right click            |
#
#    @elementLinks
#    Examples: Links
#      | tool                  | expected result        |
#      | Links                 | demoqa.com             |
#
#    @elementBrokenLinks
#    Examples: Broken Links
#      | tool                  | expected result        |
#      | Broken Links - Images | status_codes/500       |
#
#    @elementUploadDownload
#    Examples: Upload and Download
#      | tool                  | expected result        |
#      | Upload and Download   | README.md              |
#
#    @elementDynamicProperties
#    Examples: Dynamic Properties
#      | tool                  | expected result        |
#      | Dynamic Properties    | enabled and visible    |
