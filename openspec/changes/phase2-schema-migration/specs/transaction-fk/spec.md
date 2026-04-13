## ADDED Requirements

### Requirement: transactions.order_id has FK to orders
The `transactions.order_id` column SHALL have a foreign key constraint referencing `orders.order_id` with `ON DELETE RESTRICT ON UPDATE CASCADE`.

The column remains nullable — `NULL` indicates a recharge transaction (no associated order).

#### Scenario: Debit transaction references valid order
- **GIVEN** order `ord-001` exists
- **WHEN** a debit transaction is inserted with `order_id = 'ord-001'`
- **THEN** the insert succeeds

#### Scenario: Debit transaction references invalid order
- **WHEN** a transaction is inserted with `order_id = 'ord-999'` (does not exist)
- **THEN** the insert fails with FK constraint violation

#### Scenario: Recharge transaction has no order
- **WHEN** a recharge transaction is inserted with `order_id = NULL`
- **THEN** the insert succeeds (FK ignores NULL)

#### Scenario: Cannot delete order with transactions
- **GIVEN** order `ord-001` has associated transactions
- **WHEN** attempting to delete `ord-001`
- **THEN** the delete fails with FK constraint violation (RESTRICT)
