-- Phase 21: idempotent repeatable seed - one sample EMA-crossover DRAFT so
-- the registry (and later the Phase 23 signal engine + Phase 26 MVP demo)
-- has something to publish on a fresh `ay up`. The checksum below is the
-- SHA-256 of the canonical JSONB (D18) - SeedConsistencyTest re-derives it
-- from the embedded YAML and fails the build on drift.

DO $do$
DECLARE
  sid UUID;
BEGIN
  IF EXISTS (SELECT 1 FROM strategies WHERE slug = 'minimal-ema-crossover') THEN
    RETURN;
  END IF;

  INSERT INTO strategies (slug, name, description, author, tags)
  VALUES (
    'minimal-ema-crossover',
    'Minimal EMA Crossover',
    'Seed: the MVP demo strategy - EMA 9/21 crossover entries on RELIANCE with RSI-momentum scoring',
    'owner',
    ARRAY['seed', 'ema', 'demo'])
  RETURNING id INTO sid;

  INSERT INTO strategy_versions
    (strategy_id, version, config_yaml, config, schema_version, checksum, status, notes, created_by)
  VALUES (
    sid,
    '1.0.0',
    $yaml$# The seed shape (Phase 21 R__seed_sample_strategy) - smallest valid document
schema: strategy-schema/v1
id: minimal-ema-crossover
name: "Minimal EMA Crossover"
version: 1.0.0
universe:
  mode: explicit
  instruments:
    - { exchange: NSE, tradingsymbol: RELIANCE }
timeframes: { primary: 1m }
indicators:
  - { name: EMA, alias: ema_fast, timeframe: 1m, params: { period: 9 }, weight: 1.0 }
  - { name: EMA, alias: ema_slow, timeframe: 1m, params: { period: 21 }, weight: 1.0 }
  - { name: RSI, alias: rsi_1m, timeframe: 1m, params: { period: 14 }, weight: 1.0,
      normalize: { type: rsi_momentum } }
entry_rules:
  direction: long
  gate:
    all:
      - crossover: { fast: ema_fast, slow: ema_slow }
  scoring: { threshold: 0.5 }
exit_rules:
  - { type: signal_exit, params: { rule: "crossunder(ema_fast, ema_slow)" } }
risk:
  position_sizing: { method: fixed_quantity, params: { quantity: 1 } }
  max_positions: 1
  session: { style: intraday }
$yaml$,
    $json$
{"entry_rules":{"direction":"long","gate":{"all":[{"crossover":{"fast":"ema_fast","slow":"ema_slow"}}]},"scoring":{"threshold":0.5}},"exit_rules":[{"params":{"rule":"crossunder(ema_fast, ema_slow)"},"type":"signal_exit"}],"id":"minimal-ema-crossover","indicators":[{"alias":"ema_fast","name":"EMA","params":{"period":9},"timeframe":"1m","weight":1},{"alias":"ema_slow","name":"EMA","params":{"period":21},"timeframe":"1m","weight":1},{"alias":"rsi_1m","name":"RSI","normalize":{"type":"rsi_momentum"},"params":{"period":14},"timeframe":"1m","weight":1}],"name":"Minimal EMA Crossover","risk":{"max_positions":1,"position_sizing":{"method":"fixed_quantity","params":{"quantity":1}},"session":{"style":"intraday"}},"schema":"strategy-schema/v1","timeframes":{"primary":"1m"},"universe":{"instruments":[{"exchange":"NSE","tradingsymbol":"RELIANCE"}],"mode":"explicit"},"version":"1.0.0"}$json$::jsonb,
    'strategy-schema/v1',
    'ac00f791fdc055cca6a7bcfb3405a5788e7a643ed45e8f769a20c37ab4c3ae23',
    'draft',
    'repeatable seed migration',
    'owner');

  INSERT INTO strategy_audit_log (strategy_id, action, to_version, diff_summary, actor)
  VALUES (sid, 'CREATE', '1.0.0', 'seeded sample EMA-crossover strategy', 'owner');
END
$do$;
