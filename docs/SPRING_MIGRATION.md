# Spring Migration (legacy backend → Spring Boot backend)

Why the backend looks the way it does. The current behavior lives in the other
docs; this one records the decisions and the traps that are easy to undo by
accident.

The Spring backend replaced a legacy one in the same place — same directory
(`backend/`), same port, same database — behind a frozen API contract: routes,
status codes and JSON shapes are unchanged, defined by `frontend/next.config.ts`'s
proxy and the ported test suite. The legacy implementation is gone from the tree;
git history still has it.

That backend was then ported from Java to Kotlin in place: same packages, same
class names, same 39 tests, and the same frozen contract. `backend/src` holds no
Java now, and git history has it. The Kotlin-specific decisions and traps are
part of this document rather than a second one, because the reasons the Java
code was shaped that way mostly survive the translation.

## Decisions

- **Gradle (Kotlin DSL) with a committed wrapper** (9.7.1) — no system Gradle, so
  the build is the same in CI, in Docker, and on a laptop. The wrapper jar is
  committed on purpose; `.gitignore` is written not to swallow it.
- **Java 21 toolchain, Kotlin as the source language** — the build declares
  `jvmToolchain(21)`, so the JDK that launches Gradle (25 here) doesn't have to
  be the one that compiles, and the Kotlin compiler targets 21 either way. The
  Dockerfile builds on a JDK 21 image for the same reason.
- **`JdbcClient` raw SQL, not JPA or jOOQ** — the backend it replaces was sqlx
  with hand-written SQL, so keeping the queries literal kept the port mechanical
  and the generated SQL predictable. It is also load-bearing: the
  `RETURNING`/`ON CONFLICT` patterns and the 23505 → "Email is already
  registered" mapping have no clean ORM equivalent, and they are part of the
  contract.
- **No Spring Security** — auth is a `HandlerMethodArgumentResolver` for the
  `AuthUser` parameter (declaring the parameter _is_ the gate), and session
  sliding is a `OncePerRequestFilter`. Spring Security's filter chain and DSL are
  a lot of machinery for two checks.
- **scrypt kept byte-compatible** — the hash format is `hex(salt):hex(key)`,
  N=16384/r=8/p=1, 16-byte salt, 64-byte key, and the salt input is the hex
  string itself. Existing password hashes have to verify unchanged, so there was
  no room to switch to BCrypt. A pinned test hashes against a Go-era value.
- **jjwt for HS256** — same `{email, exp, iat}` claims as the backend it
  replaced, so a token from either validates on the other during an overlap.
- **One Gradle module** with `api` / `service` / `repository` packages (plus
  `config` and `cli`). The old backend was one crate with modules; splitting into
  Gradle subprojects would buy nothing here.
- **Config from environment variables only, no Spring profiles** — `NODE_ENV`
  picks dev behavior, and `EnvFiles` resolves `.env` over `.env.dev`.
- **Flyway, not the old hand-rolled runner** — the schema and its history are
  documented in [DATABASE.md](DATABASE.md).

## Kotlin port

The Java-to-Kotlin pass happened in place too: same packages, same class names,
same 39 tests. These are the decisions inside it that are worth not undoing.

- **Kotlin 2.2.21, with the BOM's `kotlin.version` overridden to match** — Spring
  Boot 3.5.3 manages `kotlin-stdlib` and `kotlin-reflect` at the version it was
  tested against (1.9.25), while Gradle 9 needs a Kotlin plugin of at least
  2.2.20. Setting `extra["kotlin.version"]` to the plugin's version keeps the
  compiler, stdlib and reflect on one version instead of resolving a mismatch.
- **`kotlin("plugin.spring")`, not hand-written `open`** — Kotlin classes are
  final by default, and Spring has to subclass `@Configuration`, `@Component` and
  `@Service` classes. The plugin's allopen preset handles that, so removing the
  plugin breaks context startup rather than failing at compile time.
- **`kotlin-reflect` and `jackson-module-kotlin` are load-bearing** — reflect is
  what lets a `JdbcClient` row mapper resolve a Kotlin primary constructor, and
  what gives Spring the parameter names it needs for config binding
  (`-java-parameters` is set as a second, cheaper source for those names). The
  Jackson module is what lets the request-body classes be filled from their
  defaults. Both look like conveniences and neither is.
- **Nullable types instead of `Optional`** — repository lookups return `T?`, so
  the ported `orElse(null)` and `orElseThrow(...)` became `?:` and `?: throw`.
  `Optional` is gone from the codebase.
- **`AppProperties` defaults every property and repairs blanks in
  `@PostConstruct`** — normalise still runs after binding (binding an empty env
  var must not clobber the dev fallback), and the production `JWT_SECRET` guard
  still fires there. One deliberate change: `smtpPort` and `maxAttempts` are
  `Int`, not `Integer`, so an _empty_ `SMTP_PORT` or `MAX_ATTEMPTS` is now a
  binding error instead of falling back to the default. Nothing in the repo sets
  either one empty, and the alternative was a nullable knob leaking into every
  caller.
- **`-Xjsr305=strict`** — Spring's `@Nullable` becomes a real nullable type, so
  the `HandlerMethodArgumentResolver` override takes `ModelAndViewContainer?` and
  `WebDataBinderFactory?` instead of leaving anyone to guess.

## Class map

