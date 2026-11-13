package com.example.unifiedqarunner;

import java.time.Instant;
import java.util.UUID;

record OrganizationMembership(UUID membershipId, UUID organizationId, String userId,
                              OrganizationRole organizationRole, MembershipStatus membershipStatus,
                              Instant createdAt, Instant updatedAt) {
    OrganizationMembership {
        membershipId = OrganizationValidation.notNull(membershipId, "membershipId");
        organizationId = OrganizationValidation.notNull(organizationId, "organizationId");
        userId = OrganizationValidation.userId(userId, "userId");
        organizationRole = OrganizationValidation.notNull(organizationRole, "organizationRole");
        membershipStatus = OrganizationValidation.notNull(membershipStatus, "membershipStatus");
        createdAt = OrganizationValidation.timestamp(createdAt, "createdAt");
        updatedAt = OrganizationValidation.timestamp(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt cannot precede createdAt.");
    }
}
