-- Orbit GPS reported spots (Cloudflare D1 / SQLite)
CREATE TABLE IF NOT EXISTS spots (
  id        INTEGER PRIMARY KEY AUTOINCREMENT,
  created   TEXT NOT NULL,                 -- ISO time reported
  updated   TEXT NOT NULL,                 -- last change (approve, edit, vote)
  status    TEXT NOT NULL DEFAULT 'pending', -- pending | approved | rejected
  type      TEXT NOT NULL,                 -- see TYPES in src/index.js
  lat       REAL NOT NULL,
  lon       REAL NOT NULL,
  note      TEXT NOT NULL DEFAULT '',
  flags     TEXT NOT NULL DEFAULT '',      -- comma list: bigrig,toilets,cell,fee
  up        INTEGER NOT NULL DEFAULT 0,    -- "still good"
  down      INTEGER NOT NULL DEFAULT 0,    -- "gone / changed"
  confirmed TEXT                           -- last "still good" (or approval) time
);
CREATE INDEX IF NOT EXISTS spots_status ON spots(status);

-- Abuse limits only: a one-way hash of (address + day + secret), never the address itself.
-- Rows older than two days are deleted.
CREATE TABLE IF NOT EXISTS hits (
  who  TEXT NOT NULL,
  day  TEXT NOT NULL,
  what TEXT NOT NULL,                      -- 'report' or 'vote:<id>'
  n    INTEGER NOT NULL DEFAULT 1,
  PRIMARY KEY (who, day, what)
);
