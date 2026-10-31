@amazon @ui @manual
Feature: Amazon storefront search and cart
  As a shopper using Amazon
  I want to search for a product and add it to my cart
  So that I can verify the storefront journey without placing an order

  Scenario: Search suggestions, product data, links, and frame context
    Given I open the Amazon storefront
    When I search Amazon for "wireless mouse"
    Then Amazon displays search suggestions and product data with links
    And the Amazon page remains usable after checking its frames

  Scenario: Add a search result to the cart from a separate tab
    Given I open the Amazon storefront
    When I search Amazon for "wireless mouse"
    And I open the first search result in a new tab
    Then the product details are displayed in the new tab
    When I add that product to the cart
    Then the cart contains the selected product

  @amazonLogin @noScreenshot
  Scenario: Sign in with a configured Amazon account
    Given I open the Amazon storefront
    When I sign in to Amazon with configured credentials
    Then Amazon shows my signed-in account
