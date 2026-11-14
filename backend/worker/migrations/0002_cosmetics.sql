-- 2026-11-14: cosmetics (hats, headbands, auras, shields). Run once on the live database BEFORE deploying this Worker:
--   npx wrangler d1 execute nether --remote --file=migrations/0002_cosmetics.sql
ALTER TABLE players ADD COLUMN hat TEXT;
ALTER TABLE players ADD COLUMN band TEXT;
ALTER TABLE players ADD COLUMN aura TEXT;
ALTER TABLE players ADD COLUMN shield TEXT;
