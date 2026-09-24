package com.enterprise.admin.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.admin.dto.PageResponse;
import com.enterprise.admin.dto.employee.CreateEmployeeRequest;
import com.enterprise.admin.dto.employee.EmployeeDetail;
import com.enterprise.admin.dto.employee.EmployeeFields;
import com.enterprise.admin.dto.employee.EmployeeStatusRequest;
import com.enterprise.admin.dto.employee.EmployeeSummary;
import com.enterprise.admin.dto.employee.UpdateEmployeeRequest;
import com.enterprise.admin.entity.AuditAction;
import com.enterprise.admin.entity.AuditEntityType;
import com.enterprise.admin.entity.Department;
import com.enterprise.admin.entity.Employee;
import com.enterprise.admin.entity.EmployeeStatus;
import com.enterprise.admin.entity.RoleName;
import com.enterprise.admin.entity.User;
import com.enterprise.admin.exception.BadRequestException;
import com.enterprise.admin.exception.ConflictException;
import com.enterprise.admin.exception.ForbiddenOperationException;
import com.enterprise.admin.exception.ResourceNotFoundException;
import com.enterprise.admin.repository.DepartmentRepository;
import com.enterprise.admin.repository.EmployeeRepository;
import com.enterprise.admin.repository.EmployeeSpecifications;
import com.enterprise.admin.repository.UserRepository;
import com.enterprise.admin.security.AuthenticatedUser;
import com.enterprise.admin.security.CurrentActor;
import com.enterprise.admin.service.AuditService.Target;
import com.enterprise.admin.service.support.PageRequestFactory;
import com.enterprise.admin.service.support.PageRequestFactory.SortAllowlist;

import lombok.RequiredArgsConstructor;

/**
 * Employee records. Role rules (controller): read = any role; create/update = ADMIN or MANAGER; status changes
 * = ADMIN. Field rules (here): only ADMIN may link a user account or create with a non-ACTIVE status.
 *
 * <p>Updates carry the {@code version} last read. A mismatch is rejected with 409 {@code STALE_VERSION}; a race
 * between two in-flight updates is caught by JPA {@code @Version} and mapped to the same 409.
 */
@Service
@RequiredArgsConstructor
public class EmployeeService {

    static final SortAllowlist SORT = new SortAllowlist("name", Sort.Direction.ASC, Map.of(
            "name", List.of("lastName", "firstName"),
            "employeeCode", List.of("employeeCode"),
            "department", List.of("department.name"),
            "jobTitle", List.of("jobTitle"),
            "status", List.of("status"),
            "hireDate", List.of("hireDate"),
            "createdAt", List.of("createdAt")));

    private final EmployeeRepository employeeRepository;
    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;

    @Transactional(readOnly = true)
    public PageResponse<EmployeeSummary> list(String search, Long departmentId, EmployeeStatus status,
                                              Integer page, Integer size, String sort, String direction) {
        var pageable = PageRequestFactory.create(page, size, sort, direction, SORT);
        return PageResponse.from(employeeRepository.findAll(EmployeeSpecifications.matches(search, departmentId, status), pageable),
                EmployeeSummary::from);
    }

    @Transactional(readOnly = true)
    public EmployeeDetail get(Long id) {
        return EmployeeDetail.from(load(id));
    }

    @Transactional
    public EmployeeDetail create(AuthenticatedUser actor, CreateEmployeeRequest request) {
        EmployeeStatus status = request.statusOrDefault();
        if (status != EmployeeStatus.ACTIVE && !isAdmin(actor)) {
            throw new ForbiddenOperationException("FORBIDDEN_FIELD", "Only administrators can set employee status.", "status");
        }
        if (request.userId() != null && !isAdmin(actor)) {
            throw linkForbidden();
        }
        String code = Employee.normalizeCode(request.employeeCode());
        if (employeeRepository.existsByEmployeeCode(code)) {
            throw duplicateCode();
        }
        if (employeeRepository.existsByEmail(User.normalizeEmail(request.email()))) {
            throw duplicateEmail();
        }
        Employee employee = new Employee(code, status);
        apply(employee, request, assignableDepartment(request.departmentId(), null));
        employee.linkUser(resolveUser(request.userId(), null));
        employeeRepository.saveAndFlush(employee);
        auditService.record(AuditAction.EMPLOYEE_CREATED, target(employee),
                AuditDetails.of("department", employee.getDepartment().getCode()).and("status", employee.getStatus())
                        .and("linkedAccount", employee.getUser() != null));
        return EmployeeDetail.from(employee);
    }

