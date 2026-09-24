package com.enterprise.admin.service;

import java.util.regex.Pattern;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.admin.entity.RoleName;
import com.enterprise.admin.entity.User;
import com.enterprise.admin.repository.RoleRepository;
import com.enterprise.admin.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Creates the first ADMIN account from {@code ADMIN_EMAIL}/{@code ADMIN_PASSWORD} at startup.
 *
 * <ul>
 *   <li>Runs only while no ADMIN account exists; never modifies or overwrites existing accounts.</li>
 *   <li>Fails startup (without echoing the value) if the configured password violates the password policy.</li>
 *   <li>Logs outcomes, never the password.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InitialAdminInitializer implements ApplicationRunner {

    private static final Pattern SIMPLE_EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final AdminBootstrapProperties properties;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.existsByRolesName(RoleName.ADMIN)) {
            log.info("Initial admin bootstrap skipped: an ADMIN account already exists");
            return;
        }
        if (!properties.isConfigured()) {
            log.warn("No ADMIN account exists and ADMIN_EMAIL/ADMIN_PASSWORD are not set; nobody can administer the system");
            return;
        }

        String email = User.normalizeEmail(properties.email());
        if (email.length() > 254 || !SIMPLE_EMAIL.matcher(email).matches()) {
            throw new IllegalStateException("ADMIN_EMAIL is not a valid email address");
        }
        if (!passwordPolicy.isAcceptable(properties.password())) {
            throw new IllegalStateException("ADMIN_PASSWORD does not meet the password policy (" + PasswordPolicy.DESCRIPTION + ")");
        }
        if (userRepository.existsByEmail(email)) {
            log.warn("Initial admin not created: ADMIN_EMAIL belongs to an existing account, which is left unchanged");
            return;
        }

        User admin = new User(email, passwordEncoder.encode(properties.password()),
                properties.firstName(), properties.lastName());
        admin.addRole(roleRepository.findByName(RoleName.ADMIN)
                .orElseThrow(() -> new IllegalStateException("ADMIN role missing; database migrations have not run")));
        userRepository.save(admin);
        log.info("Initial ADMIN account created (user id {})", admin.getId());
    }
}
