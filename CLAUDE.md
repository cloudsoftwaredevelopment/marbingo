# Marbingo — Dry Goods Back Office

> **Keep this file under 300 lines.** When adding to it, summarise and link to `docs/` (API contracts, detailed notes) instead of pasting detail; trim stale sections rather than appending.

Back-office dashboard for a dry goods shop: sales report, add item, record sale (POS), field orders, stores, profile, admin — plus **Marbingo Field**, a native Android app (`android/`) agents use offline to take orders at stores.
Field orders API contract: `docs/field-api.md` (shared by server, web and Android).
Spec: `HANDOFF.md` (wins over designs). Visual reference: `design/*.dc.html` (design-tool files — never ship).

## Stack
React 18 + Vite 5 (client/) · Express 4 + Prisma 5 + PostgreSQL (server/) · JWT 8h/7d · Multer + sharp → `server/uploads/` · PM2 `marbingo` on :5003.

## Commands
```bash
cd server && npm run dev        # API :5003 (reads server/.env)
cd client && npm run dev        # Vite :5173, proxies /api + /uploads → :5003
cd server && npm test           # periods/buckets/turnover unit tests
cd server && npm run seed       # WIPES and reseeds demo data (120 days of sales)
cd client && npx vite build && pm2 restart marbingo   # deploy
```
After `schema.prisma` changes: `npx prisma migrate dev`.

## Conventions
- Money is **integer centavos** everywhere (DB, API). Client formats with `lib/format.js` (`pesoWhole` reports, `peso` prices).
- Shop timezone **Asia/Manila**; all period/bucket logic lives in `server/src/lib/periods.js` (weeks start Monday). The server resolves presets.
- Every stock change writes a `stock_moves` row; `qty_on_hand` must equal the sum of moves. Turnover = units ÷ ((opening+closing)/2) from the ledger (`lib/turnover.js`); opening/closing = warehouse + truck ledgers (total company stock).
- Errors: `{ error, fields? }`. Helpers in `server/src/lib/http.js` (`idParam` for `:id` routes, `EMAIL_RE`). Image uploads go through `server/src/lib/upload.js` (rejects non JPG/PNG/WEBP with 400).
- Stock decrements use a conditional `updateMany where qtyOnHand gte` — never read-then-write.
- Sessions: `users.token_version` is in every JWT; bump it to revoke sessions (password change, reset, deactivation).
- "Today" chart has `Before 7a` / `After 7p` buckets, shown only when they have sales, so bars always sum to Gross sales.
- Client shows times/dates in Asia/Manila explicitly (`timeZone` option), never browser-local.
- **Pack pricing** (`docs/pricing.md`): items sell retail per base unit (`selling_price`) plus optional packs (`item_packs`: admin-managed `pack_types` like Dozen/Box/Crate/Case/Sack, per-item size + whole-pack price). Every sale/order line is priced by `priceQty` (cheapest mix of packs + loose; DP, ties → fewer loose → fewer packs) and stores its `breakdown`. The engine exists 3× — `server/src/lib/pricing.js` (authoritative), `client/src/lib/pricing.js`, android `core/.../pricing/Pricing.kt` — and **all must pass `docs/pricing-vectors.json`** (`server npm test`, `client npm test`, `:core:testDebugUnitTest`). Change the reference, regenerate vectors, port. Order lines keep a `pricing` snapshot so partial deliveries re-price from order-time prices. Changing an item's packs or a pack type bumps `items.updated_at` so tablets re-sync them.
- Roles: MANAGER, INVENTORY, CASHIER, AGENT. Server: `requireRole()`; client mirror: `client/src/lib/roles.js`.
- Styling: tokens in `client/src/styles/tokens.css`, shared classes in `global.css`.
- Branding: the official logo is a static asset in a white rounded tile — web `client/public/logo.png` via `components/BrandMark.jsx` (sidebar, mobile top bar, login; favicons in `client/public/`), Android `res/drawable-nodpi/brand_logo.png` via `BrandLogo()` (sign-in, update-required) plus the launcher icons (white background). The Admin "logo letters" (`shopMark`) now only appear on the Android Settings screen (`ShopMark()`).

## Roles
| | Manager | Inventory | Cashier | Agent |
|---|---|---|---|---|
| Sales report | ✅ | ✅ | | |
| Items list / add / edit (incl. pack prices) | ✅ | ✅ | | |
| Record sale (web POS) | ✅ | | ✅ | |
| Orders: view | all | all | | own (web read-only + Android app) |
| Orders: take (Android app) | ✅ | | | ✅ |
| Orders: deliver / cancel | ✅ | ✅ | | cancel own pending |
| Stores | ✅ | ✅ | | ✅ (app; edit own) |
| Top 10 sidebar | turnover + revenue | turnover + revenue | turnover only | turnover only |
| Ecosystem Map (`/map`) | ✅ | | | tracked (work hours) |
| Trucks (`/trucks`): view, request load/top-up/return, count | ✅ | ✅ | | |
| Trucks: CRUD, open period (approve beginning inventory), approve transfers, close | ✅ | | | |
| Sell from truck (Android) | ✅ | | | truck agents only |
| Admin (users, categories, pack types, shop name + logo letters, tracking schedule) | ✅ | | | |

