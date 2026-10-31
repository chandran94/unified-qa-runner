@api
Feature: Posts API
  As an API consumer
  I want to retrieve and create posts
  So that I can verify the posts service responses

  Scenario: Retrieve a post
    Given the posts API is available
    When I retrieve post 1
    Then the API response status is 200
    And the response contains post 1 for user 1
    And the response contains a title and body

  Scenario: Create a post
    Given the posts API is available
    When I create a post with title "Cucumber API test" and body "Created from a Cucumber API test" for user 1
    Then the API response status is 201
    And the response contains the created post with its submitted details
