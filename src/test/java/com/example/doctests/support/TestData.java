package com.example.doctests.support;

public final class TestData {
    public static final TextBox TEXT_BOX = new TextBox(
            "DemoQA Test User",
            "demoqa.test@example.com",
            "10 Current Street",
            "20 Permanent Avenue");

    public static final TableRecord TABLE_RECORD = new TableRecord(
            "DemoQA",
            "Automation",
            "elements.test@example.com",
            "30",
            "50000",
            "Testing");

    public static final String UPLOAD_FILE_NAME = "README.md";

    private TestData() {
    }

    public record TextBox(String name, String email, String currentAddress, String permanentAddress) {
    }

    public record TableRecord(
            String firstName,
            String lastName,
            String email,
            String age,
            String salary,
            String department) {
    }

    public record PracticeForm(
            String firstName,
            String lastName,
            String email,
            String mobile,
            String subject,
            String hobby,
            String address,
            String state,
            String city) {
    }
}
