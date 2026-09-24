package com.enterprise.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.enterprise.admin.entity.Role;
import com.enterprise.admin.entity.RoleName;
import com.enterprise.admin.entity.User;
import com.enterprise.admin.repository.RoleRepository;
import com.enterprise.admin.repository.UserRepository;

class InitialAdminInitializerTest {

    private static final String PASSWORD = "Sufficiently-Long-Pass-1";

    private UserRepository users;
    private RoleRepository roles;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        roles = mock(RoleRepository.class);
        given(roles.findByName(RoleName.ADMIN)).willReturn(Optional.of(mock(Role.class)));
    }

    private InitialAdminInitializer initializer(String email, String password) {
        return new InitialAdminInitializer(new AdminBootstrapProperties(email, password, "System", "Administrator"),
                users, roles, encoder, new PasswordPolicy());
    }

    @Test
    void createsAdminWithBcryptHashAndNormalizedEmail() {
        initializer("  Admin@Example.COM ", PASSWORD).run(null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("admin@example.com");
        assertThat(saved.getValue().getPasswordHash()).startsWith("$2a$").isNotEqualTo(PASSWORD);
        assertThat(encoder.matches(PASSWORD, saved.getValue().getPasswordHash())).isTrue();
        assertThat(saved.getValue().isEnabled()).isTrue();
    }

    @Test
    void skipsWhenAnAdminAlreadyExists() {
        given(users.existsByRolesName(RoleName.ADMIN)).willReturn(true);

        initializer("admin@example.com", PASSWORD).run(null);

        verify(users, never()).save(any());
    }

    @Test
    void neverOverwritesAnExistingAccountWithTheSameEmail() {
        given(users.existsByEmail("admin@example.com")).willReturn(true);

        initializer("admin@example.com", PASSWORD).run(null);

        verify(users, never()).save(any());
    }

    @Test
    void doesNothingWhenNotConfigured() {
        initializer("", "").run(null);
        initializer(null, null).run(null);

        verify(users, never()).save(any());
    }

    @Test
    void weakPasswordFailsStartupWithoutRevealingIt() {
        String weak = "short-pw";
        assertThatThrownBy(() -> initializer("admin@example.com", weak).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_PASSWORD")
                .hasMessageNotContaining(weak);
        verify(users, never()).save(any());
    }

    @Test
    void passwordOverBcryptLimitIsRejected() {
        assertThatThrownBy(() -> initializer("admin@example.com", "a".repeat(73)).run(null))
                .hasMessageContaining("password policy");
    }

    @Test
    void invalidEmailFailsStartup() {
        assertThatThrownBy(() -> initializer("not-an-email", PASSWORD).run(null))
                .hasMessageContaining("ADMIN_EMAIL");
    }

    @Test
    void propertiesToStringRedactsPassword() {
        assertThat(new AdminBootstrapProperties("a@b.co", PASSWORD, "x", "y").toString()).doesNotContain(PASSWORD);
    }
}
