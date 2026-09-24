package com.enterprise.admin.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.admin.dto.PageResponse;
import com.enterprise.admin.dto.department.DepartmentRequest;
import com.enterprise.admin.dto.department.DepartmentResponse;
import com.enterprise.admin.entity.AuditAction;
import com.enterprise.admin.entity.AuditEntityType;
import com.enterprise.admin.entity.Department;
import com.enterprise.admin.exception.ConflictException;
import com.enterprise.admin.exception.ResourceNotFoundException;
import com.enterprise.admin.repository.DepartmentRepository;
import com.enterprise.admin.repository.DepartmentSpecifications;
import com.enterprise.admin.repository.EmployeeRepository;
import com.enterprise.admin.service.AuditService.Target;
import com.enterprise.admin.service.support.PageRequestFactory;
import com.enterprise.admin.service.support.PageRequestFactory.SortAllowlist;

import lombok.RequiredArgsConstructor;

/**
 * Departments are never deleted: deactivation keeps every employee relationship intact (the FK is RESTRICT) and
 * only prevents new assignments.
 */
@Service
@RequiredArgsConstructor
public class DepartmentService {

    static final SortAllowlist SORT = new SortAllowlist("name", Sort.Direction.ASC, Map.of(
            "name", List.of("name"),
            "code", List.of("code"),
            "createdAt", List.of("createdAt"),
            "status", List.of("active")));

    private final DepartmentRepository departmentRepository;
    private final EmployeeRepository employeeRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public PageResponse<DepartmentResponse> list(String search, Boolean active, Integer page, Integer size,
                                                 String sort, String direction) {
        Page<Department> result = departmentRepository.findAll(DepartmentSpecifications.matches(search, active),
                PageRequestFactory.create(page, size, sort, direction, SORT));
        Map<Long, Long> headcount = headcount(result.getContent().stream().map(Department::getId).toList());
        return PageResponse.from(result, d -> DepartmentResponse.from(d, headcount.getOrDefault(d.getId(), 0L)));
    }

    @Transactional(readOnly = true)
    public DepartmentResponse get(Long id) {
        Department department = load(id);
        return DepartmentResponse.from(department, headcount(List.of(id)).getOrDefault(id, 0L));
    }

    @Transactional
    public DepartmentResponse create(DepartmentRequest request) {
        if (departmentRepository.existsByCode(Department.normalizeCode(request.code()))) {
            throw duplicateCode();
        }
        Department department = departmentRepository.saveAndFlush(
                new Department(request.name(), request.code(), request.description()));
        auditService.record(AuditAction.DEPARTMENT_CREATED, target(department), AuditDetails.none());
        return DepartmentResponse.from(department, 0);
    }

    @Transactional
    public DepartmentResponse update(Long id, DepartmentRequest request) {
        Department department = load(id);
        if (departmentRepository.existsByCodeAndIdNot(Department.normalizeCode(request.code()), id)) {
            throw duplicateCode();
        }
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(department.getName(), request.name())) {
            changed.add("name");
        }
        if (!Objects.equals(department.getCode(), Department.normalizeCode(request.code()))) {
            changed.add("code");
        }
        String description = request.description() == null || request.description().isBlank() ? null : request.description();
        if (!Objects.equals(department.getDescription(), description)) {
            changed.add("description");
        }
        department.update(request.name(), request.code(), request.description());
        departmentRepository.saveAndFlush(department);
        if (!changed.isEmpty()) {
            auditService.record(AuditAction.DEPARTMENT_UPDATED, target(department), AuditDetails.of("changedFields", changed));
        }
        return get(id);
    }

    @Transactional
    public DepartmentResponse setActive(Long id, boolean active) {
        Department department = load(id);
        boolean changed = department.isActive() != active;
        department.setActive(active);
        departmentRepository.saveAndFlush(department);
        if (changed) {
            auditService.record(active ? AuditAction.DEPARTMENT_REACTIVATED : AuditAction.DEPARTMENT_DEACTIVATED,
                    target(department), AuditDetails.none());
        }
        return get(id);
    }

    private static Target target(Department d) {
        return new Target(AuditEntityType.DEPARTMENT, d.getId(), d.getCode() + " · " + d.getName());
    }

    private Department load(Long id) {
        return departmentRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Department"));
    }

    private Map<Long, Long> headcount(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return employeeRepository.countCurrentByDepartment(ids).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
    }

    private static ConflictException duplicateCode() {
        return new ConflictException("DUPLICATE_CODE", "A department with this code already exists.", "code");
    }
}