## Demo logins (password `marbingo123`)
maria@ (Manager) · jose@ (Inventory) · ana@ (Cashier) · ramon@ (Agent) — all `@marbingo.test`.

## Field orders (how it works)
Agent takes an order in the app (works offline) → it syncs to `orders` (status `pending`) → staff **Deliver** it on the web `/orders` page (can deliver less than ordered) → only then is stock decremented and a `Sale` (channel `field`, `customer_id`) created, so reports count it on the delivery date. Order lines are priced with pack pricing at order time (snapshot kept for deliveries). Offline idempotency: the device generates `client_uuid` for orders and stores; `POST /api/orders/batch` returns the existing row on replay.

## Android app (`android/`)
Kotlin + Jetpack Compose, same toolchain/patterns as `/var/www/recit/android`. `:core` (no Compose: ApiClient + token refresh, DTOs, Money, EscPos, ReceiptFormatter) and `:app` (Compose UI, Room offline cache/outbox, WorkManager `SyncWorker` → `SyncEngine`, Bluetooth printing, PDF share). UI depends only on `app/.../data/Repositories.kt` interfaces; `ui/preview/Fakes.kt` implements them for tests/previews.
```bash
cd android && source /etc/profile.d/android-sdk.sh
./gradlew :core:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
SCREENSHOT_DIR=/tmp/shots ./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*'   # render screens headlessly
bash ../scripts/release-apk.sh "what changed"   # publish an OTA update (bump version first)
```
- No emulator on this box: only JVM/Robolectric tests. `LiveServerTest` hits the live server (skips if unreachable) and **creates a real order + store** — reseed afterwards.
- Debug build → `http://marbingo.62-84-181-147.sslip.io/` (cleartext allowed for that host only, `src/debug/res/xml/network_security_config.xml`). Release (`API_BASE_URL` = `https://CHANGE-ME/`) needs the real HTTPS domain and a keystore kept **outside the repo** (`/root/keystores/`).
- Never use destructive Room migrations — any entity change needs a real `Migration` (agents may have queued orders on-device).
- Bump `versionCode`/`versionName` in `app/build.gradle.kts` for each published build; installing over the old APK keeps queued orders.
- **OTA updates** (`app/.../update/`): tablets poll public `GET /api/app/latest` (serves `downloads/latest.json`) on start, after syncs (≤ every 30 min) and from Settings; banner → download → size+SHA-256 check → PackageInstaller (user taps Install; queued orders kept). Release: bump version, then `bash scripts/release-apk.sh "notes"` (`--required` makes older versions show a blocking "Update required" screen; sync keeps running). Staging is signed with `/root/keystores/marbingo-staging.keystore` (copy of the old debug key) — **never change or lose it**, or tablets can't update without uninstalling (losing queued orders).
- APKs: `downloads/` served by nginx at `/downloads/` (MIME `application/vnd.android.package-archive`); APKs + `index.html` are gitignored.

