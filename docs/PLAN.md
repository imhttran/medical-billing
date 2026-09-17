# Medical Billing Application — V1 Plan

## Status

V1 is built, on synthetic data only. Every milestone in section 19 landed and every task in section 23 is done, so the definition of success in section 24 holds. What the milestones left open is below. `docs/FEATURE.md` says what the build does, `README.md` has the endpoint list and the local logins, and `docs/DATABASE.md` covers the connection and how the schema is managed.

Four plan lines are answered differently on purpose.

- The API is unversioned, `/api/*`, where the plan sketches `/api/v1/...`.
- Resubmission is `POST /api/claims/{id}/submit`, which picks `CLAIM_SUBMIT` or `CLAIM_RESUBMIT` from the claim's current status. There is no `/correct` route, because correcting a claim is editing it and sending it again.
- The simulated payer refuses on one rule, the member not being covered on the date of service, and denies on one, a service it has no rate for. The rest of section 10's examples belong to claim validation, which is where they were built.
- Claim history is the audit trail rather than a per-claim timeline. `GET /api/audit-events` and the `/audit` screen carry each submission and resubmission.
- Role assignment is `POST /api/users/{id}/roles` and creation takes the role with the account, because an account with no assignment belongs to no practice and would be invisible to the administrator who just made it. `GET /api/users/assignable-roles` is what populates the picker.

Not built:

- The Milestone 0 health endpoint. Nothing polls one, and there is no `/health` route.
- Inbound ClaimResponse import. Our simulated payers send nothing back, so the reverse of the export waits on a real requirement.
- The R4 `diagnosis` element on FHIR export. HAPI's `diagnosisCodeableConcept` spelling is what goes out. The import accepts both, so only conformant senders see the difference.

Two open permission decisions, both written up in the permission matrix section of `docs/FEATURE.md`:

- `PLATFORM_ADMIN` holds `ROLE_ASSIGN` at platform scope, so it can grant itself a practice role.
- `PRACTICE_ADMIN` holds `PAYMENT_VIEW` but not `PAYMENT_RECORD`, so a practice whose only administrator is a practice admin cannot record a patient payment.

## 1. Purpose

Build a simple medical billing application for a small U.S. primary-care practice. The application is billing-focused rather than an EHR. It supports manual claim entry and is designed to accept/export FHIR R4 data later.

V1 uses only synthetic patient, provider, payer, and insurance data.

The product should be designed so it can eventually support a medical billing company managing multiple practices.

## 2. Product Principles

1. Keep V1 small and understandable.
2. Model the billing domain explicitly instead of using FHIR as the internal database model.
3. Use FHIR R4 primarily as an import/export interoperability format.
4. Build one modular monolith before considering microservices.
5. Design for multiple organizations/tenants from day one, even though V1 operates as one practice.
6. Simulate payer behavior before integrating with real payers or clearinghouses.
7. Use synthetic data only in V1.
8. Optimize for correctness, auditability, and maintainability before scale.
9. Do not add Kafka, Redis, Elasticsearch, Kubernetes, or other infrastructure until there is a demonstrated need.

## 3. V1 Scope

### Included

- Small primary-care practice
- Practice users and providers
- RBAC with platform-level and organization-scoped roles
- Predefined V1 roles:
  - PLATFORM_ADMIN
  - PRACTICE_ADMIN
  - BILLING_MANAGER
  - BILLER
  - PROVIDER
  - READ_ONLY
- Permission-based authorization rather than hard-coded role checks
- Organization-scoped role assignments
- Development/demo environment reset capability for PLATFORM_ADMIN
- Synthetic patients
- One primary insurance coverage per claim
- Manual billing entry
- Searchable ICD-10-CM diagnosis codes
- Searchable CPT/HCPCS procedure/service codes
- Claim creation
- Claim validation
- Claim submission
- Simulated payer
- Realistic adjudication
- Allowed amount
- Contractual adjustment
- Insurance responsibility
- Patient responsibility
- Claim rejection
- Claim denial
- Claim correction
- Claim resubmission
- Billing work queue
- Insurance payment tracking
- Patient balance tracking
- Manual patient payment recording
- FHIR R4 import/export
- Audit trail for important billing actions
- Organization/tenant boundaries

