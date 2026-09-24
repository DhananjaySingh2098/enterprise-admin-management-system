package com.enterprise.admin.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import com.enterprise.admin.entity.Employee;
import com.enterprise.admin.entity.RoleName;
import com.enterprise.admin.entity.User;
import com.enterprise.admin.repository.EmployeeRepository;

import jakarta.persistence.EntityManagerFactory;
import tools.jackson.databind.JsonNode;

class EmployeeDepartmentIntegrationTest extends AbstractIntegrationTest {

    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private TransactionTemplate transactions;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private JsonNode read(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private long createDepartment(String admin, String code) throws Exception {
        return read(mockMvc.perform(jsonBody(as(admin, post("/api/departments")),
                        Map.of("name", "Dept " + code, "code", code.toLowerCase(), "description", "Test department")))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    private Map<String, Object> employee(String code, long departmentId) {
        Map<String, Object> body = new HashMap<>();
        body.put("employeeCode", code);
        body.put("firstName", "Katherine");
        body.put("lastName", "Johnson-" + code);
        body.put("email", code.toLowerCase() + "@corp.example");
        body.put("phone", "+1 555 0100");
        body.put("jobTitle", "Mathematician");
        body.put("departmentId", departmentId);
        body.put("hireDate", "2024-02-01");
        return body;
    }

    private JsonNode createEmployee(String token, Map<String, Object> body) throws Exception {
        return read(mockMvc.perform(jsonBody(as(token, post("/api/employees")), body)).andExpect(status().isCreated()));
    }

    // ---------------------------------------------------------------- departments

    @Test
    void departmentLifecycleAndPermissions() throws Exception {
        String admin = rootAdminToken();
        String code = unique("D").substring(0, 10).toUpperCase().replace("-", "");
        long id = createDepartment(admin, code);

        mockMvc.perform(as(admin, get("/api/departments/" + id)))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.headcount").value(0));

        mockMvc.perform(jsonBody(as(admin, post("/api/departments")), Map.of("name", "Dup", "code", code.toLowerCase())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_CODE"));
        mockMvc.perform(jsonBody(as(admin, post("/api/departments")), Map.of("name", "Bad", "code", "has space")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("code"));

        mockMvc.perform(jsonBody(as(admin, put("/api/departments/" + id)), Map.of("name", "Renamed", "code", code)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Renamed"))
                .andExpect(jsonPath("$.description").doesNotExist());
        mockMvc.perform(jsonBody(as(admin, put("/api/departments/" + id + "/status")), Map.of("active", false)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));

        for (String token : List.of(tokenForNewUser(RoleName.MANAGER), tokenForNewUser(RoleName.USER))) {
            mockMvc.perform(as(token, get("/api/departments").param("search", code))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1));
            mockMvc.perform(jsonBody(as(token, post("/api/departments")), Map.of("name", "X", "code", "XX1")))
                    .andExpect(status().isForbidden());
            mockMvc.perform(jsonBody(as(token, put("/api/departments/" + id)), Map.of("name", "X", "code", code)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(jsonBody(as(token, put("/api/departments/" + id + "/status")), Map.of("active", true)))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/api/departments")).andExpect(status().isUnauthorized());
    }

    @Test
    void headcountIsComputedFromCurrentEmployeesOnly() throws Exception {
        String admin = rootAdminToken();
        String tag = unique("HC").toUpperCase();
        long dept = createDepartment(admin, tag.substring(0, 10).replace("-", ""));
        createEmployee(admin, employee(tag + "-1", dept));
        JsonNode leaving = createEmployee(admin, employee(tag + "-2", dept));
        createEmployee(admin, employee(tag + "-3", dept));
        mockMvc.perform(jsonBody(as(admin, put("/api/employees/" + leaving.get("id").asLong() + "/status")),
                Map.of("status", "TERMINATED", "version", leaving.get("version").asLong()))).andExpect(status().isOk());

        mockMvc.perform(as(admin, get("/api/departments/" + dept))).andExpect(jsonPath("$.headcount").value(2));
    }

    @Test
    void departmentsWithEmployeesCannotBeDeletedAndInactiveOnesRejectNewAssignments() throws Exception {
        String admin = rootAdminToken();
        String tag = unique("FK").toUpperCase();
        long dept = createDepartment(admin, tag.substring(0, 10).replace("-", ""));
        createEmployee(admin, employee(tag, dept));

        assertThatThrownBy(() -> jdbc.update("delete from departments where id = ?", dept)).isInstanceOf(DataAccessException.class);

        mockMvc.perform(jsonBody(as(admin, put("/api/departments/" + dept + "/status")), Map.of("active", false))).andExpect(status().isOk());
        mockMvc.perform(jsonBody(as(admin, post("/api/employees")), employee(tag + "-NEW", dept)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INACTIVE_DEPARTMENT"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("departmentId"));
    }

    // ---------------------------------------------------------------- employees

    @Test
    void employeeRolePermissions() throws Exception {
        String admin = rootAdminToken();
        String manager = tokenForNewUser(RoleName.MANAGER);
        String user = tokenForNewUser(RoleName.USER);
        String tag = unique("PERM").toUpperCase();
        long dept = createDepartment(admin, tag.substring(0, 10).replace("-", ""));

        JsonNode byManager = createEmployee(manager, employee(tag + "-M", dept));
        long id = byManager.get("id").asLong();

        mockMvc.perform(as(user, get("/api/employees/" + id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeCode").value(tag + "-M"));
        mockMvc.perform(as(user, get("/api/employees").param("search", tag))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(jsonBody(as(user, post("/api/employees")), employee(tag + "-U", dept))).andExpect(status().isForbidden());

        Map<String, Object> update = employee(tag + "-M", dept);
        update.put("jobTitle", "Lead Mathematician");
        update.put("version", byManager.get("version").asLong());
        mockMvc.perform(jsonBody(as(user, put("/api/employees/" + id)), update)).andExpect(status().isForbidden());
        JsonNode updated = read(mockMvc.perform(jsonBody(as(manager, put("/api/employees/" + id)), update))
                .andExpect(status().isOk()).andExpect(jsonPath("$.jobTitle").value("Lead Mathematician")));

        Map<String, Object> status = Map.of("status", "ON_LEAVE", "version", updated.get("version").asLong());
        mockMvc.perform(jsonBody(as(manager, put("/api/employees/" + id + "/status")), status)).andExpect(status().isForbidden());
        mockMvc.perform(jsonBody(as(user, put("/api/employees/" + id + "/status")), status)).andExpect(status().isForbidden());
        mockMvc.perform(jsonBody(as(admin, put("/api/employees/" + id + "/status")), status))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ON_LEAVE"));
        mockMvc.perform(get("/api/employees")).andExpect(status().isUnauthorized());
    }

    @Test
    void searchFiltersSortingAndPagination() throws Exception {
        String admin = rootAdminToken();
        String tag = unique("SRCH").toUpperCase();
        long deptA = createDepartment(admin, (tag.substring(0, 8) + "A").replace("-", ""));
        long deptB = createDepartment(admin, (tag.substring(0, 8) + "B").replace("-", ""));
        for (int i = 1; i <= 4; i++) {
            createEmployee(admin, employee(tag + "-" + i, i <= 3 ? deptA : deptB));
        }
        Map<String, Object> analyst = employee(tag + "-5", deptB);
        analyst.put("jobTitle", "Quantum Analyst");
        analyst.put("status", "ON_LEAVE");
        createEmployee(admin, analyst);

        mockMvc.perform(as(admin, get("/api/employees").param("search", tag).param("size", "2")
                        .param("sort", "employeeCode").param("direction", "desc")))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.content[0].employeeCode").value(tag + "-5"))
                .andExpect(jsonPath("$.content[0].department.id").value(deptB));
        mockMvc.perform(as(admin, get("/api/employees").param("search", tag).param("departmentId", String.valueOf(deptA))))
                .andExpect(jsonPath("$.totalElements").value(3));
        mockMvc.perform(as(admin, get("/api/employees").param("search", tag).param("status", "ON_LEAVE")))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].jobTitle").value("Quantum Analyst"));
        mockMvc.perform(as(admin, get("/api/employees").param("search", "quantum analyst")))
                .andExpect(jsonPath("$.content[?(@.employeeCode == '" + tag + "-5')]").exists());
        mockMvc.perform(as(admin, get("/api/employees").param("search", tag).param("sort", "department")))
                .andExpect(status().isOk());
        mockMvc.perform(as(admin, get("/api/employees").param("sort", "salary"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SORT"));
        mockMvc.perform(as(admin, get("/api/employees").param("status", "RETIRED"))).andExpect(status().isBadRequest());
    }

    @Test
    void duplicateCodesAndEmailsAreRejected() throws Exception {
        String admin = rootAdminToken();
        String tag = unique("DUP").toUpperCase();
        long dept = createDepartment(admin, tag.substring(0, 10).replace("-", ""));
        createEmployee(admin, employee(tag, dept));

        Map<String, Object> sameCode = employee(tag.toLowerCase(), dept);
        sameCode.put("email", "other-" + tag.toLowerCase() + "@corp.example");
        mockMvc.perform(jsonBody(as(admin, post("/api/employees")), sameCode))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_CODE"));

        Map<String, Object> sameEmail = employee(tag + "-X", dept);
        sameEmail.put("email", tag.toUpperCase() + "@CORP.EXAMPLE");
        mockMvc.perform(jsonBody(as(admin, post("/api/employees")), sameEmail))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));
    }

    @Test
    void staleUpdatesAreRejectedAndNeverOverwriteNewerData() throws Exception {
        String admin = rootAdminToken();
        String manager = tokenForNewUser(RoleName.MANAGER);
        String tag = unique("LOCK").toUpperCase();
        long dept = createDepartment(admin, tag.substring(0, 10).replace("-", ""));
        JsonNode created = createEmployee(admin, employee(tag, dept));
        long id = created.get("id").asLong();
        long v0 = created.get("version").asLong();

        Map<String, Object> first = employee(tag, dept);
        first.put("jobTitle", "Edited by admin");
        first.put("version", v0);
        mockMvc.perform(jsonBody(as(admin, put("/api/employees/" + id)), first))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(v0 + 1));

        Map<String, Object> stale = employee(tag, dept);
        stale.put("jobTitle", "Edited by manager from an old screen");
        stale.put("version", v0);
        mockMvc.perform(jsonBody(as(manager, put("/api/employees/" + id)), stale))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"))
                .andExpect(jsonPath("$.message").value("This record was updated by someone else. Reload the latest version before saving again."));

        assertThat(jdbc.queryForObject("select job_title from employees where id = ?", String.class, id)).isEqualTo("Edited by admin");
        assertThat(jdbc.queryForObject("select version from employees where id = ?", Long.class, id)).isEqualTo(v0 + 1);
    }

    @Test
    void jpaVersionDetectsConcurrentInFlightUpdatesOnMySql() throws Exception {
        String admin = rootAdminToken();
        String tag = unique("RACE").toUpperCase();
        long dept = createDepartment(admin, tag.substring(0, 10).replace("-", ""));
        long id = createEmployee(admin, employee(tag, dept)).get("id").asLong();

        var em = entityManagerFactory.createEntityManager();
        em.getTransaction().begin();
        Employee staleCopy = em.find(Employee.class, id);

        // Another transaction commits a change after the first one read the row.
        transactions.executeWithoutResult(tx -> employeeRepository.findById(id).orElseThrow().setStatus(com.enterprise.admin.entity.EmployeeStatus.ON_LEAVE));

        staleCopy.setStatus(com.enterprise.admin.entity.EmployeeStatus.TERMINATED);
        assertThatThrownBy(() -> {
            try {
                em.flush();
            } catch (jakarta.persistence.OptimisticLockException ex) {
                throw new ObjectOptimisticLockingFailureException(Employee.class, id, ex);
            }
        }).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        em.getTransaction().rollback();
        em.close();

        assertThat(jdbc.queryForObject("select status from employees where id = ?", String.class, id)).isEqualTo("ON_LEAVE");
    }

    @Test
    void onlyAdminsLinkUserAccountsAndEachAccountLinksOnce() throws Exception {
        String admin = rootAdminToken();
        String manager = tokenForNewUser(RoleName.MANAGER);
        String tag = unique("LINK").toUpperCase();
        long dept = createDepartment(admin, tag.substring(0, 10).replace("-", ""));
        User account = createUser(unique("linked") + "@example.com", RoleName.USER);

        Map<String, Object> body = employee(tag + "-1", dept);
        body.put("userId", account.getId());
        mockMvc.perform(jsonBody(as(manager, post("/api/employees")), body))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN_FIELD"));
        JsonNode linked = createEmployee(admin, body);
        assertThat(linked.get("linkedUser").get("email").asString()).isEqualTo(account.getEmail());
        assertThat(linked.get("linkedUser").has("passwordHash")).isFalse();

        Map<String, Object> second = employee(tag + "-2", dept);
        second.put("userId", account.getId());
        mockMvc.perform(jsonBody(as(admin, post("/api/employees")), second))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("USER_ALREADY_LINKED"));

        // A manager may edit the record as long as the link itself is left unchanged.
        Map<String, Object> keep = employee(tag + "-1", dept);
        keep.put("userId", account.getId());
        keep.put("version", linked.get("version").asLong());
        keep.put("jobTitle", "Retitled");
        mockMvc.perform(jsonBody(as(manager, put("/api/employees/" + linked.get("id").asLong())), keep)).andExpect(status().isOk());
    }

    @Test
    void schemaHasTheExpectedConstraintsAndIndexes() {
        List<String> indexes = jdbc.queryForList("""
                select distinct index_name from information_schema.statistics
                where table_schema = database() and table_name in ('employees', 'departments', 'users')""", String.class);
        assertThat(indexes).contains("uk_employees_employee_code", "uk_employees_email", "uk_employees_user_id",
                "ix_employees_department_id", "ix_employees_status", "ix_employees_name", "uk_departments_code",
                "ix_users_name");
        assertThatThrownBy(() -> jdbc.update("update employees set status = 'RETIRED' where id = (select id from (select min(id) id from employees) t)"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void validationErrorsNameFieldsAndMissingRecordsAre404() throws Exception {
        String admin = rootAdminToken();
        Map<String, Object> invalid = new HashMap<>(employee("!!", 1));
        invalid.put("email", "not-an-email");
        invalid.put("hireDate", null);
        mockMvc.perform(jsonBody(as(admin, post("/api/employees")), invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(org.hamcrest.Matchers.hasItems("employeeCode", "email", "hireDate")));
        mockMvc.perform(as(admin, get("/api/employees/99999999"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Employee not found"));
        mockMvc.perform(as(admin, get("/api/departments/99999999"))).andExpect(status().isNotFound());
    }
}
