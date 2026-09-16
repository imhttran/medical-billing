-- Billing RBAC: organizations, the permission catalogue, permission-bearing
-- roles, and organization-scoped role assignments.
--
-- `users.role` from V1__init.sql stays exactly as it is — it is the template's
-- coarse platform gate (client < staff < admin) and every existing endpoint
-- keeps using it. This layer is the authoritative billing authorization model:
-- what a user may do is a permission, and where they may do it is an
-- organization. Nothing here reads or writes `users.role`.
--
-- Every statement is IF NOT EXISTS / ON CONFLICT, so it is also a no-op against
-- a database that already has these tables.

CREATE TABLE IF NOT EXISTS organizations (
  id         SERIAL PRIMARY KEY,
  name       TEXT NOT NULL UNIQUE,
  npi        TEXT,
  tax_id     TEXT,
  active     BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS permissions (
  id          SERIAL PRIMARY KEY,
  code        TEXT NOT NULL UNIQUE,
  description TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS roles (
  id             SERIAL PRIMARY KEY,
  code           TEXT NOT NULL UNIQUE,
  name           TEXT NOT NULL,
  scope_type     TEXT NOT NULL CHECK (scope_type IN ('PLATFORM', 'ORGANIZATION')),
  system_defined BOOLEAN NOT NULL DEFAULT true,
  active         BOOLEAN NOT NULL DEFAULT true
);

CREATE TABLE IF NOT EXISTS role_permissions (
  role_id       INTEGER NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
  permission_id INTEGER NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
  PRIMARY KEY (role_id, permission_id)
);

-- organization_id NULL means the assignment is platform-scoped (PLATFORM_ADMIN).
-- Which of the two is legal is decided by the role's scope_type, enforced in
-- RoleAdminService, because a CHECK constraint cannot join to roles.
CREATE TABLE IF NOT EXISTS user_role_assignments (
  id              SERIAL PRIMARY KEY,
  user_id         INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role_id         INTEGER NOT NULL REFERENCES roles(id),
  organization_id INTEGER REFERENCES organizations(id),
  active          BOOLEAN NOT NULL DEFAULT true,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by      INTEGER REFERENCES users(id)
);

-- One active assignment per user + role + scope. COALESCE rather than
-- NULLS NOT DISTINCT so this also applies on Postgres older than 15.
CREATE UNIQUE INDEX IF NOT EXISTS user_role_assignments_unique_active
  ON user_role_assignments (user_id, role_id, COALESCE(organization_id, 0))
  WHERE active;

-- organization_id is nullable because platform-level actions (role changes,
-- organization creation) belong to no practice. Keep patient information out of
-- metadata; ids and codes only.
CREATE TABLE IF NOT EXISTS audit_events (
  id              SERIAL PRIMARY KEY,
  organization_id INTEGER REFERENCES organizations(id),
  user_id         INTEGER REFERENCES users(id),
  action          TEXT NOT NULL,
  entity_type     TEXT NOT NULL,
  entity_id       TEXT,
  metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,
  "timestamp"     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS audit_events_scope_idx
  ON audit_events (organization_id, "timestamp" DESC);

-- ---------------------------------------------------------------------------
-- Reference data. Codes here are the single source of truth; the matching
-- constants live in service/Permissions.kt and a test asserts the two agree.

INSERT INTO permissions (code, description) VALUES
  ('SYSTEM_VIEW',            'View platform health and configuration'),
  ('SYSTEM_CONFIGURE',       'Change platform configuration'),
  ('SYSTEM_RESET',           'Reset synthetic demo data'),

  ('ORGANIZATION_CREATE',    'Create an organization'),
  ('ORGANIZATION_VIEW',      'View an organization'),
  ('ORGANIZATION_EDIT',      'Edit an organization'),
  ('ORGANIZATION_DISABLE',   'Disable an organization'),

  ('USER_VIEW',              'View users'),
  ('USER_CREATE',            'Create users'),
  ('USER_EDIT',              'Edit users'),
  ('USER_DISABLE',           'Disable users'),
  ('USER_RESET_PASSWORD',    'Trigger a user password reset'),

  ('ROLE_VIEW',              'View roles'),
  ('ROLE_ASSIGN',            'Assign roles to users'),
  ('ROLE_MANAGE',            'Manage role definitions'),

  ('PATIENT_VIEW',           'View patients'),
  ('PATIENT_CREATE',         'Create patients'),
  ('PATIENT_EDIT',           'Edit patients'),

  ('COVERAGE_VIEW',          'View insurance coverage'),
  ('COVERAGE_EDIT',          'Edit insurance coverage'),

  ('PROVIDER_VIEW',          'View providers'),
  ('PROVIDER_MANAGE',        'Manage providers'),

  ('CLAIM_VIEW',             'View claims'),
  ('CLAIM_CREATE',           'Create claims'),
  ('CLAIM_EDIT',             'Edit a draft claim'),
  ('CLAIM_SUBMIT',           'Submit a claim'),
  ('CLAIM_RESUBMIT',         'Resubmit a corrected claim'),
  ('CLAIM_VOID',             'Void a claim'),

  ('WORK_QUEUE_VIEW',        'View the billing work queue'),
  ('WORK_QUEUE_ASSIGN',      'Assign a work item'),
  ('WORK_QUEUE_RESOLVE',     'Resolve a work item'),

  ('PAYMENT_VIEW',           'View payments and balances'),
  ('PAYMENT_RECORD',         'Record a payment'),

  ('AUDIT_VIEW',             'View the audit trail'),

  ('FHIR_IMPORT',            'Import FHIR resources'),
  ('FHIR_EXPORT',            'Export FHIR resources')
ON CONFLICT (code) DO NOTHING;

INSERT INTO roles (code, name, scope_type) VALUES
  ('PLATFORM_ADMIN',  'Platform administrator', 'PLATFORM'),
  ('PRACTICE_ADMIN',  'Practice administrator', 'ORGANIZATION'),
  ('BILLING_MANAGER', 'Billing manager',        'ORGANIZATION'),
  ('BILLER',          'Biller',                 'ORGANIZATION'),
  ('PROVIDER',        'Provider',               'ORGANIZATION'),
  ('READ_ONLY',       'Read only',              'ORGANIZATION')
ON CONFLICT (code) DO NOTHING;

-- PLATFORM_ADMIN deliberately holds no PATIENT_*, CLAIM_*, COVERAGE_*,
-- PROVIDER_*, PAYMENT_*, WORK_QUEUE_* or FHIR_* permission. Platform
-- administration must not imply access to practice clinical or billing
-- content. This matrix is provisional; Milestone 8 does the final review.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM (VALUES
  -- PLATFORM_ADMIN: platform operations only.
  ('PLATFORM_ADMIN', 'SYSTEM_VIEW'),
  ('PLATFORM_ADMIN', 'SYSTEM_CONFIGURE'),
  ('PLATFORM_ADMIN', 'SYSTEM_RESET'),
  ('PLATFORM_ADMIN', 'ORGANIZATION_CREATE'),
  ('PLATFORM_ADMIN', 'ORGANIZATION_VIEW'),
  ('PLATFORM_ADMIN', 'ORGANIZATION_EDIT'),
  ('PLATFORM_ADMIN', 'ORGANIZATION_DISABLE'),
  ('PLATFORM_ADMIN', 'USER_VIEW'),
  ('PLATFORM_ADMIN', 'USER_CREATE'),
  ('PLATFORM_ADMIN', 'USER_EDIT'),
  ('PLATFORM_ADMIN', 'USER_DISABLE'),
  ('PLATFORM_ADMIN', 'USER_RESET_PASSWORD'),
  ('PLATFORM_ADMIN', 'ROLE_VIEW'),
  ('PLATFORM_ADMIN', 'ROLE_ASSIGN'),
  ('PLATFORM_ADMIN', 'ROLE_MANAGE'),
  ('PLATFORM_ADMIN', 'AUDIT_VIEW'),

  -- PRACTICE_ADMIN: runs one practice.
  ('PRACTICE_ADMIN', 'ORGANIZATION_VIEW'),
  ('PRACTICE_ADMIN', 'USER_VIEW'),
  ('PRACTICE_ADMIN', 'USER_CREATE'),
  ('PRACTICE_ADMIN', 'USER_EDIT'),
  ('PRACTICE_ADMIN', 'USER_DISABLE'),
  ('PRACTICE_ADMIN', 'USER_RESET_PASSWORD'),
  ('PRACTICE_ADMIN', 'ROLE_VIEW'),
  ('PRACTICE_ADMIN', 'ROLE_ASSIGN'),
  ('PRACTICE_ADMIN', 'PROVIDER_VIEW'),
  ('PRACTICE_ADMIN', 'PROVIDER_MANAGE'),
  ('PRACTICE_ADMIN', 'PATIENT_VIEW'),
  ('PRACTICE_ADMIN', 'PATIENT_CREATE'),
  ('PRACTICE_ADMIN', 'PATIENT_EDIT'),
  ('PRACTICE_ADMIN', 'COVERAGE_VIEW'),
  ('PRACTICE_ADMIN', 'COVERAGE_EDIT'),
  ('PRACTICE_ADMIN', 'CLAIM_VIEW'),
  ('PRACTICE_ADMIN', 'WORK_QUEUE_VIEW'),
  ('PRACTICE_ADMIN', 'PAYMENT_VIEW'),
  ('PRACTICE_ADMIN', 'AUDIT_VIEW'),
  ('PRACTICE_ADMIN', 'FHIR_IMPORT'),
  ('PRACTICE_ADMIN', 'FHIR_EXPORT'),

  -- BILLING_MANAGER: the full billing workflow, including assignment and void.
  ('BILLING_MANAGER', 'PROVIDER_VIEW'),
  ('BILLING_MANAGER', 'PATIENT_VIEW'),
  ('BILLING_MANAGER', 'PATIENT_EDIT'),
  ('BILLING_MANAGER', 'COVERAGE_VIEW'),
  ('BILLING_MANAGER', 'COVERAGE_EDIT'),
  ('BILLING_MANAGER', 'CLAIM_VIEW'),
  ('BILLING_MANAGER', 'CLAIM_CREATE'),
  ('BILLING_MANAGER', 'CLAIM_EDIT'),
  ('BILLING_MANAGER', 'CLAIM_SUBMIT'),
  ('BILLING_MANAGER', 'CLAIM_RESUBMIT'),
  ('BILLING_MANAGER', 'CLAIM_VOID'),
  ('BILLING_MANAGER', 'WORK_QUEUE_VIEW'),
  ('BILLING_MANAGER', 'WORK_QUEUE_ASSIGN'),
  ('BILLING_MANAGER', 'WORK_QUEUE_RESOLVE'),
  ('BILLING_MANAGER', 'PAYMENT_VIEW'),
  ('BILLING_MANAGER', 'PAYMENT_RECORD'),
  ('BILLING_MANAGER', 'AUDIT_VIEW'),
  ('BILLING_MANAGER', 'FHIR_IMPORT'),
  ('BILLING_MANAGER', 'FHIR_EXPORT'),

  -- BILLER: works claims and the queue, no void, no assignment, no audit.
  ('BILLER', 'PROVIDER_VIEW'),
  ('BILLER', 'PATIENT_VIEW'),
  ('BILLER', 'COVERAGE_VIEW'),
  ('BILLER', 'CLAIM_VIEW'),
  ('BILLER', 'CLAIM_CREATE'),
  ('BILLER', 'CLAIM_EDIT'),
  ('BILLER', 'CLAIM_SUBMIT'),
  ('BILLER', 'CLAIM_RESUBMIT'),
  ('BILLER', 'WORK_QUEUE_VIEW'),
  ('BILLER', 'WORK_QUEUE_RESOLVE'),
  ('BILLER', 'PAYMENT_VIEW'),
  ('BILLER', 'PAYMENT_RECORD'),

  -- PROVIDER: sees their patients and documents encounters as drafts.
  ('PROVIDER', 'PATIENT_VIEW'),
  ('PROVIDER', 'COVERAGE_VIEW'),
  ('PROVIDER', 'CLAIM_VIEW'),
  ('PROVIDER', 'CLAIM_CREATE'),
  ('PROVIDER', 'CLAIM_EDIT'),

  -- READ_ONLY: no mutating permission anywhere.
  ('READ_ONLY', 'ORGANIZATION_VIEW'),
  ('READ_ONLY', 'ROLE_VIEW'),
  ('READ_ONLY', 'PROVIDER_VIEW'),
  ('READ_ONLY', 'PATIENT_VIEW'),
  ('READ_ONLY', 'COVERAGE_VIEW'),
  ('READ_ONLY', 'CLAIM_VIEW'),
  ('READ_ONLY', 'WORK_QUEUE_VIEW'),
  ('READ_ONLY', 'PAYMENT_VIEW'),
  ('READ_ONLY', 'AUDIT_VIEW')
) AS matrix(role_code, permission_code)
JOIN roles r ON r.code = matrix.role_code
JOIN permissions p ON p.code = matrix.permission_code
ON CONFLICT DO NOTHING;
