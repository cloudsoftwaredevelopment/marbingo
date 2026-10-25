# Marbingo — Dry Goods Back Office

Back-office dashboard for a dry goods shop: sales report, add item, record sale (POS), profile, admin.
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
- Errors: `{ error, fields? }`. Helpers in `server/src/lib/http.js`.
- Roles: MANAGER, INVENTORY, CASHIER, AGENT. Server: `requireRole()`; client mirror: `client/src/lib/roles.js`.
- Styling: tokens in `client/src/styles/tokens.css`, shared classes in `global.css`.

## Roles
| | Manager | Inventory | Cashier | Agent |
|---|---|---|---|---|
| Sales report | ✅ | ✅ | | |
| Add item / restock | ✅ | ✅ | | |
| Record sale | ✅ | | ✅ counter | ✅ field |
| Admin (users, categories, shop name) | ✅ | | | |

## Demo logins (password `marbingo123`)
maria@ (Manager) · jose@ (Inventory) · ana@ (Cashier) · ramon@ (Agent) — all `@marbingo.test`.

## Deploy
Nginx config at `/etc/nginx/sites-available/marbingo` is **not enabled** — replace `DOMAIN`, symlink, `nginx -t`, reload, `certbot --nginx -d DOMAIN`.

## Not built yet (HANDOFF §9)
Inventory list / edit-item screen (API `PUT /api/items/:id` + restock exist), stock-history view.
