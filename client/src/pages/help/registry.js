// Loads every article file in ./articles (each `export default [ {article}, … ]`) and flattens them.
// Adding a new articles/*.js file is enough — no wiring needed.
import { can } from '../../lib/roles';

const modules = import.meta.glob('./articles/*.js', { eager: true });

const RAW = Object.entries(modules).flatMap(([path, m]) => {
  const list = Array.isArray(m.default) ? m.default : [];
  return list.map((a) => ({ ...a, _source: path }));
});

// Category display order; anything else is appended alphabetically after these.
const CATEGORY_ORDER = [
  'Getting started',
  'Items & pricing',
  'Selling',
  'Field orders',
  'Stores',
  'Trucks',
  'Reports',
  'Ecosystem Map',
  'Admin & account',
  'Agent app: getting started',
  'Agent app: orders & stores',
  'Agent app: truck sales',
  'Agent app: troubleshooting',
];

const ALL_ROLES = ['MANAGER', 'INVENTORY', 'CASHIER', 'AGENT'];

// De-duplicate by id (last file wins) and validate the essentials.
const byId = new Map();
for (const a of RAW) {
  if (!a || !a.id || !a.title) continue;
  byId.set(a.id, {
    id: a.id,
    title: a.title,
    app: a.app === 'android' ? 'android' : 'web',
    category: a.category || 'Other',
    roles: Array.isArray(a.roles) && a.roles.length ? a.roles : ALL_ROLES,
    summary: a.summary || '',
    keywords: Array.isArray(a.keywords) ? a.keywords : [],
    blocks: Array.isArray(a.blocks) ? a.blocks : [],
  });
}

export const ARTICLES = [...byId.values()];
export const getArticle = (id) => byId.get(id);

export function visibleFor(user) {
  const role = user?.role;
  return ARTICLES.filter((a) => !role || a.roles.includes(role));
}

export function categoriesFor(articles) {
  const groups = new Map();
  for (const a of articles) {
    if (!groups.has(a.category)) groups.set(a.category, []);
    groups.get(a.category).push(a);
  }
  const order = (c) => {
    const i = CATEGORY_ORDER.indexOf(c);
    return i === -1 ? CATEGORY_ORDER.length : i;
  };
  return [...groups.entries()]
    .sort((a, b) => order(a[0]) - order(b[0]) || a[0].localeCompare(b[0]))
    .map(([name, items]) => ({ name, items }));
}

// Flatten an article's text for searching.
function haystack(a) {
  const text = a.blocks.flatMap((b) => {
    if (b.text) return [b.text];
    if (Array.isArray(b.items)) return b.items.flatMap((it) => (typeof it === 'string' ? [it] : [it.q, it.a, it.text].filter(Boolean)));
    return [];
  });
  return [a.title, a.summary, a.category, ...a.keywords, ...text].join(' \u0001 ').toLowerCase();
}

const HAY = new Map(ARTICLES.map((a) => [a.id, haystack(a)]));

// Simple AND-of-terms ranked search. Title/summary/keyword hits rank above body hits.
export function search(articles, query) {
  const terms = query.toLowerCase().split(/\s+/).filter(Boolean);
  if (!terms.length) return [];
  const scored = [];
  for (const a of articles) {
    const hay = HAY.get(a.id) || '';
    if (!terms.every((t) => hay.includes(t))) continue;
    const head = `${a.title} \u0001 ${a.summary} \u0001 ${a.keywords.join(' ')}`.toLowerCase();
    let score = 0;
    for (const t of terms) {
      if (a.title.toLowerCase().includes(t)) score += 10;
      else if (head.includes(t)) score += 4;
      else score += 1;
    }
    scored.push({ a, score });
  }
  return scored.sort((x, y) => y.score - x.score || x.a.title.localeCompare(y.a.title)).map((s) => s.a);
}
