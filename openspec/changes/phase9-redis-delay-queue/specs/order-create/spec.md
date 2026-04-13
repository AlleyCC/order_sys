## MODIFIED Requirements

### Requirement: Schedule settlement on order creation

When an order is created with a deadline, the system SHALL add it to the Redis settlement queue instead of the in-memory Java DelayQueue.

#### Scenario: Order created with deadline

- **WHEN** a new order is created with a deadline
- **THEN** the system SHALL call `redisSettlementQueue.add(orderId, deadline)` which executes `ZADD order:settlement:queue <deadline_epoch_ms> <orderId>`
