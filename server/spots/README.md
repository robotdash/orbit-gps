# Orbit spots service — spots.orbitgps.ca

Cloudflare Worker + D1 database for spots reported from the app (overnight parking OK / not,
free camping, rest areas, sani dumps, fresh potable water, propane).

| Path | What |
|---|---|
| `POST /report` | `{type, lat, lon, note, flags[]}` → stored as **pending** |
| `POST /vote` | `{id, good}` → 👍 still good / 👎 gone or changed (one per spot per day) |
| `GET /spots.json` | approved spots for the app (cached 10 min) |
| `GET /admin` | review page (any user name + the ADMIN_PASSWORD secret) |

Privacy: no accounts, names or phone IDs. To limit flooding (30 reports a day), a one-way
hash of (address + day + HASH_SECRET) is counted for the day, then deleted after two days.

## Deploy

```
npm install wrangler@3
export CLOUDFLARE_API_TOKEN=...   # Workers Scripts, D1, Workers Routes, DNS (orbitgps.ca)
npx wrangler d1 execute orbit-spots --remote --file=schema.sql   # first time only
npx wrangler deploy
echo -n 'password' | npx wrangler secret put ADMIN_PASSWORD
echo -n 'random'   | npx wrangler secret put HASH_SECRET
```

Local testing: put `ADMIN_PASSWORD=...` and `HASH_SECRET=...` in `.dev.vars` (never commit it),
`npx wrangler d1 execute orbit-spots --local --file=schema.sql`, `npx wrangler dev --local`.
