package com.workflow.account;

import java.util.function.Supplier;

/**
 * Resolves the {@code accountId} parameter of the auto-enabled Hibernate {@code accountFilter}
 * (declared on {@link Account}). Hibernate instantiates this per session via its no-arg
 * constructor and calls {@link #get()} when opening the session.
 *
 * <p>Returns {@link TenantContext#NO_SCOPE} when there is no tenant scope (platform staff,
 * startup initializers, tests) so the filter's {@code (:accountId = -1 or ...)} condition
 * degrades to a no-op instead of hiding every row.
 */
public class TenantFilterResolver implements Supplier<Long> {

    @Override
    public Long get() {
        return TenantContext.filterValue();
    }
}
