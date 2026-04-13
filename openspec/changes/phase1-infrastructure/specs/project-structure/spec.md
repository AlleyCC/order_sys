## ADDED Requirements

### Requirement: Layer-based package structure
The system SHALL organize Java source code into layer-based packages under `com.example.orderSystem`:
- `config/` — Spring configuration classes (Security, WebSocket, etc.)
- `security/` — JWT authentication filter
- `controller/` — REST API controllers
- `service/` — business logic services
- `mapper/` — MyBatis-Plus mapper interfaces
- `entity/` — database entity classes
- `dto/request/` — request DTOs with validation
- `dto/response/` — response DTOs
- `enums/` — enum definitions (OrderStatus, TradeType)
- `exception/` — custom exceptions and global exception handler
- `scheduler/` — DelayQueue settlement tasks
- `websocket/` — WebSocket handlers
- `util/` — utility classes (JWT, BCrypt, RSA)

#### Scenario: Package directories exist
- **WHEN** the project is checked out
- **THEN** each layer package directory exists under `com.example.orderSystem`

### Requirement: .gitignore excludes sensitive and generated files
The system SHALL include a `.gitignore` that excludes:
- `key/` — JWT/RSA key files
- `logs/` — application and database logs
- `target/` — Maven build output
- IDE files (`.idea/`, `*.iml`, `.vscode/`)
- `.env` — environment variable overrides
- OS files (`.DS_Store`, `Thumbs.db`)

#### Scenario: Key files are not tracked
- **WHEN** developer has `key/` directory in project root
- **THEN** `git status` does not show `key/` as untracked or modified

#### Scenario: Log files are not tracked
- **WHEN** MySQL container writes logs to `./logs/mysql/`
- **THEN** `git status` does not show `logs/` as untracked or modified

### Requirement: Service log directory structure
The system SHALL create a `./logs/app/` directory placeholder for future Spring Boot application logs (configured via logback in later phases).

#### Scenario: App log directory exists
- **WHEN** the project is checked out
- **THEN** `logs/app/` directory path is excluded by .gitignore
