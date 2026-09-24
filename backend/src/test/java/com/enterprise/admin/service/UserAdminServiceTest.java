package com.enterprise.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.enterprise.admin.dto.user.CreateUserRequest;
import com.enterprise.admin.dto.user.UpdateUserRequest;
import com.enterprise.admin.entity.RefreshTokenRevocationReason;
import com.enterprise.admin.entity.Role;
import com.enterprise.admin.entity.RoleName;
import com.enterprise.admin.entity.User;
import com.enterprise.admin.exception.ApiException;
import com.enterprise.admin.exception.BadRequestException;
import com.enterprise.admin.exception.ConflictException;
import com.enterprise.admin.exception.ResourceNotFoundException;
import com.enterprise.admin.repository.RefreshTokenRepository;
import com.enterprise.admin.repository.RoleRepository;
import com.enterprise.admin.repository.UserRepository;

/** Every administrator safeguard, exercised in isolation. */
class UserAdminServiceTest {

    private static final long ACTOR = 1L;
    private static final long TARGET = 2L;

    private UserRepository users;
    private RoleRepository roles;
    private RefreshTokenRepository tokens;
    private UserAdminService service;
    private final Map<RoleName, Role> roleRows = new EnumMap<>(RoleName.class);

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        roles = mock(RoleRepository.class);
        tokens = mock(RefreshTokenRepository.class);
        for (RoleName name : RoleName.values()) {
            Role role = mock(Role.class);
            given(role.getName()).willReturn(name);
            roleRows.put(name, role);
            given(roles.findByName(name)).willReturn(Optional.of(role));
        }
        given(roles.findByNameForUpdate(RoleName.ADMIN)).willReturn(Optional.of(roleRows.get(RoleName.ADMIN)));
        service = new UserAdminService(users, roles, tokens, new BCryptPasswordEncoder(4), new PasswordPolicy(),
                mock(AuditService.class), mock(NotificationService.class), Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    }

    private User user(long id, boolean enabled, RoleName... roleNames) {
        User user = new User("u" + id + "@example.com", "hash", "First", "Last");
        ReflectionTestUtils.setField(user, "id", id);
        user.setEnabled(enabled);
        for (RoleName name : roleNames) {
            user.addRole(roleRows.get(name));
        }
        given(users.findWithRolesById(id)).willReturn(Optional.of(user));
        return user;
    }

    private static String code(Runnable action) {
        try {
            action.run();
            return null;
        } catch (ApiException ex) {
            return ex.getCode();
        }
    }

    @Test
    void adminCannotDisableThemselves() {
        user(ACTOR, true, RoleName.ADMIN);
        given(users.countEnabledWithRole(RoleName.ADMIN)).willReturn(5L);

        assertThat(code(() -> service.updateStatus(ACTOR, ACTOR, false))).isEqualTo("SELF_DISABLE");
        verify(tokens, never()).revokeAllForUser(anyLong(), any(), any());
    }

    @Test
    void adminCannotRemoveTheirOwnAdminRole() {
        user(ACTOR, true, RoleName.ADMIN, RoleName.MANAGER);
        given(users.countEnabledWithRole(RoleName.ADMIN)).willReturn(5L);

        assertThat(code(() -> service.updateRoles(ACTOR, ACTOR, Set.of(RoleName.MANAGER)))).isEqualTo("SELF_DEMOTION");
    }

    @Test
    void adminMayChangeOwnRolesWhileKeepingAdmin() {
        User self = user(ACTOR, true, RoleName.ADMIN);
        service.updateRoles(ACTOR, ACTOR, Set.of(RoleName.ADMIN, RoleName.MANAGER));
        assertThat(self.roleNames()).containsExactlyInAnyOrder(RoleName.ADMIN, RoleName.MANAGER);
    }

    @Test
    void lastActiveAdminCannotBeDisabled() {
        user(TARGET, true, RoleName.ADMIN);
        given(users.countEnabledWithRole(RoleName.ADMIN)).willReturn(1L);

        assertThat(code(() -> service.updateStatus(ACTOR, TARGET, false))).isEqualTo("LAST_ADMIN");
    }

