package com.example.unifiedqarunner;

import java.util.Set;
import java.util.UUID;

/** Immutable request-scoped tenant decision. It contains no credentials or mutable authorization state. */
record TenantContext(String authenticatedUserId, UUID organizationId, UUID membershipId,
                     OrganizationRole organizationRole, MembershipStatus membershipStatus,
                     String projectId, Set<TenantPermission> projectPermissions) {
    TenantContext {
        if (authenticatedUserId == null || authenticatedUserId.isBlank()) throw new IllegalArgumentException("Authenticated user is required.");
        if (organizationId == null || membershipId == null) throw new IllegalArgumentException("Tenant identifiers are required.");
        if (organizationRole == null || membershipStatus != MembershipStatus.ACTIVE) throw new IllegalArgumentException("An active organization membership is required.");
        if (projectId != null && projectId.isBlank()) projectId = null;
        if (projectPermissions == null) projectPermissions = Set.of();
        projectPermissions = Set.copyOf(projectPermissions);
    }

    boolean hasPermission(String domain, String action) {
        return projectPermissions.contains(new TenantPermission(domain, action));
    }
}
