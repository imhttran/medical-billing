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
  and are not free to redistribute. One of the seeded services is deliberately not
  priced by the synthetic payer, so a denial — and the denial queue — is reachable
  from the screens
- **Demo reset** — a deterministic synthetic practice (Jane Smith, her provider
  and her primary coverage), seeded on a development boot and rebuildable on
  demand. A reset clears the practice's claims and their payments before the
  patients and coverage they point at, so it works on a database someone has been
  using. The reset endpoint exists only where `app.env` is development or demo,
  and still requires the platform-scoped `SYSTEM_RESET` permission. The seeded
  practice is granted to the local dev login, so the billing screens have
  something to show
- **Patient screens** — patient list with name search and add, and a detail page
  that edits the patient, manages their coverage, and shows what the patient owes
  across their claims. No practice picker: the server derives it from the caller's
  grants
- **Claims and adjudication** — a claim with its diagnoses and service lines,
  a state machine that owns every status change, validation with coded issues,
  and a deterministic simulated payer that prices a submitted claim and records
  the decision. The plan's worked example comes out exactly: 150.00 charged,
  110.00 allowed, 40.00 contractual adjustment, 80.00 insurance, 30.00 patient
- **Rejection and correction** — the payer's eligibility check can refuse a claim
  outright, on the member not being covered on the date of service. A refused
  claim is not adjudicated: nothing is priced, and the payer's reason is on the
  claim. Correcting it — editing the claim, or fixing the coverage it points at —
  and resubmitting sends it back for a fresh answer. This is the one payer rule
  validation deliberately does not pre-empt, because eligibility is the payer's
  determination rather than a question about the claim itself
- **Payments and balances** — the payer's remittance is recorded with its answer,
  and the patient's payments are entered by hand against the claim they settle.
  A claim that still owes something is PARTIALLY_PAID, one that owes nothing is
  PAID. No balance is stored anywhere: every figure is the adjudication minus the
  payments, summed on read, so a payment cannot leave a total behind that
  disagrees with it. A payment larger than the balance is refused rather than
  carried as a credit
- **Claim screens** — a claim list with a one-screen way to start a claim, and a
  claim page with Validate, Mark ready and Submit plus the payer's answer, the
  payer's reason when it refused the claim, a Resubmit, and the payments with
  their balance and a form to record one
- **Work queue** — a rejection or a service the payer will not cover opens a work
  item, and the queue lists what is still open across the caller's practices with
  the claim, the patient and the payer's reason, so a row is actionable without
  leaving the list. An item can be assigned (only to someone who can work that
  queue) and resolved; resubmitting the claim resolves its items by itself.
  Assignment and both kinds of resolution are audited
- **FHIR R4 import and export** — a Bundle of Patients, Practitioners and
  Coverages is imported and reconciled by the identifier the source system used,
  so a re-import updates rather than duplicates; a claim exports as a FHIR Claim,
  and the payer's answer as an ExplanationOfBenefit carrying the adjudication
  categories and the remittance. HAPI FHIR parses and serialises; the mapping is
  ours. Payers travel contained in the resource rather than as references to a
  server we do not have, and the import is one transaction — a bundle with a
  problem in it is refused whole, with every problem listed
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
