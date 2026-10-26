# Pack pricing (contract)

Items sell **retail per base unit** (`items.unit`: pc, kg, pack, …) at `sellingPrice`. They may also have **packs**, wholesale bundles of `size` base units at a fixed `price` for the whole pack, e.g. Jasmine rice: Sack = 50 kg at ₱2,900. Buyers always enter a quantity in base units, and the system applies the **cheapest combination** of full packs plus loose units automatically. Money is integer centavos.

This replaces the old single `wholesalePrice`/`fieldPrice`. During the transition the server stops reading them; the `items.wholesale_price` column is dropped at the end.

## The rule (implemented 3×, identical results)

Reference: `server/src/lib/pricing.js`. Ports: `client/src/lib/pricing.js` and `android/core/.../pricing/Pricing.kt`. **All three must pass `docs/pricing-vectors.json`**, which holds each case's `expect` (the priceQty result) and `upsell`.

`priceQty(qty, retail, packs)` → `{ total, parts }`
1. `n = max(0, floor(qty))`.
2. Usable packs are those with an integer `size ≥ 2` and an integer `price > 0`, sorted by size descending, then price ascending.
3. If `n ≤ 100000`, run an exact DP over q = 1..n. Each state keeps the key `(cost, loose, packCount)`, compared lexicographically, smaller is better.
   - Start each q with the loose option: `(cost[q-1] + retail, loose[q-1] + 1, cnt[q-1])`, pick = loose.
   - Then for each usable pack i in sorted order with `size ≤ q`: candidate `(cost[q-size] + price, loose[q-size], cnt[q-size] + 1)`. Replace only when the candidate is **strictly better**.
   - Rebuild the result from `pick` going backwards from n.
4. If `n > 100000`, use greedy instead: for each usable pack in sorted order **with `price < size × retail`**, `count = floor(left / size)`. The remainder is sold loose.
5. `parts` lists packs with `count > 0` in sorted order, then the loose part last. A pack part is `{ packTypeId, name, size, count, price, amount = count × price }`. The loose part is `{ packTypeId: null, name: null, size: 1, count, price: retail, amount }`. `total` is the sum of `amount`. When `n = 0`, `parts` is empty.

`suggestUpsell(qty, retail, packs)` → `{ addQty, total, saving } | null`
- It returns null when `n = 0`, when there are no usable packs, or when `n > 100000`.
- Otherwise, for q = n+1 up to n + (largest usable size), it returns the first q where `priceQty(q).total ≤ priceQty(n).total`, as `{ addQty: q − n, total, saving: now − total }`. For example, 49 kg of rice loose costs ₱3,038, so it suggests "Add 1 kg → 1 Sack ₱2,900, save ₱138".

`describe(parts, unit)` → `"1 × Sack (50 kg) + 3 kg"`.

## Pack types (admin list)

```jsonc
{ "id": 5, "name": "Sack", "defaultSize": null, "sort": 5, "active": true }   // Dozen has defaultSize 12
```
- `GET /api/pack-types` (any authenticated user) returns the active types, sorted. `?all=1` includes inactive ones and adds `itemCount`.
- `POST /api/pack-types` (MANAGER) with `{ name, defaultSize? }`. A duplicate name, compared case-insensitively, returns 409 with `fields.name`.
- `PUT /api/pack-types/:id` (MANAGER) with `{ name?, defaultSize?, active? }`.
- `DELETE /api/pack-types/:id` (MANAGER) only works when no item uses the type. Otherwise it returns 409 "In use by N items — deactivate it instead."

## Item packs

The item JSON (GET /api/items, GET /api/items/:id, POST/PUT responses) gains:
```jsonc
"packs": [ { "id": 3, "packTypeId": 5, "name": "Sack", "size": 50, "price": 290000 } ]   // sorted by size desc
```
- `GET /api/items/:id` (any authenticated user) → `{ item }`, for the edit page.
- `POST /api/items` and `PUT /api/items/:id` (MANAGER, INVENTORY) accept `packs` as a **JSON string** (the form is multipart): `[{ packTypeId, size, price }]`, where `price` is a peso decimal string or number for the whole pack.
  - On PUT, sending `packs` replaces the item's whole pack list; omitting it leaves the packs unchanged.
- Validation failures return 400 with `fields.packs` set to a message and `fields.packErrors` listing `{ index, message }`. A pack is invalid when:
  - its type is unknown or inactive;
  - its size isn't an integer of at least 2;
  - its price isn't above 0;
  - its type or size duplicates another pack on the same item.
- A pack whose price per unit is **not lower than retail** is allowed, but the response includes `warnings: ["Sack is not cheaper than buying 50 loose."]`.

## Sales & orders

- **POS** `POST /api/sales {lines:[{itemId, qty}]}`: the server prices each line with `priceQty(qty, item.sellingPrice, item.packs)`. It stores `sale_lines.unit_price = sellingPrice` (retail at the time), `line_total = total` and `breakdown = parts`. The sale response lines include `breakdown`.
- **Field orders** `POST /api/orders/batch`: each line is priced the same way at order time. The order line stores `breakdown` and the **pricing snapshot** `pricing = { retail, packs:[{packTypeId,name,size,price}] }`. The order total is the sum of the line totals.
- **Deliver** `POST /api/orders/:id/deliver`: each delivered quantity is **re-priced from the line's `pricing` snapshot**, never from current prices. The Sale line gets that `breakdown` and `line_total`. Legacy lines with no snapshot use `unit_price × qty` as a single loose part.
- Order JSON lines gain `breakdown` (ordered qty) and `deliveredBreakdown` (null until delivered). Sale JSON lines (`/api/sales/mine`) gain `breakdown`.

## Sync (Android) and reports

- `GET /api/sync/pull`: each item gains `packs` and loses `wholesalePrice` and `fieldPrice`. The response gains a top-level `packTypes: [...]` (active).
- `GET /api/reports/sales`: each `items[]` row gains `soldAs: [{ name, size, count }]` (packs) plus `loose`, aggregated from the `breakdown` of sale lines in range. Legacy lines without a breakdown count as loose.
