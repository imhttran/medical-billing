# Features

- **Auth** — JWT login with 10-minute sliding sessions (renewed past half-life
  on every successful request; idle sessions hard-expire), scrypt password
  hashing, email verification, password
  reset, resend-verification, self-service change-password, email-code 2FA on
  new devices (trusted devices skip it)
- **RBAC** — `client` < `staff` < `admin` roles with role-gated routes;
  promotion is CLI-only so there's no self-service escalation
- **Billing RBAC** — permissions held through organization-scoped role
  assignments (`PLATFORM_ADMIN`, `PRACTICE_ADMIN`, `BILLING_MANAGER`, `BILLER`,
  `PROVIDER`, `READ_ONLY`), enforced server-side against the organization that
  owns the record. Platform administration holds no patient or claim permission,
  so it cannot reach practice content. Role assignments are audited. A practice
  administrator works claims as well as managing users, since in a small
  practice the same person does both
- **Patients, providers and coverage** — created, searched and edited per
  practice. A record in another practice answers 404 rather than 403, so the API
  never confirms that it exists
- **Terminology search** — ICD-10-CM and CPT/HCPCS lookups for the claim screen,
  seeded with a small sample set. Real code sets have their own licensing terms
  and are not free to redistribute
- **Demo reset** — a deterministic synthetic practice (Jane Smith, her provider
  and her primary coverage), seeded on a development boot and rebuildable on
  demand. The reset endpoint exists only where `app.env` is development or demo,
  and still requires the platform-scoped `SYSTEM_RESET` permission. The seeded
  practice is granted to the local dev login, so the billing screens have
  something to show
- **Patient screens** — patient list with name search and add, and a detail page
  that edits the patient and manages their coverage. No practice picker: the
  server derives it from the caller's grants
- **Claims and adjudication** — a claim with its diagnoses and service lines,
  a state machine that owns every status change, validation with coded issues,
  and a deterministic simulated payer that prices a submitted claim and records
  the decision. The plan's worked example comes out exactly: 150.00 charged,
  110.00 allowed, 40.00 contractual adjustment, 80.00 insurance, 30.00 patient
- **Claim screens** — a claim list with a one-screen way to start a claim, and a
  claim page with Validate, Mark ready and Submit plus the payer's answer
- **Onboarding gates** — forced password change and required profile block
  API access until completed
- **Admin user management** — create, delete, verify/unverify, change role,
  and trigger password resets from the dashboard
- **Email queue** — Postgres-backed queue with a bounded-retry worker; logs to
  stdout when no SMTP is configured, so dev needs no mail server
- **Enumeration-safe endpoints** — generic responses on signup/forgot-password
  so the API can't be used to probe registered emails
- **Server-side proxy** — the browser only talks to Next.js; `/api/*` is
  forwarded to the Spring Boot API, so it's never exposed directly
- **Theming** — UT Austin navy/orange, light and dark variants that follow the
  system setting
