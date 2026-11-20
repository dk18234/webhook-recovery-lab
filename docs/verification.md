# Verification evidence

Build date: November 20, 2026. Environment: shared Linux x86_64 development host, Temurin JDK 21.0.13.1, Spring Boot 3.5.16, PostgreSQL 16.16.

| Check | Observed result |
| --- | --- |
| Java / PostgreSQL integration suite | 26 tests passed, zero failures, zero errors |
| Browser simulation domain suite | 7 tests passed |
| JavaScript module syntax | Passed |
| Executable Spring Boot JAR | Built successfully |
| Signed HTTP duplicate delivery | 60 deliveries at concurrency 12 → one 202, 59 duplicate 200s, one ledger entry, 10 credits |
| Actual JVM termination and restart | Accepted event survived SIGKILL; restart processed it to SUCCEEDED with one ledger entry |
| Prometheus endpoint | Authenticated metrics returned the processing timer and result counters |
| Dashboard walkthrough | Overview, scenario navigation, crash-after-commit reconciliation, terminal failure inspection |
| Portfolio TypeScript and Vite build | Passed |
| Local Docker image build | Not run: no Docker engine in the build environment |

The included GitHub Actions workflow separately builds and exercises Docker Compose when it runs. Do not infer a successful hosted CI run from this local report; inspect the repository's Actions result.

Machine-readable results:

- [Suite summary](evidence/verification.json)
- [HTTP correctness check](evidence/http-correctness.json)
- [Process restart proof](evidence/process-restart.json)
- [Metrics sample](evidence/metrics.txt)

The HTTP demonstration recorded approximately 0.294 seconds for delivery responses on this shared development host. This is a bounded correctness check, not a throughput or latency service-level objective. It excludes a production workload, real external dependencies, sustained capacity measurement, and deployment-level high availability.

Database concurrency is tested by the Java integration suite. The browser companion mirrors domain transitions with local storage and does not establish database guarantees.
