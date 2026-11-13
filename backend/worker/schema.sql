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
