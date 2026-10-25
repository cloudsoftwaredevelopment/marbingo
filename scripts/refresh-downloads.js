#!/usr/bin/env node
/**
 * Regenerates downloads/index.html from the APKs in downloads/. Run after every build:
 *   node scripts/refresh-downloads.js
 * nginx serves downloads/ straight from disk (see /etc/nginx/sites-available/marbingo).
 */
const fs = require('fs');
const path = require('path');

const DIR = path.join(__dirname, '..', 'downloads');
const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const mb = (bytes) => `${(bytes / 1024 / 1024).toFixed(1)} MB`;
const when = (d) => d.toLocaleString('en-PH', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Manila' });

const apks = fs.readdirSync(DIR)
  .filter((f) => f.toLowerCase().endsWith('.apk'))
  .map((f) => ({ name: f, ...fs.statSync(path.join(DIR, f)) }))
  .sort((a, b) => b.mtimeMs - a.mtimeMs);

const rows = apks.length === 0
  ? '<p class="empty">No build available yet.</p>'
  : apks.map((a, i) => `
    <a class="dl${i === 0 ? ' latest' : ''}" href="./${encodeURIComponent(a.name)}">
      <span><span class="name">${esc(a.name)}</span>
      <span class="meta">${mb(a.size)} · built ${when(new Date(a.mtimeMs))}${i === 0 ? ' · latest' : ''}</span></span>
      <span class="btn">Download</span>
    </a>`).join('');

const html = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Marbingo Field — Download</title>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Bricolage+Grotesque:opsz,wght@12..96,700&family=IBM+Plex+Mono:wght@500&family=IBM+Plex+Sans:wght@400;600&display=swap">
<style>
  * { box-sizing: border-box; }
  body { margin: 0; background: #F4F2EC; color: #1C1B18; font-family: 'IBM Plex Sans', system-ui, sans-serif; padding: 40px 16px 64px; line-height: 1.5; }
  .wrap { max-width: 560px; margin: 0 auto; display: flex; flex-direction: column; gap: 20px; }
  .brand { display: flex; align-items: center; gap: 14px; }
  .mark { width: 56px; height: 56px; border-radius: 14px; background: #1E5B43; color: #E3EEE7; display: flex; align-items: center; justify-content: center; font: 700 22px 'Bricolage Grotesque', system-ui; }
  h1 { margin: 0; font: 700 32px/1.05 'Bricolage Grotesque', system-ui; letter-spacing: -0.01em; }
  .sub { margin: 2px 0 0; color: #5E5A52; font-size: 15px; }
  .card { background: #FFFEFB; border: 1px solid #DDD8CC; border-radius: 14px; padding: 8px 20px; }
  .dl { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 14px 0; border-bottom: 1px solid #ECE8DF; color: inherit; text-decoration: none; min-height: 44px; }
  .dl:last-child { border-bottom: 0; }
  .name { display: block; font: 500 14px 'IBM Plex Mono', monospace; word-break: break-all; }
  .meta { display: block; font-size: 13px; color: #5E5A52; }
  .btn { background: #FFFEFB; color: #1E5B43; border: 1px solid #1E5B43; border-radius: 10px; padding: 10px 16px; font-weight: 600; font-size: 14px; white-space: nowrap; }
  .latest .btn { background: #1E5B43; color: #fff; }
  .empty { color: #5E5A52; text-align: center; padding: 16px 0; }
  h2 { margin: 0 0 8px; font-size: 13px; font-weight: 600; letter-spacing: .08em; text-transform: uppercase; color: #5E5A52; }
  ol { margin: 0; padding-left: 20px; font-size: 15px; }
  li { margin-bottom: 6px; }
  .note { font-size: 14px; color: #8A4209; background: #F7E7D6; border-radius: 12px; padding: 12px 14px; }
</style>
</head>
<body>
<main class="wrap">
  <div class="brand"><div class="mark" aria-hidden="true">MB</div>
    <div><h1>Marbingo Field</h1><p class="sub">Order-taking app for field agents · Android 8.0+</p></div></div>
  <section class="card" aria-label="Downloads">${rows}</section>
  <section class="card" style="padding:16px 20px">
    <h2>Install on the tablet</h2>
    <ol>
      <li>Open this page on the tablet and tap <strong>Download</strong> on the latest build.</li>
      <li>Open the downloaded file. If Android asks, allow your browser to <strong>install unknown apps</strong>.</li>
      <li>Tap <strong>Install</strong>, then open <strong>Marbingo Field</strong> and sign in with your agent account while online.</li>
      <li>To update, install a newer build over the old one. Your queued orders are kept.</li>
    </ol>
  </section>
  <p class="note">Test build: sign-in goes over plain HTTP until the shop's domain and HTTPS are set up. Use test accounts only.</p>
</main>
</body>
</html>
`;
fs.writeFileSync(path.join(DIR, 'index.html'), html);
console.log(`downloads/index.html: ${apks.length} APK(s)`);
