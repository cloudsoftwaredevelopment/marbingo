// All money from the API is integer centavos.
const whole = new Intl.NumberFormat('en-PH', { maximumFractionDigits: 0 });
const cents = new Intl.NumberFormat('en-PH', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

export const pesoWhole = (c) => '₱' + whole.format(Math.round((c || 0) / 100)); // ₱1,234 (reports)
export const peso = (c) => '₱' + cents.format((c || 0) / 100);                    // ₱1,234.00 (prices)
export const num = (n) => whole.format(Math.round(n || 0));
export function pesoCompact(c) {                                                  // ₱101.4k
  const n = (c || 0) / 100;
  if (n >= 1e6) return '₱' + (n / 1e6).toFixed(2) + 'M';
  if (n >= 1e3) return '₱' + (n / 1e3).toFixed(1) + 'k';
  return '₱' + Math.round(n);
}

export const UNITS = [
  { id: 'pc', label: 'Piece', plural: 'pcs' },
  { id: 'pack', label: 'Pack', plural: 'packs' },
  { id: 'kg', label: 'Kilogram', plural: 'kg' },
  { id: 'sack', label: 'Sack', plural: 'sacks' },
  { id: 'box', label: 'Box', plural: 'boxes' },
  { id: 'bottle', label: 'Bottle', plural: 'bottles' },
  { id: 'can', label: 'Can', plural: 'cans' }
];
export const unitPlural = (id) => UNITS.find((u) => u.id === id)?.plural || id;

export const ROLE_LABELS = { MANAGER: 'Manager', INVENTORY: 'Inventory staff', CASHIER: 'Cashier', AGENT: 'Field agent' };

export function initials(first, last) {
  return (((first || '')[0] || '') + ((last || '')[0] || '')).toUpperCase() || '?';
}

// Dates display in the shop's timezone regardless of the browser's.
const dateFmt = new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'Asia/Manila' });
export const longDate = (iso) => dateFmt.format(new Date(iso)); // "Mar 4, 2024"


// Pack pricing helpers (see lib/pricing.js). Parts are { name, size, count, price, amount }; name null = loose.
export const plural = (name, n = 2) => (n === 1 ? name : /(s|x|ch|sh)$/i.test(name) ? name + 'es' : name + 's'); // Sack -> Sacks
export const sumParts = (parts) => (parts || []).reduce((a, x) => a + x.amount, 0);
// "1 × Sack (50 kg) ₱2,900.00 + 3 kg × ₱62.00"
// Unit word for a count: singular code for exactly 1 ("1 can"), plural otherwise ("3 cans").
const sp = (unit, n) => (unit ? ' ' + (n === 1 ? unit : unitPlural(unit)) : '');
export const partsWithMoney = (parts, unit) => (parts || []).map((x) => (x.name
  ? `${x.count} × ${x.name} (${x.size}${sp(unit, x.size)}) ${peso(x.amount)}`
  : `${x.count}${sp(unit, x.count)} × ${peso(x.price)}`)).join(' + ');
// "1 × Sack (50 kg) + 3 kg" (same as pricing.describe, with the unit's display plural)
export const partsSummary = (parts, unit) => (parts || []).map((x) => (x.name
  ? `${x.count} × ${x.name} (${x.size}${sp(unit, x.size)})`
  : `${x.count}${sp(unit, x.count)}`)).join(' + ');
// "Sack 50 kg · ₱2,900"
export const packLabel = (p, unit) => `${p.name} ${p.size} ${unitPlural(unit)} · ${peso(p.price).replace(/\.00$/, '')}`;

// What an order is worth: the delivered amount once delivered (partial deliveries), else the ordered total.
// Lines carry a pack-priced deliveredBreakdown; older lines fall back to qty × unitPrice.
export const deliveredLineTotal = (l) => (l.deliveredBreakdown ? sumParts(l.deliveredBreakdown) : (l.deliveredQty ?? l.qty) * l.unitPrice);
export const deliveredTotal = (o) => o.lines.reduce((a, l) => a + deliveredLineTotal(l), 0);
export const orderTotal = (o) => (o.status === 'delivered' ? deliveredTotal(o) : o.total);
