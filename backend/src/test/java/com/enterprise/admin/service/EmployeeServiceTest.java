package com.enterprise.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.enterprise.admin.dto.employee.CreateEmployeeRequest;
import com.enterprise.admin.dto.employee.EmployeeStatusRequest;
import com.enterprise.admin.dto.employee.UpdateEmployeeRequest;
import com.enterprise.admin.entity.Department;
import com.enterprise.admin.entity.Employee;
import com.enterprise.admin.entity.EmployeeStatus;
import com.enterprise.admin.entity.RoleName;
import com.enterprise.admin.exception.ApiException;
import com.enterprise.admin.repository.DepartmentRepository;
import com.enterprise.admin.repository.EmployeeRepository;
import com.enterprise.admin.repository.UserRepository;
import com.enterprise.admin.security.AuthenticatedUser;

class EmployeeServiceTest {

    private static final AuthenticatedUser ADMIN = new AuthenticatedUser(1L, Set.of(RoleName.ADMIN));
    private static final AuthenticatedUser MANAGER = new AuthenticatedUser(2L, Set.of(RoleName.MANAGER));

    private EmployeeRepository employees;
    private DepartmentRepository departments;
    private EmployeeService service;
    private Department active;
    private Department inactive;

    @BeforeEach
    void setUp() {
        employees = mock(EmployeeRepository.class);
        departments = mock(DepartmentRepository.class);
        service = new EmployeeService(employees, departments, mock(UserRepository.class), mock(AuditService.class),
                mock(NotificationService.class));
        active = department(10L, true);
        inactive = department(11L, false);
    }

    private Department department(long id, boolean isActive) {
        Department d = new Department("Dept " + id, "D" + id, null);
        ReflectionTestUtils.setField(d, "id", id);
        d.setActive(isActive);
        given(departments.findById(id)).willReturn(Optional.of(d));
        return d;
    }

    private Employee existing(Department department, long version) {
        Employee e = new Employee("EMP-1", EmployeeStatus.ACTIVE);
        e.updateDetails("EMP-1", "Ada", "Lovelace", "ada@corp.example", null, "Engineer", department, LocalDate.of(2024, 1, 1));
        ReflectionTestUtils.setField(e, "id", 5L);
        ReflectionTestUtils.setField(e, "version", version);
        given(employees.findWithDetailsById(5L)).willReturn(Optional.of(e));
        return e;
    }

    private static CreateEmployeeRequest create(Long departmentId, EmployeeStatus status, Long userId) {
        return new CreateEmployeeRequest("EMP-9", "Grace", "Hopper", "grace@corp.example", null, "Admiral",
                departmentId, LocalDate.of(2025, 5, 1), status, userId);
    }

    private static UpdateEmployeeRequest update(Long departmentId, Long userId, long version) {
        return new UpdateEmployeeRequest("EMP-1", "Ada", "King", "ada@corp.example", "+44 20 7946 0000", "Lead",
                departmentId, LocalDate.of(2024, 1, 1), userId, version);
    }

    private static String code(Runnable action) {
        try {
            action.run();
            return null;
        } catch (ApiException ex) {
            return ex.getCode() + "@" + ex.getStatus().value();
        }
    }

    @Test
    void staleVersionIsRejectedWithConflict() {
        existing(active, 3);
        assertThat(code(() -> service.update(ADMIN, 5L, update(10L, null, 2)))).isEqualTo("STALE_VERSION@409");
        assertThat(code(() -> service.updateStatus(5L, new EmployeeStatusRequest(EmployeeStatus.ON_LEAVE, 4L))))
                .isEqualTo("STALE_VERSION@409");
    }

    @Test
    void currentVersionIsAccepted() {
        Employee e = existing(active, 3);
        service.update(MANAGER, 5L, update(10L, null, 3));
        assertThat(e.getLastName()).isEqualTo("King");
        assertThat(e.getPhone()).isEqualTo("+44 20 7946 0000");
    }

    @Test
    void managerCannotLinkUserAccountsOrSetNonActiveStatus() {
        assertThat(code(() -> service.create(MANAGER, create(10L, null, 7L)))).isEqualTo("FORBIDDEN_FIELD@403");
        assertThat(code(() -> service.create(MANAGER, create(10L, EmployeeStatus.TERMINATED, null)))).isEqualTo("FORBIDDEN_FIELD@403");
        existing(active, 0);
        assertThat(code(() -> service.update(MANAGER, 5L, update(10L, 7L, 0)))).isEqualTo("FORBIDDEN_FIELD@403");
    }

    @Test
    void newAssignmentsToInactiveDepartmentsAreRejectedButExistingOnesAreKept() {
        assertThat(code(() -> service.create(ADMIN, create(11L, null, null)))).isEqualTo("INACTIVE_DEPARTMENT@400");

        Employee e = existing(inactive, 1);
        assertThat(code(() -> service.update(ADMIN, 5L, update(11L, null, 1)))).isNull();
        assertThat(e.getDepartment()).isSameAs(inactive);

        existing(active, 1);
        assertThat(code(() -> service.update(ADMIN, 5L, update(11L, null, 1)))).isEqualTo("INACTIVE_DEPARTMENT@400");
    }

    @Test
    void duplicateCodesAndEmailsConflict() {
        given(employees.existsByEmployeeCode("EMP-9")).willReturn(true);
        assertThat(code(() -> service.create(ADMIN, create(10L, null, null)))).isEqualTo("DUPLICATE_CODE@409");

        given(employees.existsByEmployeeCode("EMP-9")).willReturn(false);
        given(employees.existsByEmail("grace@corp.example")).willReturn(true);
        assertThat(code(() -> service.create(ADMIN, create(10L, null, null)))).isEqualTo("DUPLICATE_EMAIL@409");
    }
}
