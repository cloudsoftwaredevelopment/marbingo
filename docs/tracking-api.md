# Ecosystem Map / tracking API (contract)

Shared by the backend, the web Ecosystem Map and the Android app. Coordinates are WGS84 decimal degrees. Timestamps are ISO-8601 UTC. "Day" means a Manila calendar date `YYYY-MM-DD`. Errors are `{ error, fields? }`.

## Work schedule

The setting key is `tracking_schedule`. Its default is Mon–Sat 07:00–18:00, Manila time.
```jsonc
{ "days": [1,2,3,4,5,6],   // ISO weekday: 1 = Mon … 7 = Sun
  "start": "07:00", "end": "18:00" }
```
- `GET /api/settings/tracking-schedule` (any authenticated user) → schedule
- `PUT /api/settings/tracking-schedule` (MANAGER) → schedule. Validation: days are unique 1–7, times are HH:MM, and start must be before end.
- `GET /api/sync/pull` also returns `trackingSchedule` and `trackingConsentAt` (the caller's).

## Consent

`POST /api/tracking/consent` (AGENT, MANAGER) sets `users.tracking_consent_at = now` and returns `{ trackingConsentAt }`. This is idempotent: it keeps the first timestamp.

## Upload (Android)

`POST /api/tracking/points` (AGENT, MANAGER)
```jsonc
{ "points": [ { "recordedAt": "…", "lat": 10.72, "lng": 122.56,
                "accuracy": 8.5, "speed": 3.1, "bearing": 270, "battery": 82 } ] }   // at most 500
```
→ `{ "accepted": 120, "duplicates": 3, "rejected": 2 }`.
- A point is **rejected** (not stored) if it falls outside the work schedule (Manila time), is more than 10 minutes in the future, is older than 30 days, has lat/lng out of range, or has accuracy above 500 m.
- **Duplicates** are points with the same agent and `recordedAt`, and they're ignored.
- The agent is always `req.user`.
- After the insert, stops for the affected agent-days are recomputed. This is debounced to at most once every 60 s per agent-day.

## Live

`GET /api/tracking/live` (MANAGER) returns every active AGENT:
```jsonc
[ { "agent": { "id": 4, "firstName": "Ramon", "lastName": "Dela Cruz" },
    "status": "tracking",          // tracking (last point < 5 min) | signal_lost (in work hours, stale) | off_duty
    "last": { "recordedAt", "lat", "lng", "accuracy", "speed", "battery" } | null,
    "todayStops": 4, "todayDistanceM": 18250 } ]
```

## Track

`GET /api/tracking/track?agentId=4&date=YYYY-MM-DD&from=HH:MM&to=HH:MM` (MANAGER). `date` defaults to today. `from` and `to` are optional Manila times that clip the day.
```jsonc
{ "agentId": 4, "date": "2026-10-26",
  "snapped": true,                                   // false = raw simplified GPS (OSRM unavailable)
  "route": [[lat, lng], …],                          // the line to draw
  "points": [ { "t": "…", "lat", "lng", "accuracy" } ],   // raw (thinned to ≤ 2000), for playback
  "summary": { "distanceM": 18250, "movingS": 9300, "stoppedS": 7200, "stops": 6, "storesVisited": 4,
               "firstAt": "…", "lastAt": "…" } }
```

## Stops

`GET /api/tracking/stops?agentId=&from=YYYY-MM-DD&to=YYYY-MM-DD&timeFrom=HH:MM&timeTo=HH:MM&customerId=&near=lat,lng&radius=300&minMinutes=3` (MANAGER). Every parameter is optional. The date range defaults to today. `near` and `radius` filter stops by distance from a point, with radius in metres (default 300).
```jsonc
[ { "id": 51, "agent": { "id", "firstName", "lastName" }, "day": "2026-10-26",
    "startedAt": "…", "endedAt": "…", "durationS": 840, "lat", "lng",
    "customer": { "id": 3, "name": "Aling Nena Store" } | null, "pointCount": 52 } ]
```
Results are sorted by `startedAt` ascending.

## Visits to a store

`GET /api/tracking/visits?customerId=3&from=&to=` (MANAGER) returns the same shape as stops, filtered to that store (newest first, at most 200), plus `summary: { visits, totalS, lastVisitAt }`.

## Store locations

The customer JSON (see field-api.md) gains `lat`, `lng`, `locationSource` (`gps|manual|null`), `locationAccuracy`, `locatedAt`.
- `POST /api/customers` and `POST /api/orders/batch` customers accept `lat`, `lng`, `locationAccuracy` (source `gps` when coming from AGENT).
- `PUT /api/customers/:id` accepts the same.
- `PUT /api/customers/:id/location` (MANAGER, INVENTORY) `{ lat, lng }` sets source `manual` (a web pin drag) and returns `{ customer }`.
- `GET /api/customers?located=0|1` filters to stores without or with a location.

## Roles

The map endpoints (`live`, `track`, `stops`, `visits`) are MANAGER only. Upload and consent are for AGENT (and MANAGER, for testing). Every other role gets 403.

## Retention

Once a day (at startup and then every 24 h), the server deletes `location_points` with `recordedAt` older than 365 days, along with the matching `track_cache` rows. `agent_stops` are kept.
