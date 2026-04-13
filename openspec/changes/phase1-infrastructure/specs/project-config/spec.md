## ADDED Requirements

### Requirement: pom.xml includes all required dependencies
The system SHALL include the following Maven dependencies in pom.xml:
- `spring-boot-starter-web` (existing)
- `spring-boot-starter-security`
- `spring-boot-starter-websocket`
- `spring-boot-starter-validation`
- `mybatis-plus-spring-boot3-starter`
- `mysql-connector-j` (runtime scope)
- `flyway-core` + `flyway-mysql` (existing)
- `jjwt-api`, `jjwt-impl`, `jjwt-jackson` (0.12.x)
- `bcprov-jdk18on` (Bouncy Castle)
- `lombok` (existing)
- `spring-boot-starter-test` (test scope, existing)
- `testcontainers` mysql + junit-jupiter (test scope)

#### Scenario: Maven build succeeds
- **WHEN** developer runs `mvn compile`
- **THEN** the build succeeds with all dependencies resolved

### Requirement: Application connects to MySQL via application.properties
The system SHALL configure `application.properties` with MySQL connection to `localhost:3306/paypool`, Flyway migration, MyBatis-Plus settings, JWT key paths (`../key/*`), and `server.port=8591`.

#### Scenario: App starts and connects to MySQL
- **WHEN** MySQL container is running and developer runs `mvn spring-boot:run`
- **THEN** Spring Boot starts on port 8591, Flyway executes migrations, and the app is ready to serve requests

#### Scenario: Flyway creates schema on first run
- **WHEN** app starts against a fresh `paypool` database
- **THEN** Flyway executes `V1__init_schema.sql` creating all tables and seed data

### Requirement: Dev profile overrides key paths
The system SHALL provide `application-dev.properties` that overrides JWT/RSA key paths to `./key/*` for local development convenience.

#### Scenario: Dev profile uses local key paths
- **WHEN** app starts with `--spring.profiles.active=dev`
- **THEN** key paths resolve to `./key/private_key.pem`, `./key/public_key.pem`, `./key/password_private.key`, `./key/password_public.key`

### Requirement: MyBatis-Plus configuration
The system SHALL configure MyBatis-Plus with mapper XML location, entity scan package, and appropriate settings for the project.

#### Scenario: MyBatis-Plus recognizes mapper interfaces
- **WHEN** app starts with MyBatis-Plus configured
- **THEN** `@MapperScan` is set to scan the correct base package for mapper interfaces

### Requirement: Spring Security defaults to permit-all temporarily
The system SHALL include a minimal Spring Security configuration that permits all requests, so that Phase 1 can verify connectivity without auth blocking. Actual security config will be implemented in Phase 2a.

#### Scenario: All endpoints are accessible without auth
- **WHEN** Phase 1 is complete and app is running
- **THEN** all HTTP endpoints return responses without requiring authentication
