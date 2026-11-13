package com.example.unifiedqarunner;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Reusable fail-closed checks for future route integration. */
final class TenantAuthorizationPrimitives {
    private TenantAuthorizationPrimitives() { }

    static void requireOrganizationMembership(TenantContext context, UUID organizationId) {
        if (context == null || context.membershipStatus() != MembershipStatus.ACTIVE
                || !Objects.equals(context.organizationId(), organizationId)) throw TenantAuthorizationException.forbidden();
    }

    static void requireOrganizationRole(TenantContext context, OrganizationRole... roles) {
        if (context == null || context.membershipStatus() != MembershipStatus.ACTIVE
                || roles == null || Arrays.stream(roles).noneMatch(role -> role == context.organizationRole())) throw TenantAuthorizationException.forbidden();
    }

    static void requireProjectAccess(TenantContext context, String projectId, String domain, String action) {
        if (context == null || !Objects.equals(context.projectId(), projectId) || !context.hasPermission(domain, action)) {
            throw TenantAuthorizationException.forbidden();
        }
    }

    static void requireResourceOwnership(TenantContext context, ProjectOwnershipManager ownership,
                                         OrganizationStore organizations, String resourceType, String resourceId,
                                         String domain, String action) throws IOException {
        String projectId = ownership.projectId(resourceType, resourceId);
        if (projectId.isBlank()) throw TenantAuthorizationException.notFound();
        ProjectOrganizationOwnership project = organizations.findProjectOwnership(projectId);
        if (context == null || project == null || !project.organizationId().equals(context.organizationId())) throw TenantAuthorizationException.forbidden();
        requireProjectAccess(context, projectId, domain, action);
    }

    static void requireTenantScopedPermission(TenantContext context, String domain, String action) {
        if (context == null || !context.hasPermission(domain, action)) throw TenantAuthorizationException.forbidden();
    }
}
