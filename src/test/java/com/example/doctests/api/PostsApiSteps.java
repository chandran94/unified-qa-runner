package com.example.doctests.api;

import com.example.doctests.support.ApiClient;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.response.Response;
import org.junit.jupiter.api.Assertions;

public class PostsApiSteps {
    private ApiClient apiClient;
    private Response response;
    private String submittedTitle;
    private String submittedBody;
    private int submittedUserId;

    @Given("the posts API is available")
    public void postsApiIsAvailable() {
        apiClient = ApiClient.fromConfiguration();
    }

    @When("I retrieve post {int}")
    public void retrievePost(int postId) {
        response = apiClient.get("/posts/" + postId);
    }

    @When("I create a post with title {string} and body {string} for user {int}")
    public void createPost(String title, String body, int userId) {
        submittedTitle = title;
        submittedBody = body;
        submittedUserId = userId;
        response = apiClient.post("/posts", new PostRequest(title, body, userId));
    }

    @Then("the API response status is {int}")
    public void responseStatusIs(int statusCode) {
        Assertions.assertEquals(statusCode, response.statusCode());
    }

    @Then("the response contains post {int} for user {int}")
    public void responseContainsPost(int postId, int userId) {
        Assertions.assertEquals(postId, response.jsonPath().getInt("id"));
        Assertions.assertEquals(userId, response.jsonPath().getInt("userId"));
    }

    @Then("the response contains a title and body")
    public void responseContainsTitleAndBody() {
        Assertions.assertFalse(response.jsonPath().getString("title").isBlank(),
                "Post title should not be blank");
        Assertions.assertFalse(response.jsonPath().getString("body").isBlank(),
                "Post body should not be blank");
    }

    @Then("the response contains the created post with its submitted details")
    public void responseContainsCreatedPost() {
        Assertions.assertEquals(submittedTitle, response.jsonPath().getString("title"));
        Assertions.assertEquals(submittedBody, response.jsonPath().getString("body"));
        Assertions.assertEquals(submittedUserId, response.jsonPath().getInt("userId"));
        Assertions.assertTrue(response.jsonPath().getInt("id") > 0,
                "Created post should have a positive ID");
    }

    private record PostRequest(String title, String body, int userId) {
    }
}
