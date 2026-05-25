package com.workflow.account;

import com.workflow.security.UserRepository;
import com.workflow.security.UserRole;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Resolves the authenticated user's owning account into {@link TenantContext} for the
 * duration of each request. Runs after Spring Security (auth already resolved) and just
 * before {@link com.workflow.project.ProjectContextFilter}, so the auto-enabled Hibernate
 * {@code accountFilter} scopes any DB access ProjectContextFilter triggers.
 *
 * <p>Platform staff ({@link UserRole#PLATFORM_ADMIN}) are flagged see-all. Unauthenticated
 * requests (login, register, webhooks, static assets) leave the context unset — the filter
 * then degrades to a no-op (see {@link TenantContext#filterValue()}).
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class TenantContextFilter extends OncePerRequestFilter {

    @Lazy
    @Autowired(required = false)
    private UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        try {
            resolve();
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private void resolve() {
        if (userRepository == null) return;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return;
        String username = auth.getName();
        if (username == null || "anonymousUser".equals(username)) return;
        userRepository.findByUsername(username).ifPresent(user -> {
            if (user.getRole() == UserRole.PLATFORM_ADMIN) {
                TenantContext.setPlatformAdmin(true);
            }
            if (user.getAccountId() != null) {
                TenantContext.set(user.getAccountId());
            }
        });
    }
}