### Explicitly Out of Scope for V1

- Full EHR/clinical documentation
- AI-assisted medical coding
- Real PHI
- Real insurance submission
- Clearinghouse integration
- X12 837 claim transmission
- X12 835 remittance processing
- Secondary insurance / coordination of benefits
- Credit-card processing
- Patient portal
- Payment plans
- Collections
- Real eligibility verification
- Prior authorization
- Microservices
- Kafka
- Redis
- Elasticsearch
- Kubernetes

## 4. Technology Stack

### Frontend

- Next.js
- TypeScript

### Backend

- Kotlin
- Spring Boot
- Gradle with Kotlin DSL
- Spring Web
- Spring JDBC, `JdbcClient` with hand-written SQL rather than an ORM
- HAPI FHIR R4
- Flyway
- JUnit

### Database

- PostgreSQL

### Local Development

Docker Compose should initially run PostgreSQL. The backend and frontend may run directly from their development environments.

## 5. High-Level Architecture

```text
                         External EHR / Integration
                                  |
                              FHIR R4
                                  |
                                  v
                       +----------------------+
                       | FHIR Import / Export |
                       |      HAPI FHIR       |
                       +----------+-----------+
                                  |
                            Mapping Layer
                                  |
                                  v
+-------------+          +----------------------+          +------------+
|   Next.js   |  REST    | Kotlin / Spring Boot |   JPA    | PostgreSQL |
|  Frontend   +--------->|   Billing Domain     +--------->|            |
+-------------+          +----------+-----------+          +------------+
                                   |
                                   v
                            +--------------+
                            | Fake /       |
                            | Simulated    |
                            | Payer        |
                            +--------------+
```

FHIR is an interoperability boundary. The normal Next.js UI communicates with the application's own REST API.

## 6. Backend Architecture

Use a modular monolith organized by business capability.

Suggested package structure:

```text
backend/
├── build.gradle.kts
├── settings.gradle.kts
└── src/
    ├── main/
    │   ├── kotlin/com/example/medicalbilling/
    │   │   ├── practice/
    │   │   ├── patient/
    │   │   ├── coding/
    │   │   ├── claim/
    │   │   ├── adjudication/
    │   │   ├── payment/
    │   │   ├── workflow/
    │   │   ├── fhir/
    │   │   │   ├── import/
    │   │   │   ├── export/
    │   │   │   └── mapping/
    │   │   ├── audit/
    │   │   ├── security/
    │   │   └── common/
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/
    └── test/
```

Do not split these modules into independently deployed services in V1.

## 7. Core Domain Model

### Organization

Represents a medical practice.

Important fields:

- id
- name
- npi
- tax_id
- active
- created_at
- updated_at

All tenant-owned business records must be associated with an organization.

### User

Users are platform identities. Do not store a single `role` or a mandatory
`organization_id` directly on the user as the authorization model.

- id
- email
- display_name
- active
- created_at
- updated_at

### Role

A named collection of permissions.

- id
- code
- name
- scope_type
- system_defined
- active

Initial roles:

```text
PLATFORM_ADMIN
PRACTICE_ADMIN
BILLING_MANAGER
BILLER
PROVIDER
READ_ONLY
```

`PLATFORM_ADMIN` is platform-scoped. The other V1 business roles are normally
organization-scoped.

### Permission

Fine-grained capability used by backend authorization.

Initial permission families:

