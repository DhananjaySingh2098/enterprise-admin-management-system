-- Phase 3: organisational data. Purely additive; no existing table is modified.

CREATE TABLE departments (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(120) NOT NULL,
    -- Short stable identifier, stored upper-case (e.g. ENG, HR-OPS).
    code        VARCHAR(20)  NOT NULL,
    description VARCHAR(500) NULL,
    -- Departments are deactivated, never deleted, so employee history stays intact.
    active      BIT(1)       NOT NULL DEFAULT b'1',
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    CONSTRAINT pk_departments PRIMARY KEY (id),
    CONSTRAINT uk_departments_code UNIQUE (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_departments_name ON departments (name);
CREATE INDEX ix_departments_active ON departments (active);

CREATE TABLE employees (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    employee_code VARCHAR(30)  NOT NULL,
    first_name    VARCHAR(100) NOT NULL,
    last_name     VARCHAR(100) NOT NULL,
    -- Work email, stored lower-case; unique across employees.
    email         VARCHAR(254) NOT NULL,
    phone         VARCHAR(40)  NULL,
    job_title     VARCHAR(120) NOT NULL,
    department_id BIGINT       NOT NULL,
    hire_date     DATE         NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    -- Optional link to a system account; at most one employee per user.
    user_id       BIGINT       NULL,
    -- Optimistic-lock counter (JPA @Version).
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    CONSTRAINT pk_employees PRIMARY KEY (id),
    CONSTRAINT uk_employees_employee_code UNIQUE (employee_code),
    CONSTRAINT uk_employees_email UNIQUE (email),
    CONSTRAINT uk_employees_user_id UNIQUE (user_id),
    -- RESTRICT: a department referenced by employees can never be deleted out from under them.
    CONSTRAINT fk_employees_department FOREIGN KEY (department_id) REFERENCES departments (id) ON DELETE RESTRICT,
    CONSTRAINT fk_employees_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_employees_status CHECK (status IN ('ACTIVE', 'ON_LEAVE', 'TERMINATED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_employees_department_id ON employees (department_id);
CREATE INDEX ix_employees_status ON employees (status);
CREATE INDEX ix_employees_name ON employees (last_name, first_name);
CREATE INDEX ix_employees_hire_date ON employees (hire_date);
