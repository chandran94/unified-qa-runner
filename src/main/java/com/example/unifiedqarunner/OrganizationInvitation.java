package com.example.unifiedqarunner;

import java.time.Instant;
import java.util.UUID;

record OrganizationInvitation(UUID invitationId, UUID organizationId, String invitedEmail,
                              String invitedUserId, OrganizationRole intendedRole, String tokenHash,
                              Instant expiresAt, String invitedBy, InvitationStatus status,
                              Instant createdAt, Instant acceptedAt) {
    OrganizationInvitation {
        invitationId = OrganizationValidation.notNull(invitationId, "invitationId");
        organizationId = OrganizationValidation.notNull(organizationId, "organizationId");
        invitedEmail = OrganizationValidation.email(invitedEmail);
        invitedUserId = OrganizationValidation.optionalUserId(invitedUserId, "invitedUserId");
        intendedRole = OrganizationValidation.notNull(intendedRole, "intendedRole");
        if (intendedRole == OrganizationRole.OWNER) throw new IllegalArgumentException("Invitations cannot assign the owner role.");
        tokenHash = OrganizationValidation.tokenHash(tokenHash);
        expiresAt = OrganizationValidation.timestamp(expiresAt, "expiresAt");
        invitedBy = OrganizationValidation.userId(invitedBy, "invitedBy");
        status = OrganizationValidation.notNull(status, "status");
        createdAt = OrganizationValidation.timestamp(createdAt, "createdAt");
        if (expiresAt.isBefore(createdAt)) throw new IllegalArgumentException("expiresAt cannot precede createdAt.");
        if (status == InvitationStatus.ACCEPTED && acceptedAt == null) throw new IllegalArgumentException("Accepted invitations require acceptedAt.");
        if (status != InvitationStatus.ACCEPTED && acceptedAt != null) throw new IllegalArgumentException("Only accepted invitations may have acceptedAt.");
        if (acceptedAt != null && acceptedAt.isBefore(createdAt)) throw new IllegalArgumentException("acceptedAt cannot precede createdAt.");
    }
}
