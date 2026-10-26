require('dotenv').config({ path: require('path').join(__dirname, '..', '.env') });
const path = require('path');
const fs = require('fs');
const express = require('express');
const cors = require('cors');

const app = express();
app.set('trust proxy', 1);
app.use(cors());
app.use(express.json({ limit: '1mb' }));

app.use('/uploads', express.static(path.join(__dirname, '..', 'uploads'), { maxAge: '7d' }));

app.use('/api/auth', require('./routes/auth'));
app.use('/api/profile', require('./routes/profile'));
app.use('/api/users', require('./routes/users'));
app.use('/api/settings', require('./routes/settings'));
app.use('/api/categories', require('./routes/categories'));
app.use('/api/items', require('./routes/items'));
app.use('/api/sales', require('./routes/sales'));
app.use('/api/reports', require('./routes/reports'));
app.use('/api/customers', require('./routes/customers'));
app.use('/api/orders', require('./routes/orders'));
app.use('/api/sync', require('./routes/sync'));
app.use('/api/tracking', require('./routes/tracking'));

app.use('/api', (req, res) => res.status(404).json({ error: 'Not found.' }));

// Serve the built SPA (production / local verification without nginx).
const dist = path.join(__dirname, '..', '..', 'client', 'dist');
if (fs.existsSync(dist)) {
  app.use(express.static(dist));
  app.get('*', (req, res) => res.sendFile(path.join(dist, 'index.html')));
}

app.use((err, req, res, next) => {
  if (err.code === 'P2002') return res.status(409).json({ error: 'That value is already in use.' });
  if (err.code === 'LIMIT_FILE_SIZE') return res.status(400).json({ error: 'Image must be 8 MB or smaller.' });
  console.error(err);
  if (err.expose) return res.status(err.status || 400).json(err.fields ? { error: err.message, fields: err.fields } : { error: err.message });
  res.status(err.status || 500).json({ error: 'Something went wrong.' });
});

const port = process.env.PORT || 5003;
app.listen(port, () => console.log(`marbingo server on :${port}`));
require('./jobs/retention').start();
