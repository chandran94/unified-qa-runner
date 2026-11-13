package com.example.unifiedqarunner;

/** Fail-closed authorization result that can be mapped to HTTP without coupling this boundary to RunnerServer. */
final class TenantAuthorizationException extends RuntimeException {
    private final int statusCode;

    private TenantAuthorizationException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    static TenantAuthorizationException unauthenticated() {
        return new TenantAuthorizationException(401, "Authentication is required.");
    }

    static TenantAuthorizationException forbidden() {
        return new TenantAuthorizationException(403, "The requested tenant resource is not authorized.");
    }

    static TenantAuthorizationException notFound() {
        return new TenantAuthorizationException(404, "The requested resource was not found.");
    }

    int statusCode() { return statusCode; }
}
