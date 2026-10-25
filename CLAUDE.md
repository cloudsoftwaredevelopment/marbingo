# Marbingo — Dry Goods Back Office

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
- Every stock change writes a `stock_moves` row; `qty_on_hand` must equal the sum of moves. Turnover = units ÷ ((opening+closing)/2) from the ledger (`lib/turnover.js`).
- Errors: `{ error, fields? }`. Helpers in `server/src/lib/http.js` (`idParam` for `:id` routes, `EMAIL_RE`). Image uploads go through `server/src/lib/upload.js` (rejects non JPG/PNG/WEBP with 400).
- Stock decrements use a conditional `updateMany where qtyOnHand gte` — never read-then-write.
- Sessions: `users.token_version` is in every JWT; bump it to revoke sessions (password change, reset, deactivation).
- "Today" chart has `Before 7a` / `After 7p` buckets, shown only when they have sales, so bars always sum to Gross sales.
- Client shows times/dates in Asia/Manila explicitly (`timeZone` option), never browser-local.
- Roles: MANAGER, INVENTORY, CASHIER, AGENT. Server: `requireRole()`; client mirror: `client/src/lib/roles.js`.
- Styling: tokens in `client/src/styles/tokens.css`, shared classes in `global.css`.

## Roles
| | Manager | Inventory | Cashier | Agent |
|---|---|---|---|---|
| Sales report | ✅ | ✅ | | |
| Add item / restock | ✅ | ✅ | | |
| Record sale (web POS) | ✅ | | ✅ | |
| Orders: view | all | all | | own (web read-only + Android app) |
| Orders: take (Android app) | ✅ | | | ✅ |
| Orders: deliver / cancel | ✅ | ✅ | | cancel own pending |
| Stores | ✅ | ✅ | | ✅ (app; edit own) |
| Top 10 sidebar | turnover + revenue | turnover + revenue | turnover only | turnover only |
| Admin (users, categories, shop name + logo letters) | ✅ | | | |

## Demo logins (password `marbingo123`)
maria@ (Manager) · jose@ (Inventory) · ana@ (Cashier) · ramon@ (Agent) — all `@marbingo.test`.

## Field orders (how it works)
Agent takes an order in the app (works offline) → it syncs to `orders` (status `pending`) → staff **Deliver** it on the web `/orders` page (can deliver less than ordered) → only then is stock decremented and a `Sale` (channel `field`, `customer_id`) created, so reports count it on the delivery date. Order lines are priced at `items.wholesale_price ?? selling_price` at order time. Offline idempotency: the device generates `client_uuid` for orders and stores; `POST /api/orders/batch` returns the existing row on replay.

## Android app (`android/`)
Kotlin + Jetpack Compose, same toolchain/patterns as `/var/www/recit/android`. `:core` (no Compose: ApiClient + token refresh, DTOs, Money, EscPos, ReceiptFormatter) and `:app` (Compose UI, Room offline cache/outbox, WorkManager `SyncWorker` → `SyncEngine`, Bluetooth printing, PDF share). UI depends only on `app/.../data/Repositories.kt` interfaces; `ui/preview/Fakes.kt` implements them for tests/previews.
```bash
cd android && source /etc/profile.d/android-sdk.sh
./gradlew :core:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
SCREENSHOT_DIR=/tmp/shots ./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*'   # render screens headlessly
cp app/build/outputs/apk/debug/app-debug.apk ../downloads/marbingo-field-v<VER>-debug.apk && node ../scripts/refresh-downloads.js
```
- No emulator on this box: only JVM/Robolectric tests. `LiveServerTest` hits the live server (skips if unreachable) and **creates a real order + store** — reseed afterwards.
- Debug build → `http://marbingo.62-84-181-147.sslip.io/` (cleartext allowed for that host only, `src/debug/res/xml/network_security_config.xml`). Release (`API_BASE_URL` = `https://CHANGE-ME/`) needs the real HTTPS domain and a keystore kept **outside the repo** (`/root/keystores/`).
- Room is at version 1 with no destructive migration — any entity change needs a real `Migration` (agents may have queued orders on-device).
- Bump `versionCode`/`versionName` in `app/build.gradle.kts` for each published build; installing over the old APK keeps queued orders.
- APKs: `downloads/` served by nginx at `/downloads/` (MIME `application/vnd.android.package-archive`); APKs + `index.html` are gitignored.

### Handset checklist (can't be tested here)
Sign in online → airplane mode → add a store + take an order (scan a barcode with the camera) → order shows "Waiting to sync" → reconnect → it syncs and appears on web `/orders` → deliver it on the web → app shows Delivered after sync. Also: Bluetooth printer pick + test print + print slip (58mm ESC/POS), Share PDF via Messenger/Viber, sign-out blocked while orders are unsynced.

## Deploy
Temporary test host: `marbingo.62-84-181-147.sslip.io` (nginx site enabled, HTTP only). For production: put the real domain in `/etc/nginx/sites-available/marbingo`, reload, `certbot --nginx -d DOMAIN`, and update the Android release `API_BASE_URL`.

## Not built yet (HANDOFF §9)
Inventory list / edit-item screen (API `PUT /api/items/:id` + restock exist), stock-history view.
