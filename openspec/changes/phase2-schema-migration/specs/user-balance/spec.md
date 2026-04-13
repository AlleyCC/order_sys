## ADDED Requirements

### Requirement: users table has balance column
The `users` table SHALL have a `balance` column of type `BIGINT NOT NULL DEFAULT 0` that stores the user's current wallet balance. BIGINT is used to accommodate future fractional-unit storage (e.g. cents).

#### Scenario: New user has zero balance
- **GIVEN** a newly created user with no transactions
- **THEN** the user's `balance` is `0`

#### Scenario: Balance reflects latest transaction
- **GIVEN** user alice has transactions with latest `closing_balance = 4895`
- **WHEN** V2 migration runs
- **THEN** alice's `balance` is `4895`

### Requirement: Balance supports CAS debit pattern
The `balance` column SHALL support atomic debit via `UPDATE users SET balance = balance - ? WHERE user_id = ? AND balance >= ?`.

#### Scenario: Sufficient balance
- **GIVEN** alice has `balance = 5000`
- **WHEN** `UPDATE users SET balance = balance - 105 WHERE user_id = 'alice' AND balance >= 105`
- **THEN** `affected_rows = 1` and alice's `balance = 4895`

#### Scenario: Insufficient balance
- **GIVEN** alice has `balance = 50`
- **WHEN** `UPDATE users SET balance = balance - 105 WHERE user_id = 'alice' AND balance >= 105`
- **THEN** `affected_rows = 0` and alice's `balance` remains `50`
