## MODIFIED Requirements

### Requirement: Settlement consumption via Redis polling

The settlement consumer SHALL use Redis polling instead of a blocking daemon thread. The `recoverFromDb()` startup logic SHALL be removed since Redis persists the queue across restarts.

#### Scenario: Normal settlement flow

- **WHEN** an order's deadline has passed and the polling consumer picks it up
- **THEN** the system SHALL atomically claim it via `ZREM` and call `settleOrder(orderId)`

#### Scenario: Application restart

- **WHEN** the application restarts
- **THEN** the pending orders SHALL still be in the Redis Sorted Set and will be consumed on the next poll cycle
- **AND** no `recoverFromDb()` logic SHALL be needed

## REMOVED Requirements

### Requirement: In-memory DelayQueue settlement

**Reason:** Replaced by Redis Sorted Set for distributed support.
**Migration:** `OrderSettlementQueue`, `OrderSettlementTask`, `OrderSettlementConsumer` are replaced by `RedisSettlementQueue` and `RedisSettlementConsumer`.
