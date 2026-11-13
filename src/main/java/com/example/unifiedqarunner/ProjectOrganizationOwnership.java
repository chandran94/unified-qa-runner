package com.example.unifiedqarunner;

import java.time.Instant;
import java.util.UUID;

record ProjectOrganizationOwnership(String projectId, UUID organizationId,
                                    String assignmentProvenance, Instant assignedAt) {
    ProjectOrganizationOwnership {
        projectId = OrganizationValidation.requiredText(projectId, "projectId", 200);
        organizationId = OrganizationValidation.notNull(organizationId, "organizationId");
        assignmentProvenance = OrganizationValidation.requiredText(assignmentProvenance, "assignmentProvenance", 80);
        assignedAt = OrganizationValidation.timestamp(assignedAt, "assignedAt");
    }
}