```text
SYSTEM_VIEW
SYSTEM_CONFIGURE
SYSTEM_RESET

ORGANIZATION_CREATE
ORGANIZATION_VIEW
ORGANIZATION_EDIT
ORGANIZATION_DISABLE

USER_VIEW
USER_CREATE
USER_EDIT
USER_DISABLE
USER_RESET_PASSWORD

ROLE_VIEW
ROLE_ASSIGN
ROLE_MANAGE

PATIENT_VIEW
PATIENT_CREATE
PATIENT_EDIT

COVERAGE_VIEW
COVERAGE_EDIT

PROVIDER_VIEW
PROVIDER_MANAGE

CLAIM_VIEW
CLAIM_CREATE
CLAIM_EDIT
CLAIM_SUBMIT
CLAIM_RESUBMIT
CLAIM_VOID

WORK_QUEUE_VIEW
WORK_QUEUE_ASSIGN
WORK_QUEUE_RESOLVE

PAYMENT_VIEW
PAYMENT_RECORD

AUDIT_VIEW

FHIR_IMPORT
FHIR_EXPORT
```

### RolePermission

Many-to-many mapping between roles and permissions.

- role_id
- permission_id

### UserRoleAssignment

Assigns a role to a user at the appropriate scope.

- id
- user_id
- role_id
- organization_id (nullable for platform-scoped roles)
- active
- created_at
- created_by

Conceptually:

```text
User
  |
  v
UserRoleAssignment ----> Organization (when organization scoped)
  |
  v
Role
  |
  v
RolePermission
  |
  v
Permission
```

A user may have different roles in different organizations.

Example:

```text
User: Alex

BILLING_MANAGER @ Practice A
BILLER          @ Practice B
```

Authorization must evaluate both permission and resource scope.

### Provider

- id
- organization_id
- user_id (nullable)
- first_name
- last_name
- npi
- taxonomy_code
- active

### Patient

- id
- organization_id
- external_id
- first_name
- last_name
- date_of_birth
- sex
- address fields
- phone
- active
- created_at
- updated_at

### Coverage

A patient may eventually have multiple coverages, but V1 processes one primary coverage per claim.

- id
- organization_id
- patient_id
- payer_id
- member_id
- group_number
- subscriber_name
- relationship_to_subscriber
- effective_date
- termination_date
- priority
- active

### Payer

V1 uses synthetic payers.

- id
- name
- payer_code
- active

### DiagnosisCode

Searchable ICD-10-CM terminology.

- code
- description
- active

### ProcedureCode

Searchable CPT/HCPCS terminology.

- code
- code_system
- description
- default_charge (optional development convenience)
- active

CPT licensing and distribution requirements must be reviewed before using CPT content outside an appropriate licensed environment. Do not assume FHIR supplies CPT or ICD terminology.

### Claim

- id
- organization_id
- claim_number
- patient_id
- provider_id
- coverage_id
- payer_id
- service_date
- status
- total_charge
- submission_version
- submitted_at
- created_at
- updated_at

### ClaimDiagnosis

- id
- claim_id
- diagnosis_code
- sequence

### ClaimLine

- id
- claim_id
- line_number
- procedure_code
- quantity
- charge_amount
- allowed_amount
- adjustment_amount
- payer_amount
- patient_responsibility
- status

### Adjudication

- id
- claim_id
- adjudicated_at
- outcome
- total_charge
- total_allowed
- total_adjustment
- payer_responsibility
- patient_responsibility

### AdjudicationLine

Stores line-level adjudication results.

### WorkItem

Represents billing work requiring attention.

- id
- organization_id
- claim_id
- type
- status
- reason_code
- reason_text
- assigned_user_id
- created_at
- resolved_at

Types may include:

```text
REJECTION
DENIAL
CORRECTION
FOLLOW_UP
```

### InsurancePayment

- id
- organization_id
- claim_id
- amount
- payment_date
- reference_number

### PatientPayment

- id
- organization_id
- patient_id
- claim_id
- amount
- payment_method
- payment_date
- reference_number

### AuditEvent

