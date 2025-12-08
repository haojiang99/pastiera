#!/bin/bash

# CoolwulfIME Landing Page Deployment Script
# For Ubuntu Server - http://coolwulfime.org:80

set -e

DOMAIN="coolwulfime.org"
WEB_ROOT="/var/www/$DOMAIN"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIST_DIR="$SCRIPT_DIR/dist"

echo "=== CoolwulfIME Landing Page Deployment ==="

# Check if running as root
if [ "$EUID" -ne 0 ]; then
    echo "Please run as root (sudo ./run.sh)"
    exit 1
fi

# Check if dist folder exists
if [ ! -d "$DIST_DIR" ]; then
    echo "Error: dist folder not found. Run 'npm run build' first."
    exit 1
fi

# Install nginx if not installed
if ! command -v nginx &> /dev/null; then
    echo "Installing nginx..."
    apt update
    apt install -y nginx
fi

# Create web root directory
echo "Creating web root: $WEB_ROOT"
mkdir -p "$WEB_ROOT"

# Copy dist files to web root
echo "Copying files to $WEB_ROOT..."
cp -r "$DIST_DIR"/* "$WEB_ROOT/"

# Set permissions
chown -R www-data:www-data "$WEB_ROOT"
chmod -R 755 "$WEB_ROOT"

# Create nginx config
echo "Configuring nginx..."
cat > /etc/nginx/sites-available/$DOMAIN << 'EOF'
server {
    listen 80;
    listen [::]:80;
    server_name coolwulfime.org www.coolwulfime.org;

    root /var/www/coolwulfime.org;
    index index.html;

    # Gzip compression
    gzip on;
    gzip_types text/plain text/css application/json application/javascript text/xml application/xml;

    # Cache static assets
    location /assets/ {
        expires 1y;
        add_header Cache-Control "public, immutable";
    }

    # APK download
    location ~* \.apk$ {
        add_header Content-Disposition "attachment";
        add_header Content-Type "application/vnd.android.package-archive";
    }

    # ZIP download (voice model)
    location ~* \.zip$ {
        add_header Content-Disposition "attachment";
        add_header Content-Type "application/zip";
    }

    # SPA fallback
    location / {
        try_files $uri $uri/ /index.html;
    }
}
EOF

# Enable site
ln -sf /etc/nginx/sites-available/$DOMAIN /etc/nginx/sites-enabled/

# Remove default site if exists
rm -f /etc/nginx/sites-enabled/default

# Test nginx config
echo "Testing nginx configuration..."
nginx -t

# Restart nginx
echo "Restarting nginx..."
systemctl restart nginx
systemctl enable nginx

echo ""
echo "=== Deployment Complete ==="
echo "Site is live at: http://$DOMAIN"
echo ""
echo "Files deployed to: $WEB_ROOT"
ls -la "$WEB_ROOT"
