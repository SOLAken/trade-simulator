CREATE EXTENSION IF NOT EXISTS timescaledb;

-- 原始 tick：价格单位分；qty 为该 tick 成交量（非累计）
CREATE TABLE IF NOT EXISTS tick (
  ts          TIMESTAMPTZ NOT NULL,
  symbol      TEXT        NOT NULL,
  price       BIGINT      NOT NULL,
  qty         BIGINT      NOT NULL,
  trading_day INT         NOT NULL
);
SELECT create_hypertable('tick', by_range('ts', INTERVAL '1 hour'), if_not_exists => TRUE);
CREATE INDEX IF NOT EXISTS idx_tick_symbol_ts ON tick (symbol, ts DESC);

-- 压缩：1 天前的 chunk 按 symbol 分段列式压缩
ALTER TABLE tick SET (timescaledb.compress, timescaledb.compress_segmentby = 'symbol', timescaledb.compress_orderby = 'ts DESC');
SELECT add_compression_policy('tick', INTERVAL '1 day', if_not_exists => TRUE);

-- 1 分钟 K 线：连续聚合，增量物化 + 实时聚合（materialized_only=false 时未物化部分实时计算）
CREATE MATERIALIZED VIEW IF NOT EXISTS kline_1m
WITH (timescaledb.continuous, timescaledb.materialized_only = false) AS
SELECT time_bucket('1 minute', ts) AS bucket,
       symbol,
       first(price, ts) AS open,
       max(price)       AS high,
       min(price)       AS low,
       last(price, ts)  AS close,
       sum(qty)         AS volume
FROM tick
GROUP BY bucket, symbol
WITH NO DATA;

SELECT add_continuous_aggregate_policy('kline_1m',
  start_offset => INTERVAL '1 hour', end_offset => INTERVAL '1 minute',
  schedule_interval => INTERVAL '30 seconds', if_not_exists => TRUE);

-- 收益曲线：rank-service 每 10 秒为每个参赛者记录总资产
CREATE TABLE IF NOT EXISTS equity (
  ts          TIMESTAMPTZ NOT NULL,
  contest_id  BIGINT      NOT NULL,
  user_id     BIGINT      NOT NULL,
  total       BIGINT      NOT NULL
);
SELECT create_hypertable('equity', by_range('ts', INTERVAL '1 day'), if_not_exists => TRUE);
CREATE INDEX IF NOT EXISTS idx_equity_user ON equity (contest_id, user_id, ts DESC);
