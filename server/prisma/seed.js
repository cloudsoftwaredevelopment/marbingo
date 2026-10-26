require('dotenv').config({ path: require('path').join(__dirname, '..', '.env') });
const bcrypt = require('bcryptjs');
const prisma = require('../src/db');
const { routeBetween } = require('../src/lib/osrm');
const { manilaParts, haversine } = require('../src/lib/tracking');
const { recomputeDay } = require('../src/routes/tracking');

const DAYS = 120;
const MANILA_MS = 8 * 3600 * 1000;
const PASSWORD = 'marbingo123';

const CATEGORIES = ['Rice & grains', 'Canned goods', 'Noodles', 'Oil & condiments', 'Baking', 'Beverages', 'Snacks', 'Household'];

// name, category, unit, price (pesos), base daily units, opening stock
const ITEMS = [
  ['Jasmine rice, 1 kg', 'Rice & grains', 'kg', 62, 90, 600],
  ['White sugar, 1 kg', 'Baking', 'kg', 85, 55, 420],
  ['Cooking oil, 1 L', 'Oil & condiments', 'bottle', 110, 40, 380],
  ['Sardines in tomato sauce, 155 g', 'Canned goods', 'can', 26, 120, 520],
  ['Pancit canton, 60 g', 'Noodles', 'pack', 16, 150, 700],
  ['3-in-1 coffee, 10 sachets', 'Beverages', 'pack', 72, 60, 260],
  ['Iodized salt, 500 g', 'Oil & condiments', 'pack', 22, 35, 300],
  ['Corned beef, 150 g', 'Canned goods', 'can', 48, 30, 340],
  ['Evaporated milk, 370 ml', 'Canned goods', 'can', 38, 45, 260],
  ['Soy sauce, 1 L', 'Oil & condiments', 'bottle', 55, 25, 180],
  ['All-purpose flour, 1 kg', 'Baking', 'kg', 58, 20, 210],
  ['Mung beans, 500 g', 'Rice & grains', 'pack', 60, 18, 160]
];

// Items (by index) with no wholesale price, to exercise the sellingPrice fallback.
const NO_WHOLESALE = new Set([4, 8, 11]);

const STORES = [
  ['Aling Nena Store', 'Nena Bautista', '0917 301 4482', 'Brgy. Bantayan, Dumaguete City'],
  ['Mang Berting Sari-Sari', 'Alberto Ganzon', '0928 554 1907', 'Brgy. Daro, Dumaguete City'],
  ['Tindahan ni Lourdes', 'Lourdes Tacang', '0935 220 6613', 'Brgy. Piapi, Dumaguete City'],
  ['JR Mini Mart', 'Jun-Jun Sabando', '0919 874 3321', 'Brgy. Calindagan, Dumaguete City'],
  ['Kuya Dodong Store', 'Rodolfo Alingasa', '0905 417 8826', 'Brgy. Bagacay, Dumaguete City'],
  ['Ate Beth Variety Store', 'Elizabeth Fernandez', '0917 663 2094', 'Brgy. Taclobo, Dumaguete City'],
  ['Mercy Grocery', 'Mercedes Lacson', '0926 190 7745', 'Brgy. Looc, Dumaguete City'],
  ['Sampaguita Sari-Sari', 'Rosalinda Jalandoni', '0945 338 5158', 'Brgy. Camanjac, Dumaguete City']
];

// Dumaguete City barangays. The last store is left unplaced on purpose. Source: gps (agent) or manual (web pin).
const STORE_COORDS = [
  [9.3232, 123.3020, 'gps', 9], [9.3270, 123.3077, 'gps', 7], [9.2995, 123.3035, 'gps', 12], [9.3110, 123.2905, 'gps', 6],
  [9.3045, 123.2850, 'gps', 14], [9.3150, 123.2985, 'manual', null], [9.2930, 123.3040, 'manual', null], null
];
const SHOP = { lat: 9.3105, lng: 123.3070 }; // near the public market, Dumaguete City
const WORK = { start: 7 * 60, end: 18 * 60 };
const TICK_S = 15;

