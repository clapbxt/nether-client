-- 2026-11-14: hash-prefix lookups (GET /v1/players?p=). Run once on the live database BEFORE deploying this Worker:
--   npx wrangler d1 execute nether --remote --file=migrations/0001_player_hash.sql
ALTER TABLE players ADD COLUMN hp TEXT;
ALTER TABLE players ADD COLUMN h TEXT;
CREATE INDEX IF NOT EXISTS players_hp ON players (hp);
