package com.enterprise.admin.dto.settings;

/** The non-sensitive part of organization settings that every signed-in user may see. */
public record WorkspaceInfo(String organizationName) {
}
