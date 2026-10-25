require('dotenv').config({ path: require('path').join(__dirname, '..', '.env') });
const bcrypt = require('bcryptjs');
const prisma = require('../src/db');

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

async function main() {
  const now = new Date();
  await prisma.$executeRawUnsafe('TRUNCATE sale_lines, sales, stock_moves, items, categories, users, settings RESTART IDENTITY CASCADE');

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
        createdById: users.INVENTORY.id,
        createdAt: histStart
      }
    });
    items.push({ id: row.id, base, price: price100, reorder: base * 2, qty: stock });
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

  await batched('sale', sales);
  await batched('saleLine', lines);
  await batched('stockMove', moves);
  await prisma.$executeRawUnsafe(`SELECT setval(pg_get_serial_sequence('sales','id'), ${Math.max(saleId, 1)})`);
  await prisma.$executeRawUnsafe(`SELECT setval(pg_get_serial_sequence('sale_lines','id'), ${Math.max(lineId, 1)})`);

  for (const it of items) {
    await prisma.item.update({ where: { id: it.id }, data: { qtyOnHand: it.qty } });
  }

  const gross = sales.reduce((s, x) => s + x.total, 0);
  const mism = await prisma.$queryRawUnsafe(
    'SELECT count(*)::int AS n FROM items i WHERE i.qty_on_hand <> (SELECT COALESCE(SUM(change),0) FROM stock_moves WHERE item_id = i.id)'
  );
  console.log('Seed complete');
  console.log(`  users: ${USERS.length} (password ${PASSWORD})`);
  console.log(`  categories: ${CATEGORIES.length}, items: ${items.length}`);
  console.log(`  sales: ${sales.length}, lines: ${lines.length}, stock moves: ${moves.length}`);
  console.log(`  gross: P${(gross / 100).toLocaleString('en-PH')} over ${DAYS} days (avg P${Math.round(gross / 100 / DAYS).toLocaleString('en-PH')}/day)`);
  console.log(`  qty/ledger mismatches: ${mism[0].n}`);
}

main().catch((e) => { console.error(e); process.exit(1); }).finally(() => prisma.$disconnect());