const USERS = [
  ['Maria', 'Santos', 'MANAGER', 'maria@marbingo.test', 'EMP-0001'],
  ['Jose', 'Reyes', 'INVENTORY', 'jose@marbingo.test', 'EMP-0002'],
  ['Ana', 'Cruz', 'CASHIER', 'ana@marbingo.test', 'EMP-0003'],
  ['Ramon', 'Dela Cruz', 'AGENT', 'ramon@marbingo.test', 'EMP-0004']
];

const rand = (a, b) => a + Math.random() * (b - a);
const randInt = (a, b) => Math.floor(rand(a, b + 1));

// Manila local wall-clock → UTC instant.
const manila = (y, m, d, h = 0, mi = 0, s = 0) => new Date(Date.UTC(y, m, d, h, mi, s) - MANILA_MS);

async function batched(model, rows) {
  for (let i = 0; i < rows.length; i += 5000) await prisma[model].createMany({ data: rows.slice(i, i + 5000) });
}

// Points along a polyline [[lat, lng]] at a given speed, one every TICK_S seconds. Returns the points and the end time.
function drive(coords, startMs, kmh) {
  const out = [];
  const seg = [];
  let total = 0;
  for (let i = 1; i < coords.length; i++) {
    const d = haversine(coords[i - 1][0], coords[i - 1][1], coords[i][0], coords[i][1]);
    seg.push(d);
    total += d;
  }
  const at = (dist) => {
    let i = 0;
    while (i < seg.length - 1 && dist > seg[i]) { dist -= seg[i]; i++; }
    const f = seg[i] ? Math.min(1, dist / seg[i]) : 0;
    const a = coords[i], b = coords[i + 1];
    return { lat: a[0] + (b[0] - a[0]) * f, lng: a[1] + (b[1] - a[1]) * f, bearing: (Math.atan2(b[1] - a[1], b[0] - a[0]) * 180 / Math.PI + 360) % 360 };
  };
  let t = startMs;
  let dist = 0;
  while (dist < total) {
    const v = (kmh / 3.6) * rand(0.85, 1.15);
    dist = Math.min(total, dist + v * TICK_S);
    t += TICK_S * 1000;
    const p = at(dist);
    out.push({ t, lat: p.lat + rand(-3, 3) / 111195, lng: p.lng + rand(-3, 3) / 111195, speed: v, bearing: Math.round(p.bearing) });
  }
  return { points: out, end: t };
}

// Stationary points around a place (5-12 m jitter) until endMs.
function dwell(at, startMs, endMs) {
  const out = [];
  for (let t = startMs + TICK_S * 1000; t <= endMs; t += TICK_S * 1000) {
    const r = rand(5, 12);
    const a = rand(0, 2 * Math.PI);
    out.push({
      t, lat: at.lat + (r * Math.cos(a)) / 111195, lng: at.lng + (r * Math.sin(a)) / (111195 * Math.cos(at.lat * Math.PI / 180)),
      speed: 0, bearing: null
    });
  }
  return { points: out, end: out.length ? out[out.length - 1].t : startMs };
}

// 7 days of Ramon's field days (Mon-Sat, work hours only): shop -> stores -> shop, lunch at the shop.
async function seedTracks(agent, customers, now, todayUtc) {
  const stores = customers.filter((c) => c.lat != null);
  const rows = [];
  const days = [];
  for (let back = 6; back >= 0; back--) {
    const dayMs = todayUtc - back * 86400000 - MANILA_MS;
    if (new Date(todayUtc - back * 86400000).getUTCDay() === 0) continue;
    const cutoff = Math.min(dayMs + (WORK.end * 60 - TICK_S) * 1000, back === 0 ? now.getTime() : Infinity);
    let t = dayMs + (WORK.start + randInt(8, 30)) * 60000;
    if (t >= cutoff) continue;
    days.push(manilaParts(new Date(t)).day);

    const plan = [...stores].sort(() => Math.random() - 0.5).slice(0, randInt(3, 5));
    let pos = { lat: SHOP.lat, lng: SHOP.lng };
    const pts = [];
    const push = (r) => { for (const p of r.points) if (p.t <= cutoff) pts.push(p); return r.end; };
    t = push(dwell(pos, t - TICK_S * 1000, t + randInt(3, 8) * 60000));
    const visit = async (place, stayMin) => {
      const route = await routeBetween(pos, place);
      t = push(drive(route.coords, t, rand(15, 35)));
      t = push(dwell(place, t, t + stayMin * 60000));
      pos = { lat: place.lat, lng: place.lng };
    };
    const half = Math.ceil(plan.length / 2);
    for (const s of plan.slice(0, half)) await visit(s, rand(5, 25));
    await visit(SHOP, rand(40, 60));
    for (const s of plan.slice(half)) await visit(s, rand(5, 25));
    await visit(SHOP, back === 0 ? 24 * 60 : rand(10, 30));

    let battery = randInt(92, 100);
    pts.forEach((p, i) => {
      if (i % 40 === 39) battery = Math.max(15, battery - randInt(1, 2));
      rows.push({
        agentId: agent.id, recordedAt: new Date(p.t), lat: p.lat, lng: p.lng, accuracy: Math.round(rand(5, 15) * 10) / 10,
        speed: Math.round(p.speed * 10) / 10, bearing: p.bearing, battery
      });
    });
  }
  for (let i = 0; i < rows.length; i += 5000) await prisma.locationPoint.createMany({ data: rows.slice(i, i + 5000) });
  let stops = 0;
  for (const day of days) stops += await recomputeDay(agent.id, day);
  return { points: rows.length, days: days.length, stops };
}

