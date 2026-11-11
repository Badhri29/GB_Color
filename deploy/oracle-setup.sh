#!/bin/bash
# ================================================================
# GB_Colors - Oracle Cloud (gb-colors-vm | Ubuntu E2.1.Micro 1 GB RAM)
# Sets up: 2GB Swap, OpenJDK 21 JRE, Low-Memory MySQL 8 (Auto-Schema + Workbench),
#          Nginx Reverse Proxy, HTTPS Certbot (gbcolor.duckdns.org), systemd service
# ================================================================

set -e

DOMAIN="gbcolor.duckdns.org"
APP_DIR="/opt/gb_colors"
GITHUB_PAGES_ORIGIN="https://badhri29.github.io"
DB_NAME="gb_colors"
DB_USER="gbadmin"
# Change DB password before running or pass DB_PASS="..." env var
DB_PASS="${DB_PASS:-GbColors@2026#Db}"

echo "=== 1. Adding 2 GB Swapfile (Essential for 1 GB RAM E2.1.Micro) ==="
if ! swapon --show | grep -q '/swapfile'; then
    sudo fallocate -l 2G /swapfile
    sudo chmod 600 /swapfile
    sudo mkswap /swapfile
    sudo swapon /swapfile
    echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null
fi

echo "=== 2. Installing OpenJDK 21, MySQL 8, Nginx, Certbot ==="
sudo apt-get update -y
sudo apt-get install -y openjdk-21-jre-headless mysql-server nginx certbot python3-certbot-nginx git curl

echo "=== 3. Tuning MySQL 8 for 1 GB RAM + Remote Workbench Access ==="
sudo tee /etc/mysql/mysql.conf.d/gbcolors-tuning.cnf >/dev/null <<EOF
[mysqld]
bind-address = 0.0.0.0
innodb_buffer_pool_size = 128M
innodb_log_buffer_size = 8M
max_connections = 40
performance_schema = OFF
EOF
sudo systemctl restart mysql

echo "=== 4. Creating Dedicated MySQL User & Schema Holder (Tables Auto-Created by Java JPA) ==="
sudo mysql <<SQL
CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '${DB_USER}'@'%' IDENTIFIED BY '${DB_PASS}';
CREATE USER IF NOT EXISTS '${DB_USER}'@'localhost' IDENTIFIED BY '${DB_PASS}';
GRANT ALL PRIVILEGES ON \`${DB_NAME}\`.* TO '${DB_USER}'@'%';
GRANT ALL PRIVILEGES ON \`${DB_NAME}\`.* TO '${DB_USER}'@'localhost';
FLUSH PRIVILEGES;
SQL

echo "=== 5. Opening Oracle Ubuntu Firewall Ports (80, 443, 3306) ==="
sudo iptables -I INPUT 4 -m state --state NEW -p tcp --dport 80 -j ACCEPT || true
sudo iptables -I INPUT 4 -m state --state NEW -p tcp --dport 443 -j ACCEPT || true
sudo iptables -I INPUT 4 -m state --state NEW -p tcp --dport 3306 -j ACCEPT || true
sudo netfilter-persistent save 2>/dev/null || true

echo "=== 6. Preparing ${APP_DIR} & .env ==="
sudo mkdir -p "${APP_DIR}"
sudo chown -R ubuntu:ubuntu "${APP_DIR}"

if [ ! -f "${APP_DIR}/.env" ]; then
cat > "${APP_DIR}/.env" <<ENV
PORT=7070
APP_NAME=GB_Colors
FRONTEND_ORIGIN=${GITHUB_PAGES_ORIGIN},https://${DOMAIN}
REQUIRE_MYSQL=true

ADMIN_EMAIL=gbcolor.admin@gmail.com
ADMIN_DEFAULT_PASSWORD=Admin@123
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USER=gbcolor.admin@gmail.com
SMTP_PASS=cqyqpizyebgrjyat
SMTP_FROM_NAME=GB_Colors

MYSQL_HOST=127.0.0.1
MYSQL_PORT=3306
MYSQL_USER=${DB_USER}
MYSQL_PASSWORD=${DB_PASS}
MYSQL_DATABASE=${DB_NAME}
MYSQL_SSL=false
ENV
chmod 600 "${APP_DIR}/.env"
fi

echo "=== 7. Installing systemd Service, Sudoers CI Restart & Nginx Config ==="
if [ -f "deploy/gb-colors.service" ]; then
    sudo cp deploy/gb-colors.service /etc/systemd/system/gb-colors.service
    sudo systemctl daemon-reload
    sudo systemctl enable gb-colors
fi
echo 'ubuntu ALL=(ALL) NOPASSWD: /bin/systemctl restart gb-colors, /bin/systemctl status gb-colors' | sudo tee /etc/sudoers.d/gb-colors >/dev/null
sudo chmod 440 /etc/sudoers.d/gb-colors

if [ -f "deploy/nginx-oracle.conf" ]; then
    sudo cp deploy/nginx-oracle.conf /etc/nginx/sites-available/gbcolors
    sudo ln -sf /etc/nginx/sites-available/gbcolors /etc/nginx/sites-enabled/gbcolors
    sudo rm -f /etc/nginx/sites-enabled/default
    sudo nginx -t && sudo systemctl reload nginx
fi

echo "=== 8. Enabling HTTPS SSL Certificate via Let's Encrypt ==="
sudo certbot --nginx -d "${DOMAIN}" --non-interactive --agree-tos -m gbcolor.admin@gmail.com --redirect || {
    echo "⚠️ Certbot SSL not issued yet (ensure DuckDNS points ${DOMAIN} -> 129.225.98.150 and port 80 is open in Oracle Security List)."
    echo "   After updating DuckDNS & Security List, run:"
    echo "   sudo certbot --nginx -d ${DOMAIN} --non-interactive --agree-tos -m gbcolor.admin@gmail.com --redirect"
}

echo ""
echo "✅ Oracle VM setup complete!"
