module.exports = {
  apps: [{
    name: 'marbingo',
    script: './server/src/index.js',
    cwd: '/var/www/marbingo',
    env: { NODE_ENV: 'production', PORT: 5003, TZ: 'Asia/Manila' },
    instances: 1,
    autorestart: true,
    watch: false,
    max_memory_restart: '512M',
    error_file: '/var/log/pm2/marbingo-error.log',
    out_file: '/var/log/pm2/marbingo-out.log',
    log_date_format: 'YYYY-MM-DD HH:mm:ss'
  }]
};
