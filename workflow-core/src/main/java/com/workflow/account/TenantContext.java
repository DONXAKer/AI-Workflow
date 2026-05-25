package com.workflow.account;

/**
 * Per-request / per-run account scope. The current account's id drives row-level
 * isolation: it is written into tenant-owned entities at {@code @PrePersist} time and read
 * by the Hibernate {@code accountFilter} ({@link TenantFilterResolver}) to scope every SELECT.
 *
 * <p>Mirrors {@link com.workflow.project.ProjectContext}. Plain ThreadLocal — each HTTP
 * request gets a fresh thread (populated by {@link TenantContextFilter}); background run
 * threads snapshot the value and {@code set()} it explicitly (see {@code PipelineRunner}).
 *
 * <p>{@link #setPlatformAdmin} flags platform staff (support): {@link #filterValue()} then
 * returns {@link #NO_SCOPE} so no row is hidden — they see across all accounts.
 */
public final class TenantContext {

    /** Filter value meaning "do not scope" — platform admin, startup initializers, tests. */
    public static final long NO_SCOPE = -1L;

    private static final ThreadLocal<Long> ACCOUNT_ID = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> PLATFORM = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(Long accountId) { ACCOUNT_ID.set(accountId); }

    /** Current account id, or {@code null} when unset. */
    public static Long get() { return ACCOUNT_ID.get(); }

    public static void setPlatformAdmin(boolean platform) { PLATFORM.set(platform); }

    public static boolean isPlatformAdmin() { return Boolean.TRUE.equals(PLATFORM.get()); }

    /**
     * Value handed to the Hibernate {@code accountFilter}: the real account id, or
     * {@link #NO_SCOPE} for platform staff and any contextless code path.
     */
    public static long filterValue() {
        if (isPlatformAdmin()) return NO_SCOPE;
        Long id = ACCOUNT_ID.get();
        return id != null ? id : NO_SCOPE;
    }

    public static void clear() {
        ACCOUNT_ID.remove();
        PLATFORM.remove();
    }
}
