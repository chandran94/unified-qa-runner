package com.example.unifiedqarunner;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable ownership envelope for queued work. It intentionally contains no
 * session identifiers, bearer tokens, passwords, OTPs, or other credentials.
 * The current runner keeps jobs in memory; this value is the durable-shaped
 * boundary that must be serialized unchanged if a persistent queue is added.
 */
record TenantJobContext(String jobId, UUID organizationId, String projectId,
                       String requesterUserId, String jobType,
                       String resourceType, String resourceId,
                       String createdAt, String authorizationPolicy) {
    TenantJobContext {
        if (jobId == null || jobId.isBlank()) throw new IllegalArgumentException("Job id is required.");
        if (requesterUserId == null || requesterUserId.isBlank()) throw new IllegalArgumentException("Requester is required.");
        if (jobType == null || jobType.isBlank()) throw new IllegalArgumentException("Job type is required.");
        if (resourceType == null || resourceType.isBlank()) throw new IllegalArgumentException("Resource type is required.");
        if (resourceId == null || resourceId.isBlank()) throw new IllegalArgumentException("Resource id is required.");
        if (createdAt == null || createdAt.isBlank()) createdAt = Instant.now().toString();
        if (authorizationPolicy == null || authorizationPolicy.isBlank()) {
            throw new IllegalArgumentException("Authorization policy is required.");
        }
        if (projectId != null && projectId.isBlank()) projectId = null;
        if (projectId != null && organizationId == null) {
            throw new IllegalArgumentException("A project job requires an organization.");
        }
    }

    boolean platformJob() {
        return organizationId == null && projectId == null;
    }
}
