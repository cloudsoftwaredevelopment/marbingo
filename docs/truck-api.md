# Trucks API (mobile warehouses)

Contract shared by server, web and Android. Base `/api`, JSON, `Authorization: Bearer <accessToken>`. Money = integer centavos. Errors: `{ "error": "...", "fields"?: {...} }`; some 409s add a machine-readable extra key (`short`, `uncounted`), noted below. Dates are ISO UTC; months/days are Asia/Manila.

## Concepts

- **Truck** = a mobile warehouse (`name`, `plate`, `driverName`, optional assigned `agent`). A **truck agent** is the AGENT assigned to an active truck; any other agent is a booking agent and sees no change.
- **Warehouse** is unchanged: `items.qty_on_hand == SUM(stock_moves)`. Moving goods to a truck writes `stock_moves` reason `truck_load` (negative); returning writes `truck_return` (positive).
- **Truck ledger**: `truck_stock(truck, item, qty)` and `truck_moves(change, reason load|topup|return|sale|count_variance)`; invariant `truck_stock.qty == SUM(truck_moves.change)`. Truck stock **can be negative** (offline oversell).
- Total company stock = warehouse + all trucks. `GET /reports/top-items` turnover uses that total.
- **Period** = one Manila month per truck: `draft -> open -> counting -> closed`. At most one non-closed period per truck (enforced by a partial unique index; `(truck, month)` is also unique).

### Period lifecycle

