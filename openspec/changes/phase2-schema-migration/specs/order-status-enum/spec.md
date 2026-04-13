## MODIFIED Requirements

### Requirement: orders.status uses VARCHAR enum names
The `orders.status` column SHALL be `VARCHAR(20) NOT NULL DEFAULT 'OPEN'` storing the enum name as a string.

Valid values: `OPEN`, `CLOSED`, `SETTLED`, `CANCELLED`, `FAILED`

#### Scenario: V1 data migrated correctly
- **GIVEN** V1 seed data has orders with TINYINT status values
- **WHEN** V2 migration runs
- **THEN** status values are converted: 0→OPEN, 1→CLOSED, 2→SETTLED, -1→CANCELLED, -2→FAILED

#### Scenario: New order defaults to OPEN
- **WHEN** a new order is inserted without specifying status
- **THEN** the status defaults to `'OPEN'`

## REMOVED Requirements

### Requirement: DELETED status removed
The `DELETED` status (previously `-3`) SHALL NOT exist. Order cancellation uses `CANCELLED` status only.

### State transitions
```
OPEN → CLOSED      (deadline 到期，截止收單)
OPEN → CANCELLED   (開團中團主主動取消)
CLOSED → SETTLED   (結算成功)
CLOSED → FAILED    (結算失敗，如餘額不足)
CLOSED → CANCELLED (截止後團主仍可取消)
```
