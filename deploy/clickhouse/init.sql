-- Kafka -> ClickHouse pipeline. Events land on the `ad-events` topic (JSON, one object per line); ClickHouse consumes them with a
-- Kafka-engine table and two materialized views: the raw event log, and per-minute AggregatingMergeTree rollups.
-- Flagged (fraud / duplicate) events are kept in the raw log and counted separately; rollups only count clean events (flag = '').

CREATE DATABASE IF NOT EXISTS clearline;

CREATE TABLE IF NOT EXISTS clearline.ad_events_queue
(
    type               LowCardinality(String),
    id                 String,
    ts_ms              UInt64,
    campaign           String,
    advertiser         String,
    publisher          String,
    geo                LowCardinality(String),
    device             LowCardinality(String),
    category           LowCardinality(String),
    arm                LowCardinality(String),
    user               String,
    hour               UInt8,
    price_cpm_micros   UInt64,
    spend_micros       UInt64,
    pctr               Float64,
    flag               LowCardinality(String)
)
ENGINE = Kafka
SETTINGS kafka_broker_list = 'kafka:9092',
         kafka_topic_list = 'ad-events',
         kafka_group_name = 'clickhouse-ad-events',
         kafka_format = 'JSONEachRow',
         kafka_num_consumers = 1,
         kafka_skip_broken_messages = 100;

CREATE TABLE IF NOT EXISTS clearline.ad_events
(
    type               LowCardinality(String),
    id                 String,
    ts                 DateTime,
    campaign           String,
    advertiser         String,
    publisher          String,
    geo                LowCardinality(String),
    device             LowCardinality(String),
    category           LowCardinality(String),
    arm                LowCardinality(String),
    user               String,
    hour               UInt8,
    price_cpm_micros   UInt64,
    spend_micros       UInt64,
    pctr               Float64,
    flag               LowCardinality(String)
)
ENGINE = MergeTree
PARTITION BY toYYYYMMDD(ts)
ORDER BY (campaign, ts)
TTL ts + INTERVAL 30 DAY;

CREATE MATERIALIZED VIEW IF NOT EXISTS clearline.ad_events_mv TO clearline.ad_events AS
SELECT type, id, toDateTime(intDiv(ts_ms, 1000)) AS ts, campaign, advertiser, publisher, geo, device, category, arm, user, hour,
       price_cpm_micros, spend_micros, pctr, flag
FROM clearline.ad_events_queue;

CREATE TABLE IF NOT EXISTS clearline.rollup_minute
(
    minute        DateTime,
    campaign      String,
    advertiser    String,
    publisher     String,
    geo           LowCardinality(String),
    device        LowCardinality(String),
    arm           LowCardinality(String),
    wins          AggregateFunction(sum, UInt64),
    imps          AggregateFunction(sum, UInt64),
    clicks        AggregateFunction(sum, UInt64),
    spend_micros  AggregateFunction(sum, UInt64),
    pctr_sum      AggregateFunction(sum, Float64)
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMMDD(minute)
ORDER BY (campaign, minute, publisher, geo, device, arm)
TTL minute + INTERVAL 90 DAY;

CREATE MATERIALIZED VIEW IF NOT EXISTS clearline.rollup_minute_mv TO clearline.rollup_minute AS
SELECT toStartOfMinute(toDateTime(intDiv(ts_ms, 1000))) AS minute,
       campaign, advertiser, publisher, geo, device, arm,
       sumState(toUInt64(type = 'win'))   AS wins,
       sumState(toUInt64(type = 'imp'))   AS imps,
       sumState(toUInt64(type = 'click')) AS clicks,
       sumState(spend_micros)             AS spend_micros,
       sumState(if(type = 'imp', pctr, 0.0)) AS pctr_sum
FROM clearline.ad_events_queue
WHERE flag = ''
GROUP BY minute, campaign, advertiser, publisher, geo, device, arm;

-- counts of flagged events by reason (fraud, duplicates), per minute
CREATE TABLE IF NOT EXISTS clearline.flags_minute
(
    minute  DateTime,
    flag    LowCardinality(String),
    n       UInt64
)
ENGINE = SummingMergeTree
ORDER BY (minute, flag);

CREATE MATERIALIZED VIEW IF NOT EXISTS clearline.flags_minute_mv TO clearline.flags_minute AS
SELECT toStartOfMinute(toDateTime(intDiv(ts_ms, 1000))) AS minute, flag, count() AS n
FROM clearline.ad_events_queue
WHERE flag != ''
GROUP BY minute, flag;