- id
- organization_id
- user_id
- action
- entity_type
- entity_id
- timestamp
- metadata

Avoid placing unnecessary patient information in audit metadata.

## 8. Claim State Machine

Initial claim statuses:

```text
DRAFT
READY
SUBMITTED
REJECTED
ACCEPTED
ADJUDICATED
DENIED
PARTIALLY_PAID
PAID
CORRECTED
RESUBMITTED
CLOSED
```

Example flow:

```text
DRAFT
  |
  v
READY
  |
  v
SUBMITTED
  |
  +------------------+
  |                  |
  v                  v
REJECTED           ACCEPTED
  |                  |
  v                  v
CORRECTED        ADJUDICATED
  |               /       \
  v              v         v
RESUBMITTED    DENIED   PARTIALLY_PAID / PAID
```

State transitions must be enforced by application services/domain logic rather than allowing arbitrary status updates through CRUD APIs.

## 9. Claim Validation

Before a claim becomes READY or SUBMITTED, validate at minimum:

- patient exists
- provider exists
- primary coverage exists
- payer exists
- member ID exists
- service date exists
- at least one diagnosis exists
- at least one claim line exists
- every claim line has a procedure code
- every claim line has a positive charge
- organization boundaries match
- claim totals reconcile with line totals

Validation results should return structured error codes suitable for both the UI and future integrations.

## 10. Payer Simulator

Create a deterministic simulated payer so tests are repeatable.

The simulator should support:

### Clean Claim

Example:

```text
CPT:                    99213
Charge:                  $150
Allowed:                 $110
Contractual adjustment:   $40
Payer responsibility:     $80
Patient responsibility:   $30
```

### Rejection

Examples:

- missing member ID
- missing diagnosis
- invalid required data
- inactive coverage

A rejected claim is not adjudicated.

### Denial

Examples:

- service not covered
- invalid diagnosis/procedure combination in the simulator
- duplicate service rule

A denied claim is accepted for processing and then adjudicated as denied.

### Partial Payment

Support a claim where some lines are paid and another line is denied.

Keep simulator rules simple and visible in code. Do not attempt to model real payer contracts in V1.

## 11. Work Queue

The work queue is a primary biller workflow.

Suggested categories:

- Needs Attention
- Rejected
- Denied
- Ready to Submit
- Submitted / Awaiting Payer
- Patient Balance

Example:

```text
Claim     Patient       Status       Problem
10034     Jane Smith    REJECTED     Missing member ID
10039     John Doe      DENIED       Service not covered
10042     Mary Jones    READY        Ready to submit
```

A biller must be able to:

1. Open a work item.
2. Understand the reason.
3. Navigate to the affected claim/data.
4. Correct the problem.
5. Resubmit when appropriate.
6. Resolve the work item.
7. See the history in the audit trail.

## 12. REST API

The frontend uses application-specific REST endpoints.

### Patients

```text
GET    /api/v1/patients
POST   /api/v1/patients
GET    /api/v1/patients/{id}
PUT    /api/v1/patients/{id}
```

### Coverage

```text
GET    /api/v1/patients/{patientId}/coverages
POST   /api/v1/patients/{patientId}/coverages
PUT    /api/v1/coverages/{id}
```

### Providers

```text
GET    /api/v1/providers
POST   /api/v1/providers
GET    /api/v1/providers/{id}
```

### Terminology

```text
GET /api/v1/codes/diagnoses?query=
GET /api/v1/codes/procedures?query=
```

### Claims

```text
GET    /api/v1/claims
POST   /api/v1/claims
GET    /api/v1/claims/{id}
PUT    /api/v1/claims/{id}

POST   /api/v1/claims/{id}/validate
POST   /api/v1/claims/{id}/ready
POST   /api/v1/claims/{id}/submit
POST   /api/v1/claims/{id}/correct
POST   /api/v1/claims/{id}/resubmit
```

Do not expose a generic endpoint that directly changes claim status.

