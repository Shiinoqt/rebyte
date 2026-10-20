# Testing conventions

Settled once here (D-64) because `user-service` is the template module four more services
copy — a convention chosen ad hoc mid-phase would otherwise get copied four times.

## Naming and runner

| Suffix | Runner | Maven phase | May start Docker/Testcontainers |
|---|---|---|---|
| `*Test` | Surefire | `test` | No |
| `*IT` | Failsafe | `verify` | Yes |
| `*Tests` | Surefire | `test` | No |

`*Test` classes are unit tests: fast, hermetic, no external process. `*IT` classes are
integration tests: they may bring up Testcontainers (MySQL, RabbitMQ) and exercise real
Spring context wiring. The pre-existing `*Tests` suffix (`DiscoveryServerApplicationTests`,
`ApiGatewayApplicationTests`) is retained only for the two bootstrap `contextLoads()` classes
that already carry it — it is not used for any new test class.

## Commands

- **Fast loop, single class:** `./mvnw -pl <module> test -Dtest=<Class>`
- **Full suite, including integration tests:** `./mvnw -pl <module> verify`

`test` never triggers Testcontainers; `verify` runs the full Surefire (`test`) + Failsafe
(`integration-test` + `verify`) lifecycle, so a green `verify` is required before considering
a module's test suite complete.

## Rationale

Reference: D-64 (`.planning/phases/01-identity-and-profiles/01-CONTEXT.md`).
