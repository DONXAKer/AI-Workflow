package com.workflow.project;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Reads {@link ProjectContext#HEADER} off every inbound request and populates the thread
 * local. Runs after Spring Security so auth is already resolved.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class ProjectContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        String slug = request.getHeader(ProjectContext.HEADER);
        if (slug == null || slug.isBlank()) slug = Project.DEFAULT_SLUG;
        try {
            ProjectContext.set(slug);
            chain.doFilter(request, response);
        } finally {
            ProjectContext.clear();
        }
    }
}
