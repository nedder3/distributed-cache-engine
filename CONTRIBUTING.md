# Contributing

Guidelines and conventions for contributing to `distributed-cache-engine`.

## Commits

Follow [Conventional Commits](https://www.conventionalcommits.org/) with explicit project **scope**:

```
feat(distributed-cache-engine): add asynchronous event bus
fix(distributed-cache-engine): resolve race condition in worker pool
docs(distributed-cache-engine): update README architecture section
test(distributed-cache-engine): add test coverage for queue edge cases
chore(distributed-cache-engine): update dependency versions
```

Allowed types: `feat`, `fix`, `docs`, `test`, `refactor`, `perf`, `chore`.
Use `BREAKING CHANGE:` in commit footers for breaking API alterations.

## Test-Driven Development (TDD)

All functionality must be covered with tests before marking work as complete. Always verify using Maven:
```bash
mvn clean verify
```

## Code Documentation

All public APIs must include comprehensive Javadoc comments detailing concurrency safety and parameters. Refer to [`docs/CODE-DOCUMENTATION.md`](docs/CODE-DOCUMENTATION.md).

## Diagrams

Mechanism and architecture diagrams reside in [`diagramas/`](diagramas/) (Mermaid format). Every diagram must illustrate data flow paths and component boundaries clearly.
