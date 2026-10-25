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

const MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
export function longDate(iso) {
  const d = new Date(iso);
  return MON[d.getMonth()] + ' ' + d.getDate() + ', ' + d.getFullYear();
}
