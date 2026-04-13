## ADDED Requirements

### Requirement: Docker Compose provides MySQL 8.0 service
The system SHALL provide a `docker-compose.yml` that starts a MySQL 8.0 container with database `paypool`, charset `utf8mb4`, and collation `utf8mb4_0900_ai_ci`.

#### Scenario: Start MySQL container
- **WHEN** developer runs `docker compose up -d`
- **THEN** a MySQL 8.0 container starts on port 3306 with database `paypool` ready to accept connections

#### Scenario: MySQL uses correct charset
- **WHEN** MySQL container is running
- **THEN** the default charset is `utf8mb4` and collation is `utf8mb4_0900_ai_ci`

### Requirement: DB data persists via named volume
The system SHALL use a Docker named volume `mysql-data` mapped to `/var/lib/mysql` so that database data survives container restarts.

#### Scenario: Data survives container restart
- **WHEN** developer runs `docker compose down` followed by `docker compose up -d`
- **THEN** all database data (tables, rows) persists from the previous session

#### Scenario: Full data wipe
- **WHEN** developer runs `docker compose down -v`
- **THEN** the named volume is removed and next `docker compose up -d` starts with a fresh database

### Requirement: DB logs are accessible via bind mount
The system SHALL bind mount `./logs/mysql` to the MySQL log directory so developers can browse logs directly on the host filesystem.

#### Scenario: Error log accessible on host
- **WHEN** MySQL container is running
- **THEN** the MySQL error log file is visible under `./logs/mysql/` on the host

#### Scenario: Slow query log accessible on host
- **WHEN** a query takes longer than 1 second
- **THEN** the query is logged to the slow query log file under `./logs/mysql/`

### Requirement: MySQL enables error log and slow query log
The system SHALL provide a custom `my.cnf` that enables the error log and slow query log with a threshold of 1 second. General log SHALL remain disabled.

#### Scenario: Slow query log is enabled with 1s threshold
- **WHEN** MySQL container starts with the custom config
- **THEN** `slow_query_log = 1` and `long_query_time = 1` are active

#### Scenario: General log is disabled
- **WHEN** MySQL container starts with the custom config
- **THEN** `general_log = 0` (or not set, defaulting to off)

### Requirement: MySQL root credentials via environment variables
The system SHALL configure MySQL root password and database name via environment variables in docker-compose.yml, with sensible development defaults.

#### Scenario: Default development credentials
- **WHEN** developer runs `docker compose up -d` without any `.env` file
- **THEN** MySQL starts with the default root password and `paypool` database as defined in docker-compose.yml