    @Test
    void lastActiveAdminCannotLoseAdminRole() {
        user(TARGET, true, RoleName.ADMIN);
        given(users.countEnabledWithRole(RoleName.ADMIN)).willReturn(1L);

        assertThat(code(() -> service.updateRoles(ACTOR, TARGET, Set.of(RoleName.USER)))).isEqualTo("LAST_ADMIN");
    }

    @Test
    void anotherAdminCanBeDemotedOrDisabledWhenOthersRemain() {
        User target = user(TARGET, true, RoleName.ADMIN);
        given(users.countEnabledWithRole(RoleName.ADMIN)).willReturn(2L);

        service.updateRoles(ACTOR, TARGET, Set.of(RoleName.USER));
        assertThat(target.roleNames()).containsExactly(RoleName.USER);
    }

    @Test
    void disabledAdminIsNotCountedAsTheLastOne() {
        // Demoting an already-disabled admin cannot reduce the number of *active* admins.
        User target = user(TARGET, false, RoleName.ADMIN);
        given(users.countEnabledWithRole(RoleName.ADMIN)).willReturn(1L);

        service.updateRoles(ACTOR, TARGET, Set.of(RoleName.USER));
        assertThat(target.roleNames()).containsExactly(RoleName.USER);
    }

    @Test
    void safeguardOperationsLockTheAdminRoleFirst() {
        user(TARGET, true, RoleName.USER);
        service.updateStatus(ACTOR, TARGET, false);
        service.updateRoles(ACTOR, TARGET, Set.of(RoleName.MANAGER));
        verify(roles, org.mockito.Mockito.times(2)).findByNameForUpdate(RoleName.ADMIN);
    }

    @Test
    void disablingRevokesAllSessionsOfThatUser() {
        User target = user(TARGET, true, RoleName.USER);
        service.updateStatus(ACTOR, TARGET, false);

        assertThat(target.isEnabled()).isFalse();
        verify(tokens).revokeAllForUser(eq(TARGET), eq(RefreshTokenRevocationReason.USER_DISABLED), any());
    }

    @Test
    void enablingDoesNotTouchSessions() {
        User target = user(TARGET, false, RoleName.USER);
        service.updateStatus(ACTOR, TARGET, true);
        assertThat(target.isEnabled()).isTrue();
        verify(tokens, never()).revokeAllForUser(anyLong(), any(), any());
    }

    @Test
    void createRejectsDuplicateEmailAndWeakPassword() {
        given(users.existsByEmail("taken@example.com")).willReturn(true);
        assertThatThrownBy(() -> service.create(new CreateUserRequest("A", "B", " Taken@Example.com ",
                "Long-Enough-Pass-1", Set.of(RoleName.USER), true)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("already exists");

        assertThatThrownBy(() -> service.create(new CreateUserRequest("A", "B", "new@example.com", "short",
                Set.of(RoleName.USER), true)))
                .isInstanceOf(BadRequestException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getField()).isEqualTo("initialPassword"));
    }

    @Test
    void createHashesPasswordNormalizesEmailAndNeverEchoesPassword() {
        var created = service.create(new CreateUserRequest("Ada", "Lovelace", " Ada@Example.COM ",
                "Long-Enough-Pass-1", Set.of(RoleName.MANAGER), null));

        assertThat(created.email()).isEqualTo("ada@example.com");
        assertThat(created.roles()).containsExactly("MANAGER");
        assertThat(created.enabled()).isTrue();
        assertThat(created.toString()).doesNotContain("Long-Enough-Pass-1");
        verify(users).saveAndFlush(org.mockito.ArgumentMatchers.argThat(u -> u.getPasswordHash().startsWith("$2a$")));
    }

    @Test
    void updateRejectsEmailOfAnotherAccountAndMissingUsers() {
        user(TARGET, true, RoleName.USER);
        given(users.existsByEmailAndIdNot("taken@example.com", TARGET)).willReturn(true);
        assertThat(code(() -> service.update(TARGET, new UpdateUserRequest("A", "B", "taken@example.com"))))
                .isEqualTo("DUPLICATE_EMAIL");

        given(users.findWithRolesById(99L)).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(99L)).isInstanceOf(ResourceNotFoundException.class);
    }
}
