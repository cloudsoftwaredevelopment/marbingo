# Knowledge base article format (client/src/pages/help/articles/*.js)

Each file: `export default [ ...articles ]`. Plain JS data only — no JSX, no imports.

```js
{
  id: 'record-a-sale',            // unique kebab-case, prefix with your area (e.g. 'pos-', 'map-', 'app-')
  title: 'Record a walk-in sale',  // task-style, imperative or "How …"
  app: 'web',                      // 'web' (back office) | 'android' (Marbingo Field app)
  category: 'Selling',             // the section heading this article is grouped under (use the one given to you)
  roles: ['MANAGER', 'CASHIER'],   // who should see it: MANAGER, INVENTORY, CASHIER, AGENT (all four = everyone)
  summary: 'One sentence shown in lists and search results.',
  keywords: ['pos', 'cash', 'receipt'],  // extra search words (synonyms, Tagalog/Taglish terms welcome)
  blocks: [
    { type: 'p', text: 'Paragraph.' },
    { type: 'h', text: 'Sub-heading' },
    { type: 'steps', items: ['Open **Record sale** in the sidebar.', 'Scan or search the item…'] },  // numbered
    { type: 'list', items: ['Bullet', 'Bullet'] },
    { type: 'tip', text: 'Helpful shortcut.' },
    { type: 'note', text: 'Neutral info / why it works this way.' },
    { type: 'warn', text: 'Something that loses data or money if done wrong.' },
    { type: 'faq', items: [{ q: 'Question?', a: 'Answer.' }] },
  ],
}
```

Inline markup inside any text: `**bold**` for on-screen labels/buttons exactly as they appear in the UI,
and `[[article-id|link text]]` to link another article (only ids you created, or ids listed in the brief).