### Work Queue

```text
GET    /api/v1/work-items
GET    /api/v1/work-items/{id}
POST   /api/v1/work-items/{id}/assign
POST   /api/v1/work-items/{id}/resolve
```

### Payments

```text
GET    /api/v1/claims/{id}/payments
POST   /api/v1/claims/{id}/patient-payments
GET    /api/v1/patients/{id}/balance
```

### FHIR Integration

FHIR is import/export rather than the normal UI API.

Initial endpoints may be application-oriented:

```text
POST /api/v1/integrations/fhir/import
GET  /api/v1/integrations/fhir/claims/{claimId}
GET  /api/v1/integrations/fhir/eob/{claimId}
```

The exact integration API should be refined when the first FHIR milestone begins.

## 13. FHIR Mapping

Use HAPI FHIR R4 for parsing, serialization, and validation where appropriate.

Initial mapping targets:

| FHIR Resource        | Internal Domain                        |
| -------------------- | -------------------------------------- |
| Organization         | Organization                           |
| Practitioner         | Provider                               |
| Patient              | Patient                                |
| Coverage             | Coverage                               |
| Encounter            | Source/reference information as needed |
| Claim                | Claim + ClaimLine + diagnoses          |
| ClaimResponse        | Adjudication                           |
| ExplanationOfBenefit | Exported billing/adjudication result   |

Do not force the internal database schema to mirror FHIR.

Preserve external identifiers and source-system identifiers so imported resources can be reconciled rather than duplicated.

## 14. Frontend Screens

### V1 Screens

1. Login
2. Billing Dashboard
3. Patient List
4. Patient Detail
5. Coverage Editor
6. Claim List
7. Create/Edit Claim
8. Claim Detail
9. Claim Adjudication Result
10. Work Queue
11. Work Item Detail
12. Patient Balance / Payment Entry
13. Basic Practice Administration

### Claim Creation Screen

The claim editor should emphasize the biller's workflow:

```text
Patient
Provider
Service Date
Primary Insurance

Diagnoses
+ Search ICD-10-CM

Services
+ Search CPT/HCPCS

Procedure    Qty    Charge
99213         1      150.00

Total                150.00

[Save Draft] [Validate]
```

After successful validation:

```text
[Submit Claim]
```

## 15. Multi-Tenant Preparation

Every tenant-owned record must be scoped to an organization.

The backend must never rely only on an organization ID supplied by the browser. Authorization should derive the user's permitted organization(s) and enforce the boundary server-side.

V1 may expose only one practice to a user.

Future model:

```text
Billing Company
    |
    +-- Practice A
    +-- Practice B
    +-- Practice C

Biller
    |
    +-- assigned to one or more practices
```

Do not implement billing-company administration in V1.

## 16. Security, RBAC, and Data Handling

V1 uses synthetic data only, but authorization is a foundational architectural
requirement and is implemented in Milestone 0.

### Authorization Model

Use:

```text
User -> Role Assignment -> Role -> Permissions
              |
              +-> Organization Scope
```

Do not implement authorization with checks such as:

```text
if user.role == BILLER
```

Application services should authorize against permissions such as
`CLAIM_SUBMIT` and verify that the user is allowed to act on the organization
that owns the resource.

For example, submitting a claim requires:

1. authenticated user
2. claim organization resolved server-side
3. active role assignment applicable to that organization
4. role containing `CLAIM_SUBMIT`
5. any additional domain-specific restrictions

### Platform Administrator

`PLATFORM_ADMIN` is the highest platform operations role.

It may be allowed to:

- create and disable organizations
- create the initial Practice Admin
- manage platform configuration
- manage predefined roles and permissions
- view platform health
- reset synthetic demo data in permitted environments
- perform support administration

Platform administration does **not** automatically grant unrestricted access to
patient or claim content. Administrative access to healthcare data should remain
a separate, explicit capability. Future production support access should be
explicit, time-bounded where practical, and audited.

