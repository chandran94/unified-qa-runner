package com.example.unifiedqarunner;

import java.time.Instant;
import java.util.UUID;

record Organization(UUID organizationId, String organizationName, String organizationSlug,
                    String ownerUserId, OrganizationStatus status, Instant createdAt,
                    Instant updatedAt, int schemaVersion) {
    Organization {
        organizationId = OrganizationValidation.notNull(organizationId, "organizationId");
        organizationName = OrganizationValidation.requiredText(organizationName, "organizationName", 160);
        organizationSlug = OrganizationValidation.slug(organizationSlug);
        ownerUserId = OrganizationValidation.userId(ownerUserId, "ownerUserId");
        status = OrganizationValidation.notNull(status, "status");
        createdAt = OrganizationValidation.timestamp(createdAt, "createdAt");
        updatedAt = OrganizationValidation.timestamp(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt cannot precede createdAt.");
        if (schemaVersion < 1) throw new IllegalArgumentException("schemaVersion must be positive.");
    }
}
