# Field orders API (contract)

Shared by the backend, the web dashboard and the Android app. All money is **integer centavos**. All timestamps are ISO-8601 UTC strings. Errors are `{ error, fields? }` with 400/401/403/404/409. Auth is `Authorization: Bearer <accessToken>` (see `POST /api/auth/login`, `POST /api/auth/refresh {refreshToken}` → `{accessToken, refreshToken}`).

## Shapes

```jsonc
// Item (sync/pull)
{ "id": 1, "name": "Jasmine rice, 1 kg", "sku": "4800016000017", "unit": "pc",
  "categoryId": 1, "category": "Rice & grains",
  "sellingPrice": 6200,                           // retail per base unit
  "packs": [ { "id": 3, "packTypeId": 5, "name": "Sack", "size": 50, "price": 290000 } ],   // see docs/pricing.md
  "qtyOnHand": 594, "imageUrl": "/uploads/items/x.webp", "updatedAt": "…" }

// Customer (store)
{ "id": 3, "clientUuid": "6f1c…", "name": "Aling Nena Store", "ownerName": "Nena Bautista",
  "phone": "0917…", "address": "Brgy. Bantayan, Dumaguete City", "notes": null, "active": true,
  "createdAt": "…", "updatedAt": "…" }

// Order
{ "id": 12, "clientUuid": "a9b2…", "status": "pending",          // pending | delivered | cancelled
  "customer": { "id": 3, "clientUuid": "6f1c…", "name": "Aling Nena Store" },
  "agent": { "id": 4, "firstName": "Ramon", "lastName": "Dela Cruz" },
  "orderedAt": "…", "syncedAt": "…", "updatedAt": "…",
  "deliveredAt": null, "deliveredBy": null,                    // {id, firstName, lastName} when delivered
  "cancelledReason": null, "notes": "Deliver Thursday", "total": 112000, "saleId": null,
  "lines": [ { "itemId": 1, "name": "Jasmine rice, 1 kg", "unit": "pc",
               "qty": 20, "unitPrice": 5600, "lineTotal": 112000, "deliveredQty": null } ] }
```

## Sync (Android)

`GET /api/sync/pull?since=<ISO>` (AGENT, MANAGER). `since` is optional. Without it, the response is everything.
```jsonc
{ "serverTime": "…",                 // pass back as `since` next time
  "items": [Item], "categories": [{ "id", "name" }],
  "customers": [Customer],           // active and inactive, changed since `since`
  "orders": [Order] }                // the caller's own orders changed since `since` (MANAGER: all)
```
Rows are those with `updatedAt > since`. Categories are always returned in full.

## Customers

- `GET /api/customers?q=` (any authenticated user except CASHIER) → `[Customer & { orderCount, totalDelivered }]`, active only unless `?all=1`, sorted by name.
- `GET /api/customers/:id` → `{ customer: Customer, orders: [Order], totals: { orderCount, pendingCount, totalDelivered } }`.
- `POST /api/customers` (AGENT, MANAGER, INVENTORY) `{clientUuid?, name, ownerName?, phone?, address?, notes?}`. This is an idempotent upsert: if `clientUuid` already exists, the response is the existing customer with 200. Otherwise the server creates one and returns 201 `{ customer }`. `name` is required (`fields.name: "Enter the store name."`).
- `PUT /api/customers/:id` (MANAGER, INVENTORY, or the AGENT who created it) with partial fields plus `active` → `{ customer }`.

## Orders

- `POST /api/orders/batch` (AGENT, MANAGER)
  ```jsonc
  { "customers": [ {clientUuid, name, ownerName?, phone?, address?, notes?} ],   // optional, upserted first
    "orders": [ { "clientUuid": "a9b2…", "customerClientUuid": "6f1c…",       // or "customerId": 3
                  "orderedAt": "…", "notes": null,
                  "lines": [ { "itemId": 1, "qty": 20 } ] } ] }
  ```
  → 200
  ```jsonc
  { "customers": [ { "clientUuid", "id" } ],
    "results": [ { "clientUuid": "a9b2…", "ok": true, "order": Order }
               | { "clientUuid": "…", "ok": false, "error": "Unknown item 99." } ] }
  ```
  Each order is processed on its own. If an order with the same `clientUuid` exists, the response is that order (`ok: true`) and nothing is duplicated. Lines are priced by the server with the pack pricing rule (docs/pricing.md), with a per-line pricing snapshot. Duplicate items are merged. Each `qty` must be an integer ≥ 1. The batch holds at most 100 orders. The agent is `req.user`.
- `GET /api/orders?status=&agentId=&customerId=&from=YYYY-MM-DD&to=YYYY-MM-DD` → `{ orders: [Order], counts: { pending, delivered, cancelled } }`. MANAGER and INVENTORY see all orders; AGENT sees only their own. The list is newest first, at most 200. Dates are matched on `orderedAt` in Manila time.
- `GET /api/orders/pending-count` (MANAGER, INVENTORY) → `{ pending }` (for the sidebar badge).
- `GET /api/orders/:id` → `{ order: Order }` (an AGENT only gets their own).
- `POST /api/orders/:id/deliver` (MANAGER, INVENTORY) `{ lines: [ { itemId, qty } ] }`. `qty` is the delivered quantity, 0 ≤ qty ≤ ordered. A line that's left out counts as delivered in full. This runs in one transaction:
  1. a conditional stock decrement per line, returning 409 `Not enough stock for "<name>" (<n> left).` on a shortfall;
  2. it creates a Sale with `channel: field`, `cashierId` = the agent, `customerId`, and lines at the order's `unitPrice`;
  3. it writes StockMoves with `reason: sale`;
  4. it sets `deliveredQty`, `status: delivered`, `deliveredAt`, `deliveredById` and `saleId`.

  If every qty is 0, the request is rejected with 400 "Deliver at least one item, or cancel the order.". If the order is already delivered, it returns 200 with the order unchanged. A cancelled order returns 409. The response is `{ order }`.
- `POST /api/orders/:id/cancel` `{ reason }` (MANAGER or INVENTORY, or the owning AGENT while the order is pending) → `{ order }`. A delivered order returns 409.

## Items (changes)

`POST/PUT /api/items` accept `packs` (see docs/pricing.md). The old `wholesale` field is gone.

## Reports (changes)

`GET /api/reports/sales` also returns `stores: [{ id, name, orders, revenue }]`. These are field sales in the range grouped by `customerId`, sorted by revenue descending.

## Roles

| | Manager | Inventory | Cashier | Agent |
|---|---|---|---|---|
| sync/pull, orders/batch | ✅ | | | ✅ |
| list/view orders | all | all | | own |
| deliver / cancel | ✅ | ✅ | | cancel own pending |
| customers read | ✅ | ✅ | | ✅ |
| customers create/edit | ✅ | ✅ | | ✅ (edit own) |
| web POST /api/sales | ✅ | | ✅ | ✗ (removed) |
