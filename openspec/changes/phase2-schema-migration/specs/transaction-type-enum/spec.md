## MODIFIED Requirements

### Requirement: transactions.type uses VARCHAR enum names
The `transactions.type` column SHALL be `VARCHAR(20) NOT NULL` storing the enum name as a string.

Valid values: `DEBIT`, `RECHARGE` (future: `REFUND`)

#### Scenario: V1 data migrated correctly
- **GIVEN** V1 seed data has transactions with TINYINT type values
- **WHEN** V2 migration runs
- **THEN** type values are converted: 1→DEBIT, 2→RECHARGE

#### Scenario: Debit transaction
- **WHEN** a settlement deducts from user balance
- **THEN** the transaction is inserted with `type = 'DEBIT'`

#### Scenario: Recharge transaction
- **WHEN** admin tops up a user's balance
- **THEN** the transaction is inserted with `type = 'RECHARGE'`
