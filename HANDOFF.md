# Dry Goods Shop Dashboard — Build Handoff

This folder holds the design for a back-office web dashboard for a dry goods shop. Office staff use it to add stock items, view sales reports, and manage their own profile.

- `design/*.dc.html` hold the visual reference: layout, spacing, colors, copy and interaction behavior. They use a design-tool runtime (`<x-dc>`, `{{holes}}`, `<sc-for>`, `<sc-if>`, `class Component extends DCLogic`). **Do not ship them.** Rebuild each screen as real components in the project's stack. The data arrays in their `<script>` blocks are sample data. Replace them with API/database data.
- This file is the spec. Where it disagrees with the design files, this file wins.

---

## 1. App shell

Every screen uses a two-column layout: a dark sidebar on the left and a scrolling content area on the right. Below about 840px wide, the sidebar stacks above the content. Neither column is sticky.

**Sidebar** (`design/Sidebar.dc.html`), from top to bottom:
1. Shop logo mark + `[Shop name]` + "Dry goods · Back office"
2. Nav: **Sales report** (home), **Add item**, **My profile**. The active item gets a light background and `aria-current="page"`.
3. **Top 10 items** panel (see §3)
4. Signed-in user chip (avatar/initials, name, role). It links to My profile.

## 2. Sales report — home (`design/Main.dc.html`)

**Date filter** (pill buttons with `aria-pressed`): Today · This week · This month (default) · Last month · Custom.
- Custom shows **From** and **To** date inputs. If From is after To, show "Choose a start date before the end date" and no data.
- Show the resolved date range at the top right of the header (e.g. "Oct 1 – 31, 2026").
- Weeks start on Monday. Use the shop's local timezone (Asia/Manila) for every boundary.

**Totals** (4 cards):
| Card | Definition |
|---|---|
| Gross sales | Sum of line totals (qty × unit price) in range |
| Transactions | Count of sales/receipts in range |
| Units sold | Sum of line quantities |
| Average sale | Gross sales ÷ transactions |

**Sales over time** (bar chart), with the bucket size set by the filter:
- Today → by hour (store hours)
- This week → by day (Mon–Sun)
- This month / Last month / Custom → by week (7-day chunks starting at the range start)
- Buckets that are still in the future show as a dashed outline with "—". Each bar has a tooltip with the exact ₱ amount.

**Sales by item** (table, sorted by revenue descending): rank, item name and category, units sold, revenue, share of sales (a bar plus a %). Inside its container, the table scrolls horizontally on narrow screens.

## 3. Top 10 items (sidebar panel)

The panel has a toggle with two modes:
- **Turnover** (default): turnover = units sold in period ÷ average stock on hand in period. Display as `5.6×`. Show the caption: "Turnover = units sold ÷ average stock on hand. Higher means the item sells through faster."
- **Sales**: ranked by revenue in the period. Display as compact ₱ (`₱101.4k`).

Each row shows rank, item name, value and a thin bar scaled to the #1 item. The period is fixed to **this month** in the design.

> Average stock needs stock history. The simplest version is (opening stock + closing stock) ÷ 2 for the period, using daily stock snapshots or the stock-movement ledger. Decide this before building.

## 4. Add item (`design/AddItem.dc.html`)

| Field | Type | Required | Notes |
|---|---|---|---|
| Item name | text | ✅ | |
| Description | textarea | | |
| Category | select | | Rice & grains, Canned goods, Noodles, Oil & condiments, Baking, Beverages, Snacks, Household. Should be admin-editable. |
| SKU / barcode | text | | Monospace; should accept a barcode scanner, which types digits and then Enter |
| Quantity in stock | integer ≥ 0 | ✅ | |
| Unit | select | | Piece, Pack, Kilogram, Sack, Box, Bottle, Can |
| Reorder level | integer ≥ 0 | | Hint: "Flag as low stock at or below this." |
| Cost price (₱) | decimal | | |
| Selling price (₱) | decimal | ✅ | |
| Photo | image upload | | JPG/PNG/WEBP. Drop zone + click-to-browse. |

- **Margin per unit** is a read-only box: `price − cost` and `%` of price. It shows "—" until both are filled.
- **Live preview card** on the right: photo (or placeholder), category, name, description, price and stock badge:
  - qty empty → "Stock not set" (grey)
  - qty ≤ 0 → "Out of stock" (red)
  - qty ≤ reorder level → "Low stock" (amber)
  - else → "In stock" (green)
