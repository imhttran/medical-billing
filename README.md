# spring-template

Full-stack auth template: **Next.js → Spring Boot (Kotlin) API → PostgreSQL**. The
browser only ever talks to Next.js; the API is proxied server-side and never
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

**Kubernetes** — the same stack on Rancher Desktop's cluster, needs Kubernetes
enabled (_Preferences → Kubernetes_) and Docker running:

```bash
make k8s-up         # or: ./manage.sh k8s:up
open http://template.localhost
```

`k8s/` holds plain manifests for the same four services; see
**[docs/KUBERNETES.md](docs/KUBERNETES.md)**.

### Entry points

`make` is a thin wrapper over `./manage.sh`, which owns the steps — so there is
one implementation of each:

| Task     | Docker (primary)                          | Native                                  |
| -------- | ----------------------------------------- | --------------------------------------- |
| start    | `make up` (`./manage.sh compose:up`)      | `make native-up` (`./manage.sh up`)     |
| stop     | `make down`                               | `make native-down` (`./manage.sh down`) |
| logs     | `make logs WHAT=api`                      | `make native-logs WHAT=backend`         |
| status   | `make status` — reports either path       | `./manage.sh status`                    |
| tests    | `make test`                               | `./manage.sh test`                      |
| build    | `make build` (API jar + frontend bundle)  | `./manage.sh build`                     |
| db reset | `make db-reset YES=1`                     | `./manage.sh db:reset`                  |
| re-seed  | `make db-reseed YES=1`                    | `./manage.sh db:reseed`                 |
| role     | `make role EMAIL=you@mail.com ROLE=admin` | `./manage.sh role <email> <role>`       |

`make help` and `./manage.sh help` list every target and subcommand;
`./manage.sh` with no argument prints the same list as `help`. Kubernetes has its own targets
(`make k8s-up`, `make k8s-rebuild`, `make k8s-status`, …) — see
**[docs/KUBERNETES.md](docs/KUBERNETES.md)**.

Dev admin: **admin@mail.com** / **Password1234!** — the first login from a new
browser asks for a 2FA code; in development it's always `1234`, and the browser
is trusted afterwards. Without SMTP configured the mailer logs emails instead of
sending them; under compose, Mailpit collects them at http://localhost:8025.

## Docs

- **[docs/FEATURE.md](docs/FEATURE.md)** — what this build does
- **[docs/DATABASE.md](docs/DATABASE.md)** — install Postgres, Flyway schema, reset, tests
- **[docs/KUBERNETES.md](docs/KUBERNETES.md)** — the Rancher Desktop cluster: images, ingress, commands
- **`.env.example`** — every config variable
- **`.env.dev`** — the local development values, read by the backend and by `./manage.sh`

Background, for how the backend got here (each stack replaced rather than run
alongside): **[docs/SPRING_MIGRATION.md](docs/SPRING_MIGRATION.md)** — the legacy
backend to Spring port, and the later Java to Kotlin port.

## Tests

`make test` (or `./manage.sh test`) runs the backend tests
(`./gradlew test`) plus the frontend build. The DB-backed integration tests need
`TEST_DATABASE_URL` and skip without it; everything else runs regardless. The
counts are deliberately not written down here — they went stale every time a
slice added tests. See **[docs/DATABASE.md](docs/DATABASE.md)** for the test
database.

## Roles

`client` < `staff` < `admin`. Grant via CLI only:

```bash
make role EMAIL=you@email.com ROLE=admin
# or: ./manage.sh role you@email.com admin  /  ./manage.sh → [8]
```

That is the platform gate the original endpoints use. Billing has its own
authorization layer beside it: permissions held through organization-scoped
role assignments, checked server-side. A practice-A user cannot read or mutate a
practice-B record. See **[docs/FEATURE.md](docs/FEATURE.md)**.

## Backend

Kotlin 2.2 on a Java 21 toolchain, one Gradle module, source in
`backend/src/main/kotlin/com/htt/billing/`, organized by capability rather than
by layer — each package owns its controller, service and repository:

| Package    | What's in it                                                                                                                    |
| ---------- | ------------------------------------------------------------------------------------------------------------------------------- |
| `identity` | the platform user: auth, tokens, JWT, scrypt hashing, the mail queue, profiles, users, the ranked `client`/`staff`/`admin` gate |
| `security` | billing authorization: permissions, roles, role assignments, and the org-scoped checks                                          |
| `audit`    | the audit trail                                                                                                                 |
| `practice` | organizations and providers, the practice and who works in it                                                                   |
| `patient`  | patients                                                                                                                        |
| `coverage` | payers and a patient's coverage                                                                                                 |
| `coding`   | ICD-10-CM and CPT/HCPCS search                                                                                                  |
| `common`   | HTTP plumbing (`Api`, the exception handler, `Inputs`) and app config                                                           |

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

Tests sit beside the source in `backend/src/test/kotlin/`. There's no Kotlin
formatter in the build, so `./manage.sh format` covers the frontend and the
Markdown only. Why the backend is shaped this way, and the traps a change can
trip, are in **[docs/SPRING_MIGRATION.md](docs/SPRING_MIGRATION.md)**.

## API

51 endpoints under `/api/*` — see the controllers in
`backend/src/main/kotlin/com/htt/billing/identity/`, `.../practice/`,
`.../patient/`, `.../coverage/`, `.../coding/`, `.../claim/`, `.../payment/`,
`.../workflow/`, `.../fhir/`:

- **Public auth** (8): signup, verify, resend-verification, forgot-password,
  reset-password, login, login/verify (2FA code), login/resend (2FA code)
- **Self-service, any signed-in user** (4): me, profile (get/save),
  change-password
- **Staff/admin** (7): list users, create user, delete, verify/unverify,
  change role, resend verification, reset password
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
- **FHIR** (3): import a Bundle of Patients, Practitioners and Coverages; export a
  claim as a FHIR Claim; export what the payer made of it as an
  ExplanationOfBenefit. This is the integration boundary rather than the UI's API,
  so it answers with `application/fhir+json`
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

The UI has five screens against this API: `/patients` and `/patients/{id}` for
patients and their coverage, `/claims` and `/claims/{id}` for the claim workflow,
and `/work-queue` for the follow-up the payers' answers create. `/claims/{id}` is
where the golden path is walkable — create, validate, mark ready, submit, and read
the payer's answer — and it is what Milestone 1's acceptance criteria describe.

`GET /api/me` returns the platform role, not billing permissions, so the UI
cannot hide what the user may not do. It shows the action and displays the
server's refusal instead; adding the caller's permissions to `/api/me` would let
it hide them.