| Legacy (removed)                | Spring (backend/)                             |
| ------------------------------- | --------------------------------------------- |
| axum                            | Spring MVC (`@RestController`)                |
| sqlx (`PgPool`)                 | `JdbcClient` + HikariCP                       |
| serde / serde_json              | Jackson                                       |
| jsonwebtoken (HS256)            | jjwt (HS256)                                  |
| scrypt crate                    | BouncyCastle `SCrypt`                         |
| lettre                          | `spring-boot-starter-mail` (`JavaMailSender`) |
| `tokio::time::interval` worker  | `@Scheduled(fixedDelay = 3000)`               |
| hand-rolled `.env` loader       | `EnvFiles` `EnvironmentPostProcessor`         |
| `raw_sql` migrations            | Flyway (`classpath:db/migration`)             |
| `set-role` subcommand           | `main()` intercept + `SetRoleCommand`         |
| `AppError` / `respond` / `fail` | `Api` helpers + `@RestControllerAdvice`       |
| `requireAuth` extractor         | `AuthUserArgumentResolver`                    |
| renewed-token middleware        | `SessionRenewalFilter`                        |

The right-hand column names the Spring classes the backend uses today. The table
records which stack each responsibility came from, not which language it is
written in.

## Parity gotchas (the hard parts)

These are the places the obvious implementation is wrong. Each is pinned by a
test; changing one breaks behavior the contract guarantees.

1. **Two response body shapes, not interchangeable** — the bare
   `{"message": …}` (`Api.msg`) and `{"success": false, "message": …}`
   (`Api.fail`). The bare form is what the auth gates, the profile form, the
   user-management mutations and the `parseId`/framework 400s return; the
   `success:false` form is what signup, login, password reset and admin user
   creation return. Same statuses can carry either shape, so tests assert exact
   bodies.
2. **Lenient request decoding** — a missing or unparsable body decodes to an
   empty request object; route-level validation is what produces the 400. That
   means handlers take `@RequestBody(required = false) byte[]` and decode via
   `Api.decode`, never a typed `@RequestBody` DTO (Spring would 400 on the
   malformed JSON itself, before validation could).
3. **`X-Renewed-Token` only on 2xx** — the renewed token rides on successful
   responses and never on errors, so a failed request can't slide a session. The
   filter applies it the moment the status is known, not after the body flushes.
4. **`JWT_SECRET` must be at least 32 bytes** — the app checks this while
   building `JwtService` and refuses to start on a shorter secret, because a
   short HMAC key is brute-forceable. This is stricter than the backend it
   replaced, which signed with a secret of any length, so a deployment carrying a
   short `JWT_SECRET` has to lengthen it. The dev fallback value also differs, so
   sessions minted on the fallback are not interchangeable between the two.
5. **The role gate lives in the controllers** — deliberately, because the
   original checked the role _before_ parsing the path id. Order is visible:
   `DELETE /api/users/abc` as a non-admin is a 403, not a 400.
6. **`created_at` serialises as an ISO-8601 UTC instant** — the admin user list
   carries `createdAt` as e.g. `2026-01-01T00:00:00Z`. Jackson's timestamp output
   is disabled, so an `Instant` renders the way the legacy backend's
   `DateTime<Utc>` did.
7. **The `"to"` column is quoted** — `to` is reserved in SQL, so `email_queue`
   queries write `"to"`.
8. **Request-body classes must default every field** — the lenient decoder
   falls back to a no-arg constructor, and Kotlin only synthesizes one when
   every primary-constructor parameter has a default. Drop a `= ""` from a body
   class and malformed-input handling changes from a 400 to a 500.
9. **`@JsonSetter(nulls = Nulls.SKIP)` is what makes `null` mean "absent"** — an
   explicit JSON `null` is skipped so the field keeps its default. Drop the
   annotation and a non-null Kotlin parameter rejects that null, the decode
   fails, and `Api.decode` falls back to a _completely empty_ body rather than
   ignoring the one field — so the symptom is a wrong validation message, not a
   crash. The `field:` use-site target on these annotations is cosmetic, the
   annotation itself is not.
10. **Row mapping keys off the column aliases** — `JdbcClient.query(Class)`
    resolves the Kotlin primary constructor and matches its parameter names to
    the aliased columns, so every `AS "camelCase"` in the SQL is part of the
    contract. It also depends on `kotlin-reflect` staying on the runtime
    classpath, and it is why repositories are the one place a column alias is
    worth naming explicitly.
11. **`Api` has its own `ObjectMapper`** — the request decoder is deliberately
    not Spring's mapper, so the Kotlin module has to be registered on that one
    too. Responses going through Spring's auto-configured mapper is not enough.
12. **Path variables are named explicitly** — `@PathVariable("id")` rather than
    bare `@PathVariable`. It costs nothing and removes a dependency on
    Kotlin parameter-name discovery at the one place a wrong name is a runtime
    500 instead of a compile error.
13. **`EnvFiles` is registered in `spring.factories`, not in a `.imports` file** —
    Spring Boot reads `EnvironmentPostProcessor` implementations from the
    `org.springframework.boot.env.EnvironmentPostProcessor` key in
    `META-INF/spring.factories`. A `META-INF/spring/<type>.imports` file, which is
    the mechanism auto-configuration and friends use, is ignored silently for this
    interface: no error, no warning, and the `.env` files simply never load. The
    Java version shipped exactly that mistake, so `.env` support had never actually
    worked until it was corrected. `EnvironmentPostProcessorsFactory` in Spring
    Boot 3.5 has `fromSpringFactories` and no `fromImports` at all.