- Actions: **Cancel** (back to Sales report) · **Save & add another** (saves, then clears the form) · **Save item** (primary).
- Validation runs on save and shows an inline message under each failing required field. A successful save shows a status banner, e.g. "“Jasmine rice, 1 kg” was added to inventory with 120 pcs in stock."
- Server side: validate the same rules, enforce a unique SKU when one is given, and store the image (resized) with its URL on the item. Record who created the item and when.

## 5. My profile (`design/Profile.dc.html`)

- **Summary card**: avatar (photo or initials) + "Change photo" upload, full name, role. Read-only: Employee ID, Role, Member since, with the note "Role and employee ID are managed by the shop admin."
- **Personal information** form: first name, last name, email, mobile number, address. "Save changes" shows "Changes saved."
- **Change password** form: current password, new password (min 8 chars), confirm. Errors: missing current password / too short / passwords don't match. Success: "Password updated." Clear the fields after success. The server must verify the current password.

## 6. Suggested data model

```
users        id, first_name, last_name, email, mobile, address, photo_url, role, employee_id, password_hash, created_at
categories   id, name
items        id, name, description, category_id, sku (unique, nullable), unit, qty_on_hand, reorder_level,
             cost_price, selling_price, image_url, created_by → users.id, created_at, updated_at
sales        id, sold_at (timestamptz), cashier_id → users.id, total
sale_lines   id, sale_id, item_id, qty, unit_price, line_total
stock_moves  id, item_id, change (+/-), reason (initial, sale, restock, adjustment), at, by → users.id   -- for turnover/avg stock
```

Store money as integer centavos or as decimal(12,2). Never use floats.

**Report endpoints** (example):
- `GET /api/reports/sales?from=YYYY-MM-DD&to=YYYY-MM-DD&bucket=hour|day|week` → `{ totals, buckets[], items[] }`
- `GET /api/reports/top-items?period=this_month&by=turnover|revenue&limit=10`
- Presets (today, this week, …) resolve to from/to on the client or the server. Pick one place and use it consistently.

## 7. Design tokens

| Token | Value | Use |
|---|---|---|
| ground | `#F4F2EC` | page background |
| surface | `#FFFEFB` | cards |
| ink | `#1C1B18` | text, sidebar background |
| muted | `#5E5A52` | secondary text (passes 4.5:1 on ground) |
| line | `#DDD8CC` / `#ECE8DF` | borders, row dividers |
| input border | `#CFC9BB` | |
| accent | `#1E5B43` | primary buttons, bars, active pills |
| accent tint | `#E3EEE7` | success banner, In stock badge |
| sidebar panel | `#2A2925` | Top 10 card |
| sidebar muted | `#A9A497` / `#D9D5CB` | |
| sidebar accent | `#7BC3A0` | Top 10 bars, active toggle |
| warning | `#8A4209` on `#F7E7D6` | Low stock |
| danger | `#A12A1E` (text) · `#8E2A20` on `#F2DEDC` | errors, Out of stock |

- **Fonts** (Google Fonts): *Bricolage Grotesque* 600/700 for headings and big numbers, *IBM Plex Sans* 400/500/600 for body text, *IBM Plex Mono* 400/500 for figures, SKUs and dates.
- **Radii**: cards 14px · inputs and buttons 10px · pills 999px.
- **Controls**: minimum height 44px.
- **Currency format**: `₱1,234` (reports) and `₱1,234.00` (prices), using `en-PH` locale.

## 8. Accessibility

Use real `<button>`, `<a>`, `<label for>` and `<input>` elements. Icon SVGs are `aria-hidden`. Save confirmations use `role="status"`. Hidden file inputs stay keyboard-reachable through their `<label>`.

## 9. Open decisions for the developer

1. Auth/login and roles (staff vs admin). They are not designed yet.
2. Where sales come from: an existing POS, or a sales-entry screen to be built.
3. The method for calculating average stock (§3).
4. Image storage (local disk, S3, Cloudinary, …).
5. Screens not designed yet: inventory list (search, filter, edit, low-stock view) and an edit-item form, which reuses the Add item form.