- Build types: `debug` (43 MB) and `staging` (R8-minified, debug-signed, same applicationId `ph.marbingo.field.debug` so it upgrades the debug app, ~25 MB — publish this one as `marbingo-field-v<VER>.apk`; smaller APKs pass Play Protect's scan faster). `./gradlew :app:assembleStaging`.
- Room is at **version 5** (`MIGRATION_4_5`: `device_events` outbox; `MIGRATION_3_4`: `truck_meta`, `truck_stock`, `truck_sales`, `truck_sale_lines`; `MIGRATION_1_2`: customer lat/lng + `location_points` + `tracking_meta`; `MIGRATION_2_3`: `item_packs`, line breakdowns, items rebuilt without wholesale price, forces one full re-pull), covered by `MigrationTest`.
- Location tracking lives in `app/.../tracking/`: `LocationTrackingService` (foreground, type `location`), `TrackingScheduler` (exact alarms at work-hour boundaries + every 15 min on duty to revive a killed service; boot/package-replaced/time-change receivers), `LocationUploader` (outbox → `POST /api/tracking/points`), onboarding UI in `ui/tracking/`. Needs Google Play services (fused location).
- **Device log** (`tracking/DeviceMonitor.kt`, contract in `docs/tracking-api.md`): during work hours records power off/on (shutdown broadcast, else boot count + last heartbeat in `PrefsMonitorStore`), app killed, mobile data/airplane/signal/GPS/permission/auto-time changes and fake-GPS fixes (not stored) → `device_events` outbox → `POST /api/tracking/events`. Web `/map` shows "Device alerts now" and a per-agent-day gap bar.

## Trucks (mobile warehouses)
Contract: `docs/truck-api.md`. A **truck agent** = AGENT assigned to an active `Truck` (no user flag); others are booking agents. Warehouse stays `items.qty_on_hand` + `stock_moves` (new reasons `truck_load`/`truck_return`); trucks have their own ledger `truck_stock == SUM(truck_moves)` (seed checks both). Monthly `truck_periods`: draft → **open (MANAGER approves beginning inventory = carry-over + pending loads)** → counting (staff enter count on web) → closed (MANAGER; posts `count_variance` so leftovers carry over, freezes MATCHED/NOT MATCHED report with missing/surplus at retail, creates next draft). Mid-month top-ups/returns need MANAGER approval. Truck sales (`POST /api/truck-sales/batch`, offline, idempotent by `client_uuid`) create `Sale` channel `truck`, decrement truck stock **unconditionally** (offline oversell → `oversold` flag, stock may go negative; deliberate exception) and never touch warehouse moves; sales landing after their period closed post to the current period as `late`. Logic: `server/src/lib/truck.js` (pure, tested) + `lib/truckOps.js` (transactions, used by routes + seed). Web: `/trucks`, `/trucks/:id`. Android: Home truck card, "Sell from truck" (`OrderMode.TRUCK_SALE`), available = server truck stock − unsynced sale lines (never mutated locally), SALES RECEIPT print/PDF.

## Ecosystem Map (tracking)
Contract: `docs/tracking-api.md`. Agents' tablets record GPS **only inside the work schedule** (`settings.tracking_schedule`, edited in Admin → Tracking; server also rejects out-of-hours points). `location_points` are purged after 365 days (`server/src/jobs/retention.js`); `agent_stops` (derived by `lib/tracking.js#detectStops`: ≥3 min within 60 m, matched to a store ≤75 m) are kept. Stops are recomputed per agent-day (debounced after uploads, single-flight, also on read when stale); routes cached in `track_cache`.
- Road snapping: `server/src/lib/osrm.js` calls OSRM at `OSRM_URL` (`/match`); without it the map shows the raw GPS trail (`snapped:false`). OSRM 5.27.1 is **installed** at `/opt/osrm` (built from source by `ops/osrm/install.sh`, which also patches two GCC 13 incompatibilities), serving the Negros + Siquijor map via systemd `osrm-routed` on 127.0.0.1:5010 as the `osrm` system user; `OSRM_URL` is set in `server/.env`. Check: `systemctl status osrm-routed`. Refresh roads monthly: `bash ops/osrm/build-map.sh` (re-downloads Geofabrik PH, re-extracts, chowns to `osrm`, restarts).
- Web page `/map` (MANAGER only): Leaflet + OSM tiles (respect the OSM tile usage policy; switch to a tile provider if usage grows).

### Handset checklist (can't be tested here)
Tracking: setup flow ("Allow all the time", notifications, battery exemption + brand tips) → during work hours the notification shows and the live marker/route appear on web `/map` → outside hours tracking stops → airplane mode then reconnect (points upload) → reboot (tracking resumes) → turn GPS off (red banner) → "Set location here" on a store shows the pin on the web. Upgrade 0.1 → 0.2 keeps queued orders (Room migration).
Device log (0.7): on duty toggle mobile data, airplane mode, GPS → each shows on web `/map` Device log after reconnect; hold power → Restart (exact power-off) and pull battery/let it die (estimated); Settings → Apps → Force stop then reopen after > 3 min (App stopped); a fake-GPS app (Developer options → mock location app) shows "Fake GPS".
Orders:
Sign in online → airplane mode → add a store + take an order (scan a barcode with the camera) → order shows "Waiting to sync" → reconnect → it syncs and appears on web `/orders` → deliver it on the web → app shows Delivered after sync. Also: Bluetooth printer pick + test print + print slip (58mm ESC/POS), Share PDF via Messenger/Viber, sign-out blocked while orders are unsynced.

## Deploy
Temporary test host: `marbingo.62-84-181-147.sslip.io` (nginx site enabled, HTTP only). For production: put the real domain in `/etc/nginx/sites-available/marbingo`, reload, `certbot --nginx -d DOMAIN`, and update the Android release `API_BASE_URL`.

## Not built yet (HANDOFF §9)
Stock-history view; restock/adjustment UI on the web (API `POST /api/items/:id/restock` exists). Inventory list (`/items`) and Edit item now exist.
