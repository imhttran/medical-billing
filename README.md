# medical-billing

Medical billing for a small primary-care practice, on synthetic data:
**Next.js → Spring Boot (Kotlin) API → PostgreSQL**. Billing rather than an EHR.
The browser only ever talks to Next.js; the API is proxied server-side and never
exposed directly.

```
Browser
   ↓
Next.js        frontend/        → :3000   React UI, routing, SSR/static gen, server components
   ↓
Spring API     backend/         → :8080   Kotlin + Spring MVC + JdbcClient + JWT + scrypt + email worker
   ↓
PostgreSQL     migrations apply on boot (Flyway)
```

## Demo

A narrated walkthrough covering every screen, every billing role, and the FHIR
boundary. Seven minutes, recorded against this build on synthetic data.

https://github.com/user-attachments/assets/0e0d71c4-8103-4ab5-a439-1e1cb5925159

The 720p cut is 9MB, attached to
[issue #1](https://github.com/imhttran/medical-billing/issues/1).

## Quick Start

**Docker (primary path)** — no local JDK, Node, or Postgres needed:

```bash
make up          # or: docker compose up
```

Four services come up: Postgres (host port **5433**, so it won't fight a local
instance on 5432), Mailpit (SMTP on 1025, web UI on 8025), the API on :8080, and
the frontend on :3000. Migrations run automatically on the API's first boot.

**Native** — needs JDK 21, Node 20+, and a running PostgreSQL:

```bash
./manage.sh setup   # install dependencies (npm install, gradle build)
./manage.sh up      # start backend + frontend
```

`up` refuses to start if Postgres is down, and `DATABASE_URL` must point at
**`htt-billing-db`**. **[docs/DATABASE.md](docs/DATABASE.md)** has the DSN, setting
up a local instance, and what an older `.env` needs.

### Entry points

`make` is a thin wrapper over `./manage.sh`, which owns the steps — so there is
one implementation of each:

| Task     | Docker (primary)                         | Native                                  |
| -------- | ---------------------------------------- | --------------------------------------- |
| start    | `make up` (`./manage.sh compose:up`)     | `make native-up` (`./manage.sh up`)     |
| stop     | `make down`                              | `make native-down` (`./manage.sh down`) |
| logs     | `make logs WHAT=api`                     | `make native-logs WHAT=backend`         |
| status   | `make status` — reports either path      | `./manage.sh status`                    |
| tests    | `make test`                              | `./manage.sh test`                      |
| build    | `make build` (API jar + frontend bundle) | `./manage.sh build`                     |
| db reset | `make db-reset YES=1`                    | `./manage.sh db:reset`                  |
| re-seed  | `make db-reseed YES=1`                   | `./manage.sh db:reseed`                 |

`make help` and `./manage.sh help` list every target and subcommand;
`./manage.sh` with no argument prints the same list as `help`.

A development boot seeds one login per billing role, so the permission matrix can
be walked rather than read. Every account uses the password **Password1234!**.

| Login               | Billing role                     | What it shows                                               |
| ------------------- | -------------------------------- | ----------------------------------------------------------- |
| `admin@mail.com`    | PRACTICE_ADMIN + BILLING_MANAGER | every screen, plus user management                          |
| `manager@mail.com`  | BILLING_MANAGER                  | every billing screen, including void and queue assignment   |
| `biller@mail.com`   | BILLER                           | claims and the queue, no Audit trail, no void or assignment |
| `provider@mail.com` | PROVIDER                         | claims and patients only, nothing that touches money        |
| `readonly@mail.com` | READ_ONLY                        | every screen, no action that changes anything               |
| `platform@mail.com` | PLATFORM_ADMIN                   | the dashboard and the audit trail, and no practice content  |

The first login from a new browser asks for a 2FA code. In development it's
always `1234`, and the browser is trusted afterwards. Without SMTP configured
the mailer logs emails instead of sending them, and under compose Mailpit
collects them at http://localhost:8025.

A development boot seeds the demo practice — ten patients with their coverages,
three clinicians and fourteen claims across every state the screens show, with
three items waiting on the work queue and every submission in the audit trail.
`make db-reseed YES=1` rebuilds it after someone has changed it.

## Docs

- **[docs/FEATURE.md](docs/FEATURE.md)** — what this build does
- **[docs/PLAN.md](docs/PLAN.md)** — what it was meant to do, and what it left open
- **[docs/DATABASE.md](docs/DATABASE.md)** — install Postgres, Flyway schema, reset, tests
- **[docs/BACKEND.md](docs/BACKEND.md)** — the decisions behind the backend and the traps it can trip
- **`.env.example`** — every config variable
- **`.env.dev`** — the local development values, read by the backend and by `./manage.sh`

## Tests

`make test` (or `./manage.sh test`) runs the backend tests
(`./gradlew test`) plus the frontend typecheck (`tsc --noEmit`). It stops at the
typecheck rather than running `next build`, because a production bundle written
into `frontend/.next` pulls the rug from under a running `next dev`. Use
`make build` when you actually want the bundle. The DB-backed integration tests
need `TEST_DATABASE_URL` and skip without it; everything else runs regardless.
The counts are deliberately not written down here — they went stale every time a
slice added tests. See **[docs/DATABASE.md](docs/DATABASE.md)** for the test
database.

Committing runs the same checks through `.githooks/pre-commit`, which builds and
tests the backend, typechecks the frontend and runs the prettier check. Run
`git config core.hooksPath .githooks` once after cloning to turn it on, since
nothing activates a hook on checkout. Without that the checks run only when you
ask for them.

## Roles

Every account's identity is the billing roles it holds, and those are the only
thing that grants anything. A practice administrator manages users, claims and
patients in one practice. A billing manager runs the billing workflow. A biller
works claims without void or assignment. A provider documents encounters. A
read-only account reads without changing. A platform administrator runs the
platform and holds no practice content at all.

Roles are granted through organization-scoped assignments rather than a column on
the user row, and the permission matrix in **[docs/FEATURE.md](docs/FEATURE.md)**
is the reference for what each one may do. A practice-A user cannot read or mutate
a practice-B record.

## Backend

Kotlin 2.2 on a Java 21 toolchain, one Gradle module, source in
`backend/src/main/kotlin/com/htt/billing/`. Controllers, services and repositories
sit in their layer's package with the capability as the sub-package, so
`api/patient/PatientController.kt`, `service/patient/PatientService.kt` and
`repository/patient/PatientRepository.kt`, across the capabilities `identity`,
`security`, `audit`, `practice`, `patient`, `coverage`, `coding`, `claim`,
`adjudication`, `payment`, `workflow`, `fhir` and `demo`.

What stays in a capability package is what belongs to no layer:

| Package        | What's in it                                                                                          |
| -------------- | ----------------------------------------------------------------------------------------------------- |
| `identity`     | the platform user's plumbing: the token gate, scrypt hashing, the mailer and its queue worker, tokens |
| `security`     | the permission catalogue the roles are built from, and the roles themselves                           |
| `claim`        | the claim's status machine, its validation, and the facts it refers to                                |
| `adjudication` | the simulated payer's rules                                                                           |
| `payment`      | the one place a balance is subtracted                                                                 |
| `fhir`         | the R4 mapping and the resources it builds                                                            |
| `demo`         | the synthetic dataset                                                                                 |
| `common`       | HTTP plumbing (`Api`, the exception handler, `Inputs`) and app config                                 |

Repositories are one class per table group, raw SQL through `JdbcClient`.

A tenant-owned record is read in two steps, and they answer differently on
purpose. If the caller cannot see the owning organization at all, the response
is 404, so the API never confirms that a record exists in someone else's
practice. If they can see it but lack the permission for the action, that is a
plain 403. See `AuthorizationService.requireVisible`.

```bash
cd backend
./gradlew build     # compile, run every test, write build/libs/app.jar
./gradlew bootRun   # run the API on :8080
./gradlew test      # tests only
```

Tests sit beside the source in `backend/src/test/kotlin/` and stay in capability
packages rather than mirroring the layers. There's no Kotlin formatter in the
build, so `./manage.sh fmt` covers the frontend and the Markdown only. Why the
backend is shaped this way, and the traps a change can trip, are in
**[docs/BACKEND.md](docs/BACKEND.md)**.

## API

54 endpoints under `/api/*` — `backend/src/main/kotlin/com/htt/billing/api/` holds
every controller, one package per capability:

- **Public auth** (8): signup, verify, resend-verification, forgot-password,
  reset-password, login, login/verify (2FA code), login/resend (2FA code)
- **Self-service, any signed-in user** (4): me, profile (get/save),
  change-password
- **Staff/admin** (8): list users, create user, delete, verify/unverify,
  assign role, the roles this caller may hand out, resend verification, reset
  password. The list and every per-user route are confined to the caller's
  practices
- **Organizations** (1): list, derived from the caller's own grants
- **Patients** (5): list/search, create, read, update, and the patient's balance
  across their claims
- **Coverage** (3): list and create under a patient, update by id
- **Payers** (1): list, for the coverage picker
- **Providers** (3): list, create, read
- **Terminology** (2): search ICD-10-CM diagnoses and CPT/HCPCS procedures
- **Claims** (9): list, read, create, edit, validate, mark ready, submit, the
  claim's payments, and recording a patient payment. No endpoint sets a status —
  the transitions are named actions, and everything else goes through create and
  edit while the claim is still editable
- **Work queue** (4): list, read, assign, resolve
- **Audit** (1): the trail for the caller's practices, newest first, filterable by
  practice and action and bounded in size. Read-only — there is no endpoint that
  writes or erases an audit event
- **FHIR** (4): import a Bundle of Patients, Practitioners, Coverages and Claims;
  export a claim as a FHIR Claim; export what the payer made of it as an
  ExplanationOfBenefit and as the ClaimResponse a payer sends back. This is the
  integration boundary rather than the UI's API, so it answers with
  `application/fhir+json`
- **Demo reset** (1): rebuild the synthetic dataset. Development and demo only —
  the route is not registered anywhere else

Routes are unversioned. The plan sketches `/api/v1/...`, but the 19 endpoints
that already existed are not, and one convention beats two. Moving everything to
`/api/v1` later is a single change if versioning is ever wanted.

A tenant-owned write never takes the practice from the browser. When the caller
can write in exactly one practice — every V1 user — the server derives it from
their own grants, and a named practice is checked against those grants either
way. An account that can write in several has to say which, because the choice
is genuinely ambiguous.

The rows a write names have to belong to that practice too. A claim's patient,
provider and coverage arrive as ids in the body, so naming another practice's row
is refused where the claim is written rather than left to validation: the foreign
keys would accept it, and a claim in one practice pointing at another's patient
is not a state the API should be able to produce.

The UI has six screens against this API: `/patients` and `/patients/{id}` for
patients and their coverage, `/claims` and `/claims/{id}` for the claim workflow,
`/work-queue` for the follow-up the payers' answers create, and `/audit` for what
was done. `/claims/{id}` is where the golden path is walkable — create, validate,
mark ready, submit, and read the payer's answer — and it is what Milestone 1's
acceptance criteria describe.

`GET /api/me` returns the platform role, not billing permissions, so the UI
cannot hide what the user may not do. It shows the action and displays the
server's refusal instead; adding the caller's permissions to `/api/me` would let
it hide them.
