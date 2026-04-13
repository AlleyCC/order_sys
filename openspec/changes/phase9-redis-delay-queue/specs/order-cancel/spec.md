## MODIFIED Requirements

### Requirement: Remove settlement on order cancellation

When an order is cancelled or deleted, the system SHALL remove it from the Redis settlement queue instead of the in-memory Java DelayQueue.

#### Scenario: Order cancelled

- **WHEN** an order is cancelled via `cancelOrder`
- **THEN** the system SHALL call `redisSettlementQueue.remove(orderId)` which executes `ZREM order:settlement:queue <orderId>`

#### Scenario: Order deleted (all items)

- **WHEN** an order is deleted via `deleteUserOrder` with itemId="all"
- **THEN** the system SHALL call `redisSettlementQueue.remove(orderId)` which executes `ZREM order:settlement:queue <orderId>`