1. `draft` (MANAGER creates; also auto-created at close for the next month). Staff may request **load** transfers (pending).
2. `open` — **MANAGER approves beginning inventory** (`POST /truck-periods/:id/open`): in one transaction every pending load is applied (warehouse -> truck; 409 `short` if the warehouse can't cover it, nothing changes) and `openingSnapshot` is fixed = truck stock now (carry-over + loads). While open: truck sales sync in, **top-up / return** transfers are requested by staff and approved by a MANAGER (applied immediately).
3. `counting` — staff (MANAGER/INVENTORY) start the count and enter counted quantities on the web (`PUT .../count` replaces the whole count).
4. `closed` — **MANAGER** closes. Requires a count line for every item whose expected qty is not 0. Posts one `count_variance` truck move per item with `counted - expected != 0` so `truck_stock == counted` (the carry-over), freezes `report`, creates the next month's draft period (`max(month+1, current Manila month)`).

**Expected** for an item = `openingSnapshot + SUM(truck_moves of the period with reason topup | return | sale)`. `load` moves are *not* summed (the loads applied at open are already inside the snapshot, so nothing is double counted); `count_variance` belongs to closing. If sales were posted to a period while it was still a draft (see "Late"), the snapshot is reduced by those moves at open so each unit counts once. **Reconcile**: `variance = counted - expected`; `missing` (<0), `surplus` (>0), `ok`; an item without a count line counts as 0 (the live preview shows it as missing; close refuses it); `value = |variance| x retail selling_price` (not pack prices). `matched` = every line ok.

### Oversell / late rules (truck sales)

- Truck stock is decremented **unconditionally** (deliberate exception to the "conditional decrement" rule: the goods are already sold). If any line leaves truck stock `< 0` the sale is `oversold: true` and still accepted. Tablets hard-cap quantities locally.
- `soldAt` (device clock) is clamped to `[now - 35 days, now]`; the real time is kept on the Sale.
- A sale posts to the period that was open (open/counting) at `soldAt`. If that period is already closed, or none had been opened yet, it posts to the truck's current non-closed period (draft/open/counting) with `late: true`. If the truck has no non-closed period the sale fails with a per-sale error. Closed reports stay frozen.
- Creates `Sale{channel:'truck', cashierId: seller}` (+ SaleLines with pack `breakdown`, priced server-side at current prices), a `truck_sale`, a `truck_moves(sale)` row (at `soldAt`) per line. **No warehouse stock_moves.** Sales reports include channel `truck` like any sale (on `soldAt`).

## Routes

Roles: **M**=MANAGER, **I**=INVENTORY, **A**=AGENT. Anything not listed returns 403 for other roles.

| Method + path | Roles | Purpose |
|---|---|---|
| `GET /trucks` | M, I | list |
| `POST /trucks` | M | create |
| `GET /trucks/:id` | M, I | detail: current period, stock, periods, transfers |
| `PUT /trucks/:id` | M | edit (partial); agent change / deactivate blocked (409) while a period is open or counting |
| `GET /trucks/:id/periods` | M, I | all periods, newest first |
| `POST /trucks/:id/periods` | M | create a draft period `{ month? }` (default current Manila month) |
| `POST /trucks/:id/transfers` | M, I | request load / topup / return |
| `POST /truck-transfers/:id/approve` | M | approve + apply top-up or return |
| `POST /truck-transfers/:id/reject` | M | reject any pending transfer |
| `GET /truck-periods/:id` | M, I | period, items to count (expected), saved count |
| `POST /truck-periods/:id/open` | M | approve beginning inventory |
| `POST /truck-periods/:id/start-count` | M, I | open -> counting |
| `PUT /truck-periods/:id/count` | M, I | save the whole count (status counting) |
| `GET /truck-periods/:id/report` | M, I | live preview (`preview:true`) or frozen (`preview:false`) |
| `POST /truck-periods/:id/close` | M | close + freeze + next draft |
| `POST /truck-sales/batch` | A (assigned truck), M (`truckId`) | offline truck sales |
| `GET /users?role=AGENT` | M | agents for the assignment dropdown (existing endpoint; `role` filter added; `active` flag included) |
| `GET /items` | any signed in | each item now has `onTruck` (units on all trucks); `qtyOnHand` is warehouse only |
| `GET /sync/pull` | M, A | adds the `truck` block (below) |

### Shapes

`user ref` = `{ id, firstName, lastName }`. `period ref` = `{ id, month, status, openedAt, countStartedAt, closedAt }`.

**GET /trucks** -> 200
```json
{ "trucks": [ { "id": 1, "name": "Truck 1", "plate": "NEG 4821", "driverName": "Pedro Alvarez", "active": true,
  "agent": { "id": 4, "firstName": "Ramon", "lastName": "Dela Cruz" },
  "currentPeriod": { "id": 1, "month": "2026-10", "status": "open", "openedAt": "...", "countStartedAt": null, "closedAt": null },
  "unitsOnBoard": 527, "pendingTransfers": 1 } ] }
```
`currentPeriod` and `agent` may be null.

**POST /trucks** `{ "name": "Truck 2", "plate": "ABC 123", "driverName": "Lito", "agentId": 5 }` -> 201 `{ "truck": { id, name, plate, driverName, active, agent } }`. `name` required; `plate`, `driverName`, `agentId` optional (`agentId: null` unassigns). 400 with `fields` (`name`, `agentId`: must be an active AGENT not already on another truck). **PUT** takes the same keys (all optional) plus `active` -> 200 `{ truck }`.

**GET /trucks/:id** -> 200
```json
{ "truck": { "id": 2, "name": "...", "plate": "...", "driverName": "...", "active": true, "agent": null },
  "currentPeriod": { "id": 2, "month": "2026-10", "status": "open", "openedAt": "...", "countStartedAt": null, "closedAt": null },
  "periods": [ { "id": 2, "month": "2026-10", "status": "closed", "openedAt": "...", "countStartedAt": "...", "closedAt": "...", "matched": false, "missingValue": 8500 } ],
  "stock": [ { "itemId": 1, "name": "Jasmine rice, 1 kg", "unit": "kg", "qty": 25, "retailPrice": 6200, "value": 155000 } ],
  "unitsOnBoard": 25, "stockValue": 155000,
  "transfers": [ { "id": 5, "truckId": 2, "periodId": 2, "kind": "topup", "status": "approved", "note": "more rice",
      "requestedBy": { "id": 2, "firstName": "Jose", "lastName": "Reyes" }, "requestedAt": "...",
      "decidedBy": { "id": 1, "firstName": "Maria", "lastName": "Santos" }, "decidedAt": "...",
      "lines": [ { "itemId": 1, "name": "Jasmine rice, 1 kg", "unit": "kg", "qty": 10 } ] } ],
  "pendingTransfers": 0 }
```
`stock` lists non-zero rows only (negative = oversold, show in red). `transfers` = latest 50, newest first. `matched`/`missingValue` are null until a period is closed.

**POST /trucks/:id/periods** `{ "month": "2026-11" }` (optional) -> 201 `{ "period": <period ref> }`. 409 if the truck already has an unfinished period or that month exists; 400 `fields.month` for a bad format.

**POST /trucks/:id/transfers** `{ "kind": "load" | "topup" | "return", "note": "optional", "lines": [ { "itemId": 1, "qty": 20 } ] }` -> 201 `{ "transfer": {...as above, status "pending"} }`. Duplicate items are merged; qty whole >= 1. `load` needs the truck's period to be `draft`; `topup`/`return` need it `open` (else 409). 400 for bad lines / unknown item.

**POST /truck-transfers/:id/approve** -> 200 `{ "transfer": {...approved} }`. Topup: warehouse -> truck. Return: truck -> warehouse. 409 when: already decided; kind is `load` ("approved by opening the period"); period no longer open; **not enough stock** — body `{ "error": "...", "short": [ { "itemId", "name", "need", "have" } ] }` (warehouse stock for topup, truck stock for return). Nothing is applied on 409. **reject** -> 200 `{ transfer }` (any pending kind); 409 if already decided.

**GET /truck-periods/:id** -> 200
```json
{ "period": { "id": 2, "truckId": 2, "month": "2026-10", "status": "counting", "openedAt": "...", "openedBy": {...}, "countStartedAt": "...",
    "closedAt": null, "closedBy": null, "opening": [ { "itemId": 1, "name": "...", "unit": "kg", "qty": 20 } ] },
  "expected": [ { "itemId": 1, "name": "...", "unit": "kg", "expected": 25 } ],
  "count": { "countedAt": "...", "lines": [ { "itemId": 1, "counted": 25 } ] } }
```
`opening` is the beginning-inventory snapshot (empty before open). `expected` = items with a non-zero expected qty (the ones that must be counted; may include negatives). `count` null until saved. Any item may be counted, including ones not listed.

**POST /truck-periods/:id/open** -> 200 `{ "period": {...} }`. 409 `This period is already open.` or `short` (warehouse can't cover pending loads; nothing changes).
**POST .../start-count** -> 200 `{ period }`; 409 unless status `open`.
**PUT .../count** `{ "lines": [ { "itemId": 1, "counted": 25 } ] }` (replaces the whole count; counted whole >= 0) -> 200 `{ "report": <report> }` (live preview). 409 unless status `counting`.
**GET .../report** and **POST .../close** return a report:
```json
{ "report": { "periodId": 2, "month": "2026-10", "status": "counting",
  "truck": { "id": 2, "name": "Truck 2", "plate": "ABC 123", "driverName": "Lito" },
  "generatedAt": "...", "preview": true,
  "counted": true, "countedAt": "...", "countedBy": { "id": 2, "firstName": "Jose", "lastName": "Reyes" },
  "complete": true, "uncountedItems": [ { "itemId": 2, "name": "...", "expected": 5 } ],
  "matched": false,
  "totals": { "missingQty": 1, "missingValue": 8500, "surplusQty": 1, "surplusValue": 3800 },
  "lines": [ { "itemId": 2, "name": "White sugar, 1 kg", "unit": "kg", "retailPrice": 8500,
      "opening": 10, "toppedUp": 0, "returned": 2, "sold": 3, "expected": 5, "counted": 4, "variance": -1,
      "status": "missing", "value": 8500, "hasCount": true } ],
  "sales": { "count": 3, "total": 159300, "oversold": 2, "late": 2 } } }
```
`lines` has every item with a non-zero expected or counted qty. `value` is always positive (`|variance| x retail`). `matched=false` before a count exists. A closed period returns its frozen copy (`status:"closed"`, plus `closedAt`, `preview:false`).
**POST .../close** -> 200 `{ "period": {...closed}, "report": {...}, "nextPeriod": { "id", "month", "status": "draft" } }`. 409: not counting ("Start and save the count"), already closed, or items uncounted: `{ "error": "Count every item on the truck: A, B.", "uncounted": [ { itemId, name, expected } ] }`.

### POST /truck-sales/batch

Same shape as `POST /orders/batch`. Up to 100 sales (400 beyond). AGENT uses their assigned active truck (403 `You are not assigned to a truck.` otherwise); MANAGER must pass `truckId` (400 `fields.truckId`).

Request:
```json
{ "customers": [ { "clientUuid": "c-1", "name": "Aling Rosa Store", "address": "Brgy. Daro", "lat": 9.31, "lng": 123.3 } ],
  "sales": [ { "clientUuid": "5f0c...-uuid", "customerClientUuid": "c-1", "soldAt": "2026-10-27T05:18:50Z",
      "lines": [ { "itemId": 1, "qty": 5 }, { "itemId": 2, "qty": 3 } ] } ] }
```
- `customers`: upserted first, create-only, exactly like orders (a bad location is dropped with a `warning`). Customer is optional on a sale (walk-in): give `customerClientUuid` (a store in this or an earlier batch) or `customerId`, or neither. An unknown store fails that sale.
- Prices are computed on the server (pack pricing, `docs/pricing.md`); client prices are ignored.
- **Idempotent by `clientUuid`**: a replay returns the stored sale and changes nothing. A `clientUuid` belonging to another truck fails with an error.

Response 200 (even when individual sales fail):
```json
{ "customers": [ { "clientUuid": "c-1", "id": 9 } ],
  "results": [
    { "clientUuid": "5f0c...", "ok": true, "sale": { "id": 3326, "clientUuid": "5f0c...", "truckId": 2, "periodId": 2,
        "soldAt": "2026-10-27T05:18:50.000Z", "customerId": 9, "total": 56500, "oversold": false, "late": false,
        "lines": [ { "itemId": 1, "qty": 5, "unitPrice": 6200, "lineTotal": 31000,
          "breakdown": [ { "packTypeId": null, "name": null, "size": 1, "count": 5, "price": 6200, "amount": 31000 } ] } ] } },
    { "clientUuid": "x", "ok": false, "error": "Unknown item 999." } ] }
```
`ok:false` errors (do not retry unchanged): `Missing sale id.`, `Unknown store.`, `Unknown item N.`, line validation, `That sale id belongs to another truck.`, `This truck has no period to post the sale to. Ask a manager to create one.`

### Sync pull — `truck` block

`GET /sync/pull` (M, A) response gains a top-level `truck`: for an AGENT assigned to an **active** truck, else `null` (managers, booking agents, unassigned or deactivated truck).
```json
"truck": { "id": 2, "name": "Truck 2", "plate": "ABC 123", "driverName": "Lito",
  "period": { "id": 2, "month": "2026-10", "status": "open" },
  "stock": [ { "itemId": 1, "qty": 25 }, { "itemId": 9, "qty": -1 } ] }
```
- `period` is the truck's current non-closed period (`draft` | `open` | `counting`) or `null`. Tablets sell only while `open` and show "Waiting for manager approval" while `draft`; a `counting` truck is being counted (sales still sync if already made).
- `stock` is always the **full** list of non-zero rows (never incremental, independent of `since`): the tablet replaces its copy. Devices compute availability as `stock.qty - queued unsynced truck-sale lines`.
- Older clients ignore the unknown key; field order contract otherwise unchanged (`docs/field-api.md`).
