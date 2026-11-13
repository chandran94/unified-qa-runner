package com.example.unifiedqarunner;

import java.util.Objects;

record TenantPermission(String domain, String action) {
    TenantPermission {
        if (domain == null || domain.isBlank() || action == null || action.isBlank()) {
            throw new IllegalArgumentException("Tenant permission domain and action are required.");
        }
        domain = domain.trim();
        action = action.trim();
    }
}
