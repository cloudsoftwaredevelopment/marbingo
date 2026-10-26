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


// What an order is worth: the delivered amount once delivered (partial deliveries), else the ordered total.
export const deliveredTotal = (o) => o.lines.reduce((a, l) => a + (l.deliveredQty ?? l.qty) * l.unitPrice, 0);
export const orderTotal = (o) => (o.status === 'delivered' ? deliveredTotal(o) : o.total);
