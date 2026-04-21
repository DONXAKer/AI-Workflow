package com.workflow.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.http.HttpStatus;

/**
 * Session-based security for the REST + WebSocket backend.
 *
 * <p>Why sessions (not JWT) for MVP:
 * <ul>
 *   <li>SPA hits the same origin via Vite proxy → cookie works out of the box.</li>
 *   <li>No refresh-token plumbing, no token storage in localStorage (XSS exposure).</li>
 *   <li>Spring's default session handling already integrates with the existing WS layer.</li>
 * </ul>
 *
 * <p>CSRF is enabled with cookie-based tokens; the frontend reads the {@code XSRF-TOKEN}
 * cookie and echoes it on state-changing requests via the {@code X-XSRF-TOKEN} header.
 *
 * <p>Open paths: {@code /api/auth/login}, {@code /api/auth/logout}, {@code /ws/**},
 * static assets, H2 console (dev), webhooks (HMAC-validated separately in Epic 1.6).
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(LocalUserDetailsService uds, PasswordEncoder encoder,
                                                        org.springframework.context.ApplicationEventPublisher publisher) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(uds);
        provider.setPasswordEncoder(encoder);
        ProviderManager manager = new ProviderManager(provider);
        // Enable AuthenticationSuccessEvent / AuthenticationFailureEvent so AuthEventAuditor records them.
        manager.setAuthenticationEventPublisher(
            new org.springframework.security.authentication.DefaultAuthenticationEventPublisher(publisher));
        return manager;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // Plain CsrfTokenRequestAttributeHandler (no XOR) so curl/PS can send the raw
        // cookie value in X-XSRF-TOKEN without client-side XOR math.
        CsrfTokenRequestAttributeHandler requestHandler = new CsrfTokenRequestAttributeHandler();
        requestHandler.setCsrfRequestAttributeName(null);  // always populate the deferred token

        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(requestHandler)
                .ignoringRequestMatchers(
                    "/api/auth/login",
                    "/api/auth/logout",
                    "/ws/**",
                    "/api/webhooks/**",
                    "/h2-console/**"))
            .headers(h -> h.frameOptions(f -> f.sameOrigin()))  // H2 console
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/api/auth/login",
                    "/api/auth/logout",
                    "/ws/**",
                    "/api/webhooks/**",
                    "/h2-console/**",
                    "/actuator/health",
                    "/actuator/info",
                    "/actuator/prometheus").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll()  // static assets, SPA index
            )
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .logout(l -> l.disable());  // custom logout in AuthController

        return http.build();
    }
}