async function main() {
  const now = new Date();
  await prisma.$executeRawUnsafe('TRUNCATE track_cache, agent_stops, location_points, order_lines, orders, customers, sale_lines, sales, stock_moves, items, categories, users, settings RESTART IDENTITY CASCADE');

  await prisma.setting.createMany({ data: [{ key: 'shop_name', value: 'Marbingo' }, { key: 'shop_mark', value: 'MB' }] });

  const passwordHash = await bcrypt.hash(PASSWORD, 10);
  const users = {};
  for (const [firstName, lastName, role, email, employeeId] of USERS) {
    users[role] = await prisma.user.create({
      data: {
        firstName, lastName, role, email, employeeId, passwordHash,
        mobile: '0917 555 01' + employeeId.slice(-2),
        address: 'Poblacion, Dumaguete City',
        createdAt: role === 'MANAGER' ? new Date('2024-03-04T00:00:00+08:00') : undefined
      }
    });
  }

  const cats = {};
  for (const name of CATEGORIES) cats[name] = (await prisma.category.create({ data: { name } })).id;

  const histStart = new Date(now.getTime() - DAYS * 86400000);
  const items = [];
  for (let i = 0; i < ITEMS.length; i++) {
    const [name, cat, unit, price, base, stock] = ITEMS[i];
    const price100 = price * 100;
    const row = await prisma.item.create({
      data: {
        name, unit,
        description: null,
        categoryId: cats[cat],
        sku: '480' + String(1000000000 + i * 7919 + 100003).padStart(10, '0'),
        qtyOnHand: stock,
        reorderLevel: base * 2,
        costPrice: Math.round(price100 * 0.8),
        sellingPrice: price100,
        wholesalePrice: NO_WHOLESALE.has(i) ? null : Math.round(price100 * 0.9),
        createdById: users.INVENTORY.id,
        createdAt: histStart
      }
    });
    items.push({ id: row.id, base, price: price100, wholesale: row.wholesalePrice, reorder: base * 2, qty: stock });
  }

  const moves = items.map((it) => ({ itemId: it.id, change: it.qty, reason: 'initial', at: histStart, byId: users.INVENTORY.id }));
  const sales = [];
  const lines = [];
  let saleId = 0;
  let lineId = 0;

  const totalBase = items.reduce((s, it) => s + it.base, 0);
  const cum = [];
  items.reduce((s, it, i) => (cum[i] = s + it.base), 0);
  const pick = () => {
    const r = Math.random() * totalBase;
    return items[cum.findIndex((c) => r < c)];
  };

  const nowM = new Date(now.getTime() + MANILA_MS);
  const todayUtc = Date.UTC(nowM.getUTCFullYear(), nowM.getUTCMonth(), nowM.getUTCDate());

  for (let d = DAYS - 1; d >= 0; d--) {
    const day = new Date(todayUtc - d * 86400000);
    const y = day.getUTCFullYear(), m = day.getUTCMonth(), dd = day.getUTCDate();
    const dow = day.getUTCDay();
    const dayFactor = (dow === 0 || dow === 6 ? 1.2 : dow === 5 ? 1.1 : 0.95) * rand(0.88, 1.12);

    // Morning restock before the shelf would dip under the reorder level.
    for (const it of items) {
      if (it.qty - it.base * dayFactor * 1.5 < it.reorder) {
        const add = Math.round((it.base * rand(7, 10)) / 10) * 10;
        moves.push({ itemId: it.id, change: add, reason: 'restock', at: manila(y, m, dd, 6, randInt(0, 40)), byId: users.INVENTORY.id });
        it.qty += add;
      }
    }

    const receipts = randInt(15, 40);
    const meanQty = Math.max(1, (totalBase * dayFactor) / (receipts * 2.5));
    const times = [];
    for (let r = 0; r < receipts; r++) times.push(manila(y, m, dd, 7, 0, randInt(0, 11 * 3600 - 1)));
    times.sort((a, b) => a - b);

    for (const soldAt of times) {
      if (soldAt > now) continue;
      const roll = Math.random();
      const cashier = roll < 0.78 ? users.CASHIER : roll < 0.98 ? users.AGENT : users.MANAGER;
      const channel = cashier.role === 'AGENT' ? 'field' : 'counter';
      const chosen = new Map();
      const nLines = randInt(1, 4);
      for (let k = 0; k < nLines; k++) {
        const it = pick();
        if (chosen.has(it)) continue;
        const qty = Math.min(it.qty, Math.max(1, Math.round(rand(0.4, 1.6) * meanQty)));
        if (qty > 0) { chosen.set(it, qty); it.qty -= qty; }
      }
      if (!chosen.size) continue;
      const id = ++saleId;
      let total = 0;
      for (const [it, qty] of chosen) {
        const lineTotal = qty * it.price;
        total += lineTotal;
        lines.push({ id: ++lineId, saleId: id, itemId: it.id, qty, unitPrice: it.price, lineTotal });
        moves.push({ itemId: it.id, change: -qty, reason: 'sale', at: soldAt, byId: cashier.id });
      }
      sales.push({ id, soldAt, cashierId: cashier.id, channel, total });
    }
  }

  // Field orders: delivered ones also become sales (and stock moves) in this same pass.
  const customers = [];
  for (let i = 0; i < STORES.length; i++) {
    const [name, ownerName, phone, address] = STORES[i];
    const c = STORE_COORDS[i];
    const loc = c ? {
      lat: c[0], lng: c[1], locationSource: c[2], locationAccuracy: c[3], locatedAt: histStart,
      locatedById: c[2] === 'gps' ? users.AGENT.id : users.MANAGER.id
    } : {};
    customers.push(await prisma.customer.create({
      data: { name, ownerName, phone, address, createdById: users.AGENT.id, createdAt: histStart, ...loc }
    }));
  }
  const statuses = [...Array(20).fill('delivered'), ...Array(7).fill('pending'), ...Array(3).fill('cancelled')]
    .sort(() => Math.random() - 0.5);
  const orderRows = [];
  for (const status of statuses) {
    const ageDays = status === 'delivered' ? rand(4, 21) : status === 'pending' ? rand(0, 6) : rand(1, 18);
    const orderedAt = new Date(now.getTime() - ageDays * 86400000);
    const customer = customers[randInt(0, customers.length - 1)];
    const chosen = new Map();
    for (let k = randInt(1, 4); k > 0; k--) {
      const it = items[randInt(0, items.length - 1)];
      chosen.set(it, (chosen.get(it) || 0) + randInt(5, 30));
    }
    const olines = [...chosen].map(([it, qty]) => {
      const unitPrice = it.wholesale != null ? it.wholesale : it.price;
      return { it, qty, unitPrice, lineTotal: qty * unitPrice, deliveredQty: null };
    });
    const row = {
      status, customerId: customer.id, agentId: users.AGENT.id, orderedAt,
      clientUuid: require('crypto').randomUUID(),
      total: olines.reduce((n, l) => n + l.lineTotal, 0),
      notes: Math.random() < 0.25 ? 'Deliver in the morning' : null,
      olines
    };
    if (status === 'cancelled') row.cancelledReason = Math.random() < 0.5 ? 'Store closed for the week' : 'Customer changed their mind';
    if (status === 'delivered') {
      const deliveredAt = new Date(orderedAt.getTime() + randInt(1, 3) * 86400000);
      const id = ++saleId;
      let total = 0;
      for (const l of olines) {
        l.deliveredQty = Math.min(l.it.qty, l.qty);
        if (l.deliveredQty > 0) {
          l.it.qty -= l.deliveredQty;
          const lt = l.deliveredQty * l.unitPrice;
          total += lt;
          lines.push({ id: ++lineId, saleId: id, itemId: l.it.id, qty: l.deliveredQty, unitPrice: l.unitPrice, lineTotal: lt });
          moves.push({ itemId: l.it.id, change: -l.deliveredQty, reason: 'sale', at: deliveredAt, byId: users.INVENTORY.id });
        }
      }
      sales.push({ id, soldAt: deliveredAt, cashierId: users.AGENT.id, channel: 'field', customerId: customer.id, total });
      Object.assign(row, { deliveredAt, deliveredById: users.INVENTORY.id, saleId: id });
    }
    orderRows.push(row);
  }

  // Field deliveries run after the daily restock pass, so top up anything they left under its reorder level.
  const topUpAt = new Date(Date.now() - 3600000);
  for (const it of items) {
    if (it.qty < it.reorder) {
      const add = it.reorder * 3 - it.qty;
      it.qty += add;
      moves.push({ itemId: it.id, change: add, reason: 'restock', at: topUpAt, byId: users.INVENTORY.id });
    }
  }

  await batched('sale', sales);
  await batched('saleLine', lines);
  await batched('stockMove', moves);
  await prisma.$executeRawUnsafe(`SELECT setval(pg_get_serial_sequence('sales','id'), ${Math.max(saleId, 1)})`);
  await prisma.$executeRawUnsafe(`SELECT setval(pg_get_serial_sequence('sale_lines','id'), ${Math.max(lineId, 1)})`);

  for (const { olines, ...row } of orderRows) {
    await prisma.order.create({
      data: {
        ...row,
        lines: { create: olines.map((l) => ({ itemId: l.it.id, qty: l.qty, unitPrice: l.unitPrice, lineTotal: l.lineTotal, deliveredQty: l.deliveredQty })) }
      }
    });
  }

  for (const it of items) {
    await prisma.item.update({ where: { id: it.id }, data: { qtyOnHand: it.qty } });
  }

  const track = await seedTracks(users.AGENT, customers, now, todayUtc);

  const gross = sales.reduce((s, x) => s + x.total, 0);
  const mism = await prisma.$queryRawUnsafe(
    'SELECT count(*)::int AS n FROM items i WHERE i.qty_on_hand <> (SELECT COALESCE(SUM(change),0) FROM stock_moves WHERE item_id = i.id)'
  );
  console.log('Seed complete');
  console.log(`  users: ${USERS.length} (password ${PASSWORD})`);
  console.log(`  categories: ${CATEGORIES.length}, items: ${items.length}`);
  console.log(`  sales: ${sales.length}, lines: ${lines.length}, stock moves: ${moves.length}`);
  const delivered = orderRows.filter((o) => o.status === 'delivered').length;
  console.log(`  stores: ${customers.length}, wholesale prices: ${items.filter((i) => i.wholesale != null).length}/${items.length}`);
  console.log(`  orders: ${orderRows.length} (${delivered} delivered, ${orderRows.filter((o) => o.status === 'pending').length} pending, ${orderRows.filter((o) => o.status === 'cancelled').length} cancelled)`);
  console.log(`  gross: P${(gross / 100).toLocaleString('en-PH')} over ${DAYS} days (avg P${Math.round(gross / 100 / DAYS).toLocaleString('en-PH')}/day)`);
  console.log(`  tracking: ${track.points} GPS points over ${track.days} days, ${track.stops} stops, ${customers.filter((c) => c.lat == null).length} store(s) without a location`);
  console.log(`  qty/ledger mismatches: ${mism[0].n}`);
}

main().catch((e) => { console.error(e); process.exit(1); }).finally(() => prisma.$disconnect());