### Practice Administrator

`PRACTICE_ADMIN` is scoped to one practice and may manage:

- practice users
- organization role assignments
- providers
- practice configuration

It cannot administer unrelated organizations.

### Organization Isolation

Tenant isolation must be enforced server-side.

Never rely solely on an organization ID supplied by the browser.

Avoid repository access patterns such as:

```text
findById(claimId)
```

when accessing tenant-owned resources.

Prefer organization-aware access such as:

```text
findByIdAndOrganizationId(claimId, authorizedOrganizationId)
```

A user assigned to Practice A must not be able to read or mutate Practice B
records even if the user knows a Practice B resource UUID.

### Spring Security

Use Spring Security for authentication and method/request authorization.

Permission-oriented service authorization may use mechanisms such as
`@PreAuthorize`, backed by an application authorization service.

Example concept:

```text
canAccessClaim(authentication, claimId, "CLAIM_SUBMIT")
```

Authorization should not depend exclusively on controller annotations.
Organization ownership must also be checked when loading and mutating data.

### RBAC Administration

V1 uses predefined roles. Custom role creation is deferred.

Practice Admins may assign permitted organization-scoped roles to users in their
own organization.

Role and permission changes must be audited.

### Demo/System Reset

Development and demo environments need a deterministic reset mechanism so the
golden-path and error scenarios can be repeatedly exercised.

Example admin action:

```text
Reset Demo Data
    |
    +-- delete synthetic claims
    +-- delete adjudications
    +-- delete work items
    +-- delete synthetic payments
    +-- reset synthetic patients/coverage
    +-- recreate demo practice
    +-- recreate demo providers
    +-- recreate Jane Smith
    +-- recreate demo payer
    +-- recreate test terminology data
```

Reset must require both:

```text
Environment is DEV or DEMO
AND
User has SYSTEM_RESET
```

The reset endpoint must not exist in a normal production profile. Prefer an
environment/profile restriction in addition to RBAC, for example a Spring
`dev`/`demo` profile.

A production `PLATFORM_ADMIN` must not be able to erase the environment through
the demo reset mechanism.

Reset operations themselves should be audited when audit storage is retained
outside the reset scope.

### Data Handling Baseline

Establish these patterns from the beginning:

- authentication
- permission-based authorization
- organization-level data isolation
- server-side authorization checks
- audit important billing and access-management actions
- avoid patient information in normal application logs
- use environment variables/secrets rather than committed credentials
- version-control database migrations
- validate all external input
- sanitize error responses
- use TLS in deployed environments
- test cross-tenant access failures

Before introducing real PHI, perform a dedicated HIPAA/security architecture
review. V1 architecture alone must not be represented as making the system HIPAA
compliant.

## 17. Database Migration Strategy

Use Flyway.

Example:

```text
V001__create_organizations.sql
V002__create_users.sql
V003__create_rbac.sql
V004__seed_roles_and_permissions.sql
V005__create_providers.sql
V006__create_patients.sql
V007__create_payers_and_coverages.sql
V008__create_code_tables.sql
V009__create_claims.sql
V010__create_adjudications.sql
V011__create_work_items.sql
V012__create_payments.sql
V013__create_audit_events.sql
```

Avoid auto-generating production schemas from JPA. Hibernate schema validation may be used to detect mapping mismatches.

## 18. Testing Strategy

### Unit Tests

Focus on domain rules:

- claim validation
- claim state transitions
- adjudication calculations
- balance calculations
- payer simulator rules
- organization access rules

### Integration Tests

Use Testcontainers with PostgreSQL.

Test:

- repositories
- REST endpoints
- security boundaries
- Flyway migrations
- transactional workflows

### End-to-End Tests

Cover the primary billing scenarios:

1. clean claim
2. rejected claim
3. corrected/resubmitted claim
4. denied claim
5. partial payment
6. patient balance
7. manual patient payment

