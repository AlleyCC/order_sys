## MODIFIED Requirements

### Requirement: Cross-instance user notification delivery

The system SHALL deliver user-specific WebSocket notifications across all application instances via Redis Pub/Sub. A notification published on any instance SHALL be deliverable to the target user regardless of which instance their WebSocket session is connected to.

#### Scenario: User connected to same instance as publisher

- **WHEN** `NotificationService.sendBalanceUpdate(userId, ...)` is called on instance A
- **AND** user is connected to instance A
- **THEN** the system SHALL publish an envelope to Redis channel `websocket:notifications`
- **AND** instance A SHALL receive its own published message, resolve the local session, and deliver via `/user/{userId}/queue/balance`

#### Scenario: User connected to different instance

- **WHEN** `NotificationService.sendSettlementSuccess(userId, ...)` is called on instance A
- **AND** user is connected to instance B
- **THEN** the system SHALL publish an envelope to Redis channel `websocket:notifications`
- **AND** instance B (subscribed to the channel) SHALL receive the message and deliver via `/user/{userId}/queue/notification`
- **AND** instance A SHALL also receive the message but drop it silently (no local session)

#### Scenario: User not connected to any instance

- **WHEN** a notification is published for a disconnected user
- **THEN** the system SHALL publish the envelope to Redis
- **AND** all instances SHALL receive and silently drop it (no local session on any instance)
- **AND** no error SHALL be raised (fire-and-forget semantics)

### Requirement: Notification envelope format

The published message SHALL be a JSON envelope containing the target userId, destination path, and the original notification payload.

#### Scenario: Envelope shape

- **WHEN** `sendBalanceUpdate("alice", 4500, "下單")` is invoked
- **THEN** the published JSON SHALL contain `userId="alice"`, `destination="/queue/balance"`, and `payload` equal to the serialized `BalanceMessage` DTO

### Requirement: NotificationService public API unchanged

The public methods of `NotificationService` (`sendBalanceUpdate`, `sendSettlementSuccess`, `sendSettlementFailed`) SHALL retain their existing signatures. Only the internal implementation changes from direct `SimpMessagingTemplate` calls to Redis publish.

#### Scenario: Existing callers unaffected

- **WHEN** `OrderService` calls `notificationService.sendBalanceUpdate(...)`
- **THEN** the call site SHALL compile and behave correctly without modification

### Requirement: STOMP endpoints and subscription paths unchanged

The WebSocket endpoint `/ws`, STOMP destination prefixes (`/app`, `/topic`, `/user/queue`), and JWT CONNECT authentication SHALL NOT change.

#### Scenario: Client subscription compatibility

- **WHEN** an existing client subscribes to `/user/queue/balance` or `/user/queue/notification`
- **THEN** the client SHALL continue receiving messages without any code change

## ADDED Requirements

### Requirement: Redis subscriber relay

The system SHALL register a Redis subscriber on every instance that listens on channel `websocket:notifications` and forwards received envelopes to the local `SimpMessagingTemplate`.

#### Scenario: Relay forwards envelope to local session

- **WHEN** an envelope arrives on the Redis channel
- **THEN** the relay SHALL call `messagingTemplate.convertAndSendToUser(userId, destination, payload)`
- **AND** Spring's SimpleBroker SHALL decide whether to deliver (based on local session presence)
