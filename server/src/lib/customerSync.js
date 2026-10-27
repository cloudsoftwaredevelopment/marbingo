// Offline store upsert shared by the orders and truck-sales batch endpoints.
const prisma = require('../db');
const { parseLocation } = require('./orders');

const text = (v) => (v === undefined || v === null ? null : String(v).trim() || null);

async function upsertCustomers(list, user) {
  const out = [];
  for (const c of list) {
    const clientUuid = typeof (c && c.clientUuid) === 'string' ? c.clientUuid.trim() : '';
    const name = text(c && c.name);
    if (!clientUuid || !name) continue;
    // Create-only: a replayed batch must not overwrite edits made since (on the web or via PUT, which
    // the app uses to change existing stores). An invalid location is dropped and reported, not fatal,
    // so one bad fix can't block the offline queue forever.
    let row = await prisma.customer.findUnique({ where: { clientUuid } });
    let warning;
    if (!row) {
      const data = { name, ownerName: text(c.ownerName), phone: text(c.phone), address: text(c.address), notes: text(c.notes) };
      const loc = parseLocation(c, user);
      if (loc.error) warning = 'Location ignored: ' + Object.values(loc.fields).join(' ');
      else if (loc.data) Object.assign(data, loc.data);
      try {
        row = await prisma.customer.create({ data: { ...data, clientUuid, createdById: user.id } });
      } catch (e) {
        if (e.code !== 'P2002') throw e;
        row = await prisma.customer.findUnique({ where: { clientUuid } });
      }
    }
    out.push(warning ? { clientUuid, id: row.id, warning } : { clientUuid, id: row.id });
  }
  return out;
}

module.exports = { text, upsertCustomers };
