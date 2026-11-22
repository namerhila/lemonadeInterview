# https://github.com/namerhila/lemonadeInterview

Java 21 · Spring Boot 4 · Maven Wrapper.

## Commands

- Run the app: `./mvnw spring-boot:run`.
- Run all tests: `./mvnw test`.
- Run one test class: `./mvnw -Dtest=ClassNameTest test` (replace `ClassNameTest`).

## Project structure

- Root package is `com.hila.lemonadeinterview`. Only `LemonadeInterviewApplication` exists so far; create the sub-packages below as features need them, and use constructor injection.
- For HTTP endpoints, keep HTTP mapping in `controller` and request validation constraints in `dto`.
- Keep business rules in plain Java classes under `service`.
- For in-memory persistence, use a single repository class under `repository`; create it with the first feature that needs storage, then reuse it.
- Follow the API contract in the exercise. Put error handling in one `@RestControllerAdvice` class; create it with the first error case, then reuse it.

## TDD and verification

- Clarify any ambiguity that affects the expected behavior.
- For each new behavior, start with a failing JUnit Jupiter test and confirm it fails for the expected reason.
- Write the simplest code that passes, then refactor with the test green.
- Run focused tests while iterating. Run `./mvnw test` after each feature and before the demo.
- Report what passed, what failed, and anything left unverified.