## 19. Implementation Milestones

All eight milestones are built. The status section at the top names what they left open.

### Milestone 0 — Project Foundation + RBAC

Deliver:

- repository structure
- Next.js frontend
- Kotlin/Spring Boot backend
- Gradle Kotlin DSL
- PostgreSQL
- Docker Compose
- Flyway
- health endpoint
- frontend-to-backend connectivity
- Spring Security foundation
- User, Role, Permission, RolePermission, and UserRoleAssignment model
- predefined V1 roles and permissions
- PLATFORM_ADMIN
- organization-scoped authorization
- permission-based service checks
- cross-organization access tests
- dev/demo-only `SYSTEM_RESET` capability
- deterministic demo-data reseeding
- baseline audit events for role assignments and administrative actions
- baseline tests

### Milestone 1 — Golden Path Claim

Deliver one complete scenario:

```text
Jane Smith
    |
Primary Coverage
    |
Create Claim
    |
ICD-10-CM J06.9
CPT 99213
Charge $150
    |
Submit
    |
Simulated Payer
    |
Allowed      $110
Payer         $80
Patient       $30
Adjustment    $40
```

This is the most important initial milestone.

### Milestone 2 — Patients, Providers, Coverage and Coding

Deliver:

- patient management
- provider management
- primary coverage
- payer configuration/seeds
- searchable diagnosis codes
- searchable procedure codes

### Milestone 3 — Claim Workflow

Deliver:

- draft claims
- validation
- ready state
- submission
- state machine
- claim history

### Milestone 4 — Payer Simulation

Deliver:

- accepted claims
- rejection rules
- adjudication
- denial rules
- partial payment
- line-level results

### Milestone 5 — Work Queue

Deliver:

- rejection queue
- denial queue
- correction workflow
- resubmission
- work-item resolution

### Milestone 6 — Payments and Balances

Deliver:

- insurance payment record
- patient responsibility
- patient balance
- manual patient payment
- balance reconciliation

### Milestone 7 — FHIR R4

Deliver:

- HAPI FHIR integration
- FHIR parsing/validation
- Patient import
- Practitioner import
- Coverage import
- Claim import/export
- ClaimResponse mapping
- ExplanationOfBenefit export
- external identifier reconciliation

### Milestone 8 — Security and Audit Hardening

RBAC begins in Milestone 0. This milestone hardens it for the completed V1.

Deliver:

- final permission matrix review
- final organization isolation tests
- role-assignment administration review
- PLATFORM_ADMIN boundary tests
- verify platform administration does not implicitly grant patient/claim access
- audit history
- log review
- security integration tests
- verify demo reset endpoints are unavailable outside dev/demo profiles

## 20. Milestone 0 RBAC Acceptance Criteria

RBAC foundation is complete when:

1. A user can authenticate.
2. Roles and permissions are stored independently.
3. A user can have an organization-scoped role assignment.
4. A user can have different roles in different organizations.
5. A BILLER with `CLAIM_SUBMIT` can submit an authorized claim.
6. A user without `CLAIM_SUBMIT` receives an authorization failure.
7. A BILLER in Practice A cannot read a Practice B patient.
8. A BILLER in Practice A cannot mutate a Practice B claim.
9. PRACTICE_ADMIN can manage permitted users/assignments only within their practice.
10. PLATFORM_ADMIN can perform allowed platform administration.
11. PLATFORM_ADMIN does not implicitly receive patient/claim content access.
12. Role and role-assignment changes create audit events.
13. `SYSTEM_RESET` succeeds only in dev/demo and only for an authorized platform user.
14. Demo reset recreates the deterministic synthetic dataset.
15. The demo reset endpoint is unavailable in the production profile.

## 21. Milestone 1 Acceptance Criteria

Milestone 1 is complete when a developer can:

