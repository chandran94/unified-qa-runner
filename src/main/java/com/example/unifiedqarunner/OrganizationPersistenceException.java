package com.example.unifiedqarunner;

final class OrganizationPersistenceException extends IllegalStateException {
    OrganizationPersistenceException(String message) { super(message); }
    OrganizationPersistenceException(String message, Throwable cause) { super(message, cause); }
}
