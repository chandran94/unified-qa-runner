package com.example.doctests.security;

import java.util.List;

final class SensitiveTestFixture {
    static final String USERNAME = "security-test-user";
    static final String TOKEN = "synthetic-test-token-only";
    static final String NATIONAL_ID = "TEST-NATIONAL-ID-ONLY";
    static final String DOCUMENT = "SYNTHETIC-DOCUMENT-CONTENTS-ONLY";
    static final String EXCEPTION_SECRET = "synthetic-exception-secret-only";

    private SensitiveTestFixture() {
    }

    static List<String> values() {
        return List.of(USERNAME, TOKEN, NATIONAL_ID, DOCUMENT, EXCEPTION_SECRET);
    }
}