1. Start PostgreSQL locally.
2. Start the Spring Boot backend.
3. Start the Next.js frontend.
4. Open the application in a browser.
5. Select synthetic patient Jane Smith.
6. Select her synthetic primary insurance.
7. Create a claim.
8. Add diagnosis `J06.9`.
9. Add procedure `99213`.
10. Enter a $150 charge.
11. Validate the claim successfully.
12. Submit the claim.
13. Have the simulated payer adjudicate it.
14. Display:
    - Charge: $150
    - Allowed: $110
    - Adjustment: $40
    - Insurance responsibility: $80
    - Patient responsibility: $30
15. Persist the claim and adjudication to PostgreSQL.
16. Reload the browser and see the same result.
17. Have automated tests verify the adjudication math.

Do not add rejection/denial complexity until this golden path works end-to-end.

## 22. Initial Repository Layout

```text
medical-billing/
├── docs/PLAN.md
├── README.md
├── docker-compose.yml
├── frontend/
│   ├── package.json
│   ├── next.config.*
│   ├── src/
│   └── ...
└── backend/
    ├── build.gradle.kts
    ├── settings.gradle.kts
    ├── src/
    │   ├── main/
    │   │   ├── kotlin/com/htt/billing/
    │   │   │   ├── api/           controllers and request bodies, by capability
    │   │   │   ├── service/       application services, by capability
    │   │   │   ├── repository/    the SQL, by capability
    │   │   │   └── <capability>/  domain types that belong to no layer
    │   │   └── resources/
    │   │       └── db/migration/
    │   └── test/
    └── ...
```

Controllers, services and repositories each sit in their layer's package with the capability as the sub-package, so `repository/patient/PatientRepository.kt` and `api/patient/PatientController.kt`. Everything else stays in its capability package.

## 23. First Development Tasks

Start with these tasks in order:

- [x] Create repository structure.
- [x] Generate Kotlin/Spring Boot application.
- [x] Generate Next.js/TypeScript application.
- [x] Add PostgreSQL to Docker Compose.
- [x] Configure Spring Boot database connectivity.
- [x] Add Flyway.
- [x] Add Spring Security foundation.
- [x] Create User, Role, Permission, RolePermission, and UserRoleAssignment schema.
- [x] Seed predefined roles and permissions.
- [x] Implement organization-scoped authorization service.
- [x] Seed a development PLATFORM_ADMIN.
- [x] Implement dev/demo-only SYSTEM_RESET endpoint.
- [x] Add cross-organization authorization tests.
- [x] Create Organization, Provider, Patient, Payer, and Coverage schema.
- [x] Seed one synthetic organization.
- [x] Seed one synthetic provider.
- [x] Seed Jane Smith.
- [x] Seed one synthetic payer and primary coverage.
- [x] Create Claim, ClaimDiagnosis, and ClaimLine schema.
- [x] Implement claim creation API.
- [x] Implement claim validation service.
- [x] Implement claim state machine.
- [x] Implement deterministic payer simulator.
- [x] Implement adjudication persistence.
- [x] Build minimal patient/claim UI.
- [x] Display adjudication result.
- [x] Add golden-path integration test.

## 24. Definition of V1 Success

V1 succeeds when a biller can use synthetic data to move a primary-care claim from creation through payer adjudication, handle rejected or denied claims through a work queue, record resulting payments and patient responsibility, and exchange selected billing information through FHIR R4 import/export.

The application does not need to submit a real insurance claim to demonstrate V1 success.

## 25. Future Directions

After V1, evaluate these individually rather than assuming they are required:

- real clearinghouse integration
- X12 837
- X12 835
- eligibility (270/271)
- secondary coverage / coordination of benefits
- patient statements
- electronic patient payments
- ERA posting
- denial analytics
- prior authorization
- richer FHIR integration
- multi-practice billing-company UI
- AI-assisted coding with human review
- automated claim scrubbing
- reporting
- external identity provider
- production PHI/HIPAA readiness

Each should be introduced only after its business requirement is clear.
