package com.workflow.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class LocalUserDetailsService implements UserDetailsService {

    @Autowired
    private UserRepository userRepository;

    /** When true, an unverified email disables the account (login is rejected). */
    @Value("${workflow.auth.require-email-verification:false}")
    private boolean requireEmailVerification;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        // PLATFORM_ADMIN is additionally granted ROLE_ADMIN so endpoints already gated on
        // hasRole('ADMIN') keep working for platform staff.
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(user.getRole().authority()));
        if (user.getRole() == UserRole.PLATFORM_ADMIN) {
            authorities.add(new SimpleGrantedAuthority(UserRole.ADMIN.authority()));
        }

        boolean disabled = !user.isEnabled()
            || (requireEmailVerification && !user.isEmailVerified());

        return org.springframework.security.core.userdetails.User.builder()
            .username(user.getUsername())
            .password(user.getPasswordHash())
            .disabled(disabled)
            .authorities(authorities)
            .build();
    }
}
