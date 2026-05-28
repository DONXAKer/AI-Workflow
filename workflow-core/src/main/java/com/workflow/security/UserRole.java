package com.workflow.security;

/**
 * Fixed role set. The first four are tenant-scoped roles; {@link #PLATFORM_ADMIN} is
 * platform staff.
 *
 * <ul>
 *   <li>{@link #VIEWER} — read-only access to runs, pipelines, integrations.</li>
 *   <li>{@link #OPERATOR} — can approve/edit/skip non-prod stages, return runs, start runs.</li>
 *   <li>{@link #RELEASE_MANAGER} — operator + prod approval + rollback + kill switch per project.</li>
 *   <li>{@link #ADMIN} — release_manager + integration CRUD, skills CRUD, system settings.
 *       For a customer account this is the account owner (self-registered).</li>
 *   <li>{@link #PLATFORM_ADMIN} — platform support staff. Sees across all accounts
 *       ({@code TenantContextFilter} flags them see-all) and is also granted
 *       {@code ROLE_ADMIN} so existing admin-gated endpoints keep working.</li>
 * </ul>
 */
public enum UserRole {
    VIEWER,
    OPERATOR,
    RELEASE_MANAGER,
    ADMIN,
    PLATFORM_ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }
}
