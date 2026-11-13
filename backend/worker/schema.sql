-- every Nether Client player who has checked in (nametag icon), and their Nether cape
CREATE TABLE IF NOT EXISTS players (
  uuid TEXT PRIMARY KEY,   -- Minecraft profile id, no dashes, lowercase
  cape TEXT,               -- id from capes/index.json, NULL = none
  seen INTEGER NOT NULL    -- unix seconds of the last check-in / cape change
);

-- one-time server ids for the Mojang join/hasJoined handshake (/v1/auth/start); deleted on use, purged when expired
CREATE TABLE IF NOT EXISTS nonces (
  id TEXT PRIMARY KEY,
  exp INTEGER NOT NULL     -- unix seconds
);

-- Friends-lite (opt-in, env FRIENDS = "1"): a row per user who turned Friends on; no names are stored
CREATE TABLE IF NOT EXISTS fr_users (
  uuid TEXT PRIMARY KEY,
  code TEXT UNIQUE,        -- 8-char Crockford friend code, rotates on demand
  code_exp INTEGER,        -- unix seconds (7 days)
  host TEXT,               -- address shown to accepted friends while hosting, NULL = not hosting
  host_until INTEGER,
  joined INTEGER NOT NULL
);
-- one row per pair (a < b): pending (requester asked) or accepted
CREATE TABLE IF NOT EXISTS fr_edges (
  a TEXT NOT NULL, b TEXT NOT NULL,
  state TEXT NOT NULL,
  requester TEXT NOT NULL,
  created INTEGER NOT NULL,
  PRIMARY KEY (a, b)
);
CREATE INDEX IF NOT EXISTS fr_edges_b ON fr_edges (b);
-- uuid blocked "blocked": their requests to uuid are dropped silently
CREATE TABLE IF NOT EXISTS fr_blocks (
  uuid TEXT NOT NULL, blocked TEXT NOT NULL,
  PRIMARY KEY (uuid, blocked)
);
