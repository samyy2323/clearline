# ADR 0004: Transactional outbox for campaign-change events

Status: accepted (lite implementation)

## Context
A campaign write and its change event must not diverge: publishing after commit can lose the event, publishing before can
announce a write that never happened.

## Decision
`CampaignService.save/delete` updates the table and appends an event to the outbox under one lock (standing in for one database
transaction). A relay (`@Scheduled`, 100 ms) drains the outbox and applies events to the index in order. In a Postgres/Kafka
deployment the outbox is a table written in the same transaction and a relay publishes it to a topic that every engine consumes.

## Consequences
Delivery is at-least-once and ordered per relay; applying an upsert or delete twice is idempotent. Lite mode keeps the table in
memory, so campaigns are re-seeded on restart. The Postgres + Flyway + Kafka variant is not built (ADR 0006).