    @Transactional
    public EmployeeDetail update(AuthenticatedUser actor, Long id, UpdateEmployeeRequest request) {
        Employee employee = load(id);
        requireVersion(employee, request.version());

        Long currentUserId = employee.getUser() == null ? null : employee.getUser().getId();
        if (!Objects.equals(currentUserId, request.userId()) && !isAdmin(actor)) {
            throw linkForbidden();
        }
        if (employeeRepository.existsByEmployeeCodeAndIdNot(Employee.normalizeCode(request.employeeCode()), id)) {
            throw duplicateCode();
        }
        if (employeeRepository.existsByEmailAndIdNot(User.normalizeEmail(request.email()), id)) {
            throw duplicateEmail();
        }
        Map<String, Object> before = snapshot(employee);
        apply(employee, request, assignableDepartment(request.departmentId(), employee.getDepartment()));
        employee.linkUser(resolveUser(request.userId(), id));
        employeeRepository.saveAndFlush(employee);
        List<String> changed = changedKeys(before, snapshot(employee));
        if (!changed.isEmpty()) {
            auditService.record(AuditAction.EMPLOYEE_UPDATED, target(employee), AuditDetails.of("changedFields", changed));
            notificationService.employeeRecordUpdated(employee, changed, null, actor.id());
        }
        return EmployeeDetail.from(employee);
    }

    @Transactional
    public EmployeeDetail updateStatus(Long id, EmployeeStatusRequest request) {
        Employee employee = load(id);
        requireVersion(employee, request.version());
        EmployeeStatus previous = employee.getStatus();
        employee.setStatus(request.status());
        employeeRepository.saveAndFlush(employee);
        if (previous != employee.getStatus()) {
            auditService.record(AuditAction.EMPLOYEE_STATUS_CHANGED, target(employee),
                    AuditDetails.of("from", previous).and("to", employee.getStatus()));
            notificationService.employeeRecordUpdated(employee, List.of("status"), previous, CurrentActor.id());
        }
        return EmployeeDetail.from(employee);
    }

    private static Target target(Employee e) {
        return new Target(AuditEntityType.EMPLOYEE, e.getId(), e.getEmployeeCode() + " · " + e.getFirstName() + " " + e.getLastName());
    }

    /** Comparable view of the editable fields; only the <em>names</em> of changed fields are ever audited. */
    private static Map<String, Object> snapshot(Employee e) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("employeeCode", e.getEmployeeCode());
        values.put("firstName", e.getFirstName());
        values.put("lastName", e.getLastName());
        values.put("email", e.getEmail());
        values.put("phone", e.getPhone());
        values.put("jobTitle", e.getJobTitle());
        values.put("department", e.getDepartment() == null ? null : e.getDepartment().getId());
        values.put("hireDate", e.getHireDate());
        values.put("userId", e.getUser() == null ? null : e.getUser().getId());
        return values;
    }

    static List<String> changedKeys(Map<String, Object> before, Map<String, Object> after) {
        return before.keySet().stream().filter(key -> !Objects.equals(before.get(key), after.get(key))).toList();
    }

    private void apply(Employee employee, EmployeeFields fields, Department department) {
        employee.updateDetails(fields.employeeCode(), fields.firstName(), fields.lastName(), fields.email(),
                fields.phone(), fields.jobTitle(), department, fields.hireDate());
    }

    /** New assignments must target an active department; keeping an existing (now inactive) one is allowed. */
    private Department assignableDepartment(Long departmentId, Department current) {
        if (current != null && current.getId().equals(departmentId)) {
            return current;
        }
        Department department = departmentRepository.findById(departmentId)
                .orElseThrow(() -> new BadRequestException("INVALID_DEPARTMENT", "Department does not exist.", "departmentId"));
        if (!department.isActive()) {
            throw new BadRequestException("INACTIVE_DEPARTMENT", "Employees cannot be assigned to an inactive department.", "departmentId");
        }
        return department;
    }

    private User resolveUser(Long userId, Long employeeId) {
        if (userId == null) {
            return null;
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BadRequestException("INVALID_USER", "User account does not exist.", "userId"));
        boolean linkedElsewhere = employeeId == null
                ? employeeRepository.existsByUserId(userId)
                : employeeRepository.existsByUserIdAndIdNot(userId, employeeId);
        if (linkedElsewhere) {
            throw new ConflictException("USER_ALREADY_LINKED", "This user account is already linked to another employee.", "userId");
        }
        return user;
    }

    private static void requireVersion(Employee employee, Long expected) {
        if (!Objects.equals(employee.getVersion(), expected)) {
            throw ConflictException.staleVersion();
        }
    }

    private Employee load(Long id) {
        return employeeRepository.findWithDetailsById(id).orElseThrow(() -> new ResourceNotFoundException("Employee"));
    }

    private static boolean isAdmin(AuthenticatedUser actor) {
        return actor.roles().contains(RoleName.ADMIN);
    }

    private static ForbiddenOperationException linkForbidden() {
        return new ForbiddenOperationException("FORBIDDEN_FIELD", "Only administrators can link user accounts.", "userId");
    }

    private static ConflictException duplicateCode() {
        return new ConflictException("DUPLICATE_CODE", "An employee with this code already exists.", "employeeCode");
    }

    private static ConflictException duplicateEmail() {
        return new ConflictException("DUPLICATE_EMAIL", "An employee with this email already exists.", "email");
    }
}
