-- every Nether Client player who has checked in (nametag icon), and their Nether cape
CREATE TABLE IF NOT EXISTS players (
  uuid TEXT PRIMARY KEY,   -- Minecraft profile id, no dashes, lowercase
  cape TEXT,               -- id from capes/index.json, NULL = none
  hat TEXT,                -- cosmetics/index.json ids by type (migrations/0002), NULL = none
  band TEXT,
  aura TEXT,
  shield TEXT,
  seen INTEGER NOT NULL,   -- unix seconds of the last check-in / cape change
  hp TEXT,                 -- first 4 hex digits of h (lookups are by prefix, see index.js)
  h TEXT                   -- sha256 hex of "nether-player:" + uuid
);
CREATE INDEX IF NOT EXISTS players_hp ON players (hp);

-- one-time server ids for the Mojang join/hasJoined handshake (/v1/auth/start); deleted on use, purged when expired
CREATE TABLE IF NOT EXISTS nonces (
  id TEXT PRIMARY KEY,
  exp INTEGER NOT NULL     -- unix seconds
);
