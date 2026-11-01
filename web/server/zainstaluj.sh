#!/usr/bin/env bash
# Instalacja / aktualizacja wersji WWW Ham Radio Toolkit na Debianie pod adresem https://toolkit.czysty.eu
# Jedno polecenie na serwerze (pobiera projekt prosto z GitHuba):
#   curl -fsSL https://raw.githubusercontent.com/SP3WRO/Ham_Radio_Toolkit/web-pwa/web/server/zainstaluj.sh | sudo bash
# Ponowne uruchomienie = aktualizacja do najnowszej wersji z GitHuba.
set -euo pipefail

DOMAIN="${DOMAIN:-toolkit.czysty.eu}"
BRANCH="${BRANCH:-web-pwa}"
REPO="https://github.com/SP3WRO/Ham_Radio_Toolkit.git"
APP_DIR="/opt/Ham_Radio_Toolkit"
PORT=8080

red() { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }

[ "$(id -u)" -eq 0 ] || { red "Uruchom przez sudo/jako root."; exit 1; }

step "1/6 Pakiety (Node.js, nginx, certbot, git)"
apt-get update -q
apt-get install -y -q nginx certbot python3-certbot-nginx curl dnsutils git
if ! command -v node >/dev/null || [ "$(node -p 'process.versions.node.split(".")[0]')" -lt 18 ]; then
  apt-get install -y -q nodejs || true
fi
NODE_MAJOR=$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null || echo 0)
if [ "$NODE_MAJOR" -lt 18 ]; then
  red "Potrzebny Node.js 18 lub nowszy (jest: $(node -v 2>/dev/null || echo brak))."
  red "Na Debianie 11 i starszym zainstaluj z NodeSource: https://github.com/nodesource/distributions"
  exit 1
fi
green "Node.js $(node -v)"

step "2/6 Sprawdzenie DNS dla $DOMAIN"
MY_IP=$(curl -4 -s --max-time 5 https://api.ipify.org || true)
DNS_IP=$(dig +short A "$DOMAIN" | tail -n1)
echo "Adres publiczny serwera: ${MY_IP:-nieznany}   |   $DOMAIN wskazuje na: ${DNS_IP:-BRAK REKORDU}"
if [ -z "$DNS_IP" ]; then
  red "Domena nie ma jeszcze rekordu A. Dodaj go u rejestratora i uruchom polecenie ponownie (zmiana DNS może potrwać do godziny)."
  exit 1
fi
[ -n "$MY_IP" ] && [ "$DNS_IP" != "$MY_IP" ] && red "Uwaga: DNS ($DNS_IP) ≠ adres serwera ($MY_IP). Za routerem z przekierowaniem portów to normalne – kontynuuję."

step "3/6 Projekt z GitHuba ($BRANCH) w $APP_DIR"
id hamradio >/dev/null 2>&1 || useradd --system --home "$APP_DIR" --shell /usr/sbin/nologin hamradio
# Repozytorium należy do użytkownika hamradio, a git działa jako root
git config --global --get-all safe.directory | grep -qx "$APP_DIR" || git config --global --add safe.directory "$APP_DIR"
if [ -d "$APP_DIR/.git" ]; then
  git -C "$APP_DIR" fetch --depth 1 origin "$BRANCH"
  git -C "$APP_DIR" checkout -q -B "$BRANCH" FETCH_HEAD
else
  # Pozostałości po wcześniejszych próbach (bez .git) odkładamy na bok
  [ -e "$APP_DIR" ] && mv "$APP_DIR" "$APP_DIR.stary-$(date +%s)"
  git clone -q --depth 1 --branch "$BRANCH" "$REPO" "$APP_DIR"
fi
chown -R hamradio:hamradio "$APP_DIR"
green "Wersja: $(git -C "$APP_DIR" log -1 --format='%h %cd' --date=short)"
( cd "$APP_DIR/web" && sudo -u hamradio node --test test/*.test.mjs >/dev/null ) && green "Testy aplikacji: OK"

step "4/6 Usługa systemd"
cp "$APP_DIR/web/server/hamradio-toolkit-web.service" /etc/systemd/system/
sed -i "s/^Environment=PORT=.*/Environment=PORT=$PORT/" /etc/systemd/system/hamradio-toolkit-web.service
systemctl daemon-reload
systemctl enable --now hamradio-toolkit-web
systemctl restart hamradio-toolkit-web
sleep 1
curl -fs "http://127.0.0.1:$PORT/api/health" >/dev/null && green "Serwer aplikacji działa na porcie $PORT" \
  || { red "Serwer nie odpowiada – zobacz: journalctl -u hamradio-toolkit-web -n 50"; exit 1; }

step "5/6 nginx dla $DOMAIN"
CONF=/etc/nginx/sites-available/hamradio-toolkit
if [ ! -f "$CONF" ]; then
  cat > "$CONF" <<EOF
server {
    listen 80;
    listen [::]:80;
    server_name $DOMAIN;

    gzip on;
    gzip_types text/css text/javascript application/javascript application/json application/manifest+json text/csv text/tab-separated-values text/plain image/svg+xml;

    location / {
        proxy_pass http://127.0.0.1:$PORT;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-For \$remote_addr;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 3600s;
    }
}
EOF
  ln -sf "$CONF" /etc/nginx/sites-enabled/hamradio-toolkit
fi
nginx -t
systemctl reload nginx

step "6/6 Certyfikat HTTPS (Let's Encrypt)"
if [ -d "/etc/letsencrypt/live/$DOMAIN" ]; then
  green "Certyfikat już jest – odnawia się sam."
else
  echo "certbot zapyta o adres e-mail (powiadomienia o wygaśnięciu) i zgodę na regulamin."
  certbot --nginx -d "$DOMAIN" --redirect </dev/tty
fi
systemctl reload nginx

echo
curl -fs "https://$DOMAIN/api/health" >/dev/null \
  && green "Gotowe: https://$DOMAIN" \
  || red "Strona nie odpowiada z zewnątrz po HTTPS – sprawdź zaporę (porty 80 i 443) i przekierowanie portów w routerze."
