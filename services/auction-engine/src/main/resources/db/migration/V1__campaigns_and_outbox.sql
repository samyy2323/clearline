-- Campaign system of record plus the transactional outbox. A campaign write and its change event are one transaction;
-- a relay publishes unpublished outbox rows to Kafka (topic campaign-events) and marks them published.

CREATE TABLE campaigns (
    id              TEXT PRIMARY KEY,
    advertiser_id   TEXT        NOT NULL,
    name            TEXT        NOT NULL,
    bid_cpm_micros  BIGINT      NOT NULL CHECK (bid_cpm_micros > 0),
    budget_micros   BIGINT      NOT NULL CHECK (budget_micros > 0),
    hour_mask       INTEGER     NOT NULL DEFAULT 0,
    freq_cap        INTEGER     NOT NULL DEFAULT 0 CHECK (freq_cap >= 0),
    start_ms        BIGINT      NOT NULL,
    end_ms          BIGINT      NOT NULL CHECK (end_ms > start_ms),
    active          BOOLEAN     NOT NULL DEFAULT TRUE,
    targeting       JSONB       NOT NULL DEFAULT '{}'::jsonb,   -- geos, devices, segments, categories, sizes
    version         BIGINT      NOT NULL DEFAULT 1,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX campaigns_advertiser ON campaigns (advertiser_id);

CREATE TABLE outbox (
    id            BIGSERIAL PRIMARY KEY,
    aggregate_id  TEXT        NOT NULL,
    event_type    TEXT        NOT NULL,            -- UPSERT | DELETE
    payload       JSONB,                           -- full campaign for UPSERT, null for DELETE (Kafka tombstone)
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ
);
CREATE INDEX outbox_unpublished ON outbox (id) WHERE published_at IS NULL;
