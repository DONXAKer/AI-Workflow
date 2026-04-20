package com.workflow.security;

/**
 * Fixed role set — 4 tiers from Q25 of the design discussion.
 *
 * <ul>
 *   <li>{@link #VIEWER} — read-only access to runs, pipelines, integrations.</li>
 *   <li>{@link #OPERATOR} — can approve/edit/skip non-prod stages, return runs, start runs.</li>
 *   <li>{@link #RELEASE_MANAGER} — operator + prod approval + rollback + kill switch per project.</li>
 *   <li>{@link #ADMIN} — release_manager + integration CRUD, skills CRUD, system settings.</li>
 * </ul>
 */
public enum UserRole {
    VIEWER,
    OPERATOR,
    RELEASE_MANAGER,
    ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }
}
