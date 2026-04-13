## REMOVED Requirements

### Requirement: token_blacklist table removed
The `token_blacklist` table SHALL be dropped. JWT invalidation is replaced by refresh token revocation.

## ADDED Requirements

### Requirement: refresh_tokens table stores refresh tokens
The system SHALL have a `refresh_tokens` table with the following structure:

| Column | Type | Constraint | Description |
|--------|------|-----------|-------------|
| token_id | VARCHAR(36) | PK | UUID |
| user_id | VARCHAR(20) | FK → users (CASCADE), NOT NULL | Token owner |
| revoked | TINYINT(1) | NOT NULL DEFAULT 0 | 0=active, 1=revoked |
| expire_time | DATETIME | NOT NULL | Token expiration (7 days from creation) |
| created_at | DATETIME | NOT NULL DEFAULT CURRENT_TIMESTAMP | Creation time |

Indexes:
- `idx_user_id (user_id)` — lookup active tokens by user
- `idx_expire_time (expire_time)` — efficient cleanup of expired tokens

#### Scenario: Active refresh token exists
- **GIVEN** user alice logs in
- **WHEN** a refresh token is issued
- **THEN** a row is inserted with `revoked = 0` and `expire_time` set to 7 days from now

#### Scenario: Revoked on logout
- **GIVEN** alice has an active refresh token `rt-001`
- **WHEN** alice logs out
- **THEN** `rt-001.revoked` is set to `1`

#### Scenario: Multi-device login
- **GIVEN** alice logs in on her phone (refresh token `rt-001`) and laptop (refresh token `rt-002`)
- **WHEN** alice logs out on her phone
- **THEN** only `rt-001.revoked` is set to `1`; `rt-002` remains active
- **AND** alice can continue using her laptop without re-login

#### Scenario: Cascade delete with user
- **GIVEN** alice has refresh tokens
- **WHEN** alice's user record is deleted
- **THEN** all of alice's refresh tokens are automatically deleted (CASCADE)

#### Scenario: Expired token cleanup
- **GIVEN** there are refresh tokens with `expire_time < NOW()`
- **WHEN** `DELETE FROM refresh_tokens WHERE expire_time < NOW()` is executed
- **THEN** all expired tokens are removed efficiently via `idx_expire_time`

### Requirement: refresh_tokens indexed by user_id
The `refresh_tokens` table SHALL have an index on `user_id` to support efficient lookup of a user's active tokens.

### Requirement: refresh_tokens indexed by expire_time
The `refresh_tokens` table SHALL have an index on `expire_time` to support efficient periodic cleanup of expired tokens.
