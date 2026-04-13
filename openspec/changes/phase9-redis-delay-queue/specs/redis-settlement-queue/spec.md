## ADDED Requirements

### Requirement: Redis Sorted Set settlement queue

The system SHALL use a Redis Sorted Set with key `order:settlement:queue` to manage pending order settlements. The score SHALL be the deadline's epoch milliseconds, and the member SHALL be the orderId.

#### Scenario: Add order to queue

- **WHEN** an order is created with a deadline
- **THEN** the system SHALL execute `ZADD order:settlement:queue <deadline_epoch_ms> <orderId>`

#### Scenario: Remove order from queue

- **WHEN** an order is cancelled or deleted
- **THEN** the system SHALL execute `ZREM order:settlement:queue <orderId>`

### Requirement: Polling consumer for due orders

The system SHALL poll Redis every 1 second using `@Scheduled(fixedRate = 1000)` to find orders past their deadline.

#### Scenario: Poll for due orders

- **WHEN** the scheduled poll executes
- **THEN** the system SHALL run `ZRANGEBYSCORE order:settlement:queue 0 <current_epoch_ms> LIMIT 0 10` to retrieve up to 10 due orders

### Requirement: Atomic claim via ZREM

The system SHALL use `ZREM` to atomically claim each due order before processing. Only the instance that successfully removes the member SHALL execute settlement.

#### Scenario: Successful claim

- **WHEN** `ZREM order:settlement:queue <orderId>` returns 1
- **THEN** the system SHALL call `orderService.settleOrder(orderId)`

#### Scenario: Already claimed by another instance

- **WHEN** `ZREM order:settlement:queue <orderId>` returns 0
- **THEN** the system SHALL skip this order (no action)

### Requirement: Retry on failure

The system SHALL re-add the order to the queue if settlement fails with an unexpected exception.

#### Scenario: Settlement throws exception

- **WHEN** `settleOrder(orderId)` throws an exception
- **THEN** the system SHALL execute `ZADD order:settlement:queue <original_deadline_ms> <orderId>` to re-enqueue
- **AND** the system SHALL log the error

### Requirement: Conditional activation

The consumer SHALL only be active when `app.scheduler.enabled=true` (default: true).

#### Scenario: Scheduler disabled

- **WHEN** `app.scheduler.enabled=false`
- **THEN** the polling consumer SHALL NOT be registered as a Spring bean
