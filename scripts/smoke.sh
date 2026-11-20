#!/usr/bin/env bash
# Smoke test of a running Pattern Shop: health, login, cart and the three catalog APIs.
# Usage: scripts/smoke.sh http://localhost:8080   (waits up to SMOKE_WAIT_SECONDS for /health, default 90:
#        the hosted demo on Render's free plan may need about a minute to wake up)
set -euo pipefail
base="${1:?base URL, for example http://localhost:8080}"
wait="${SMOKE_WAIT_SECONDS:-90}"
jar="$(mktemp)"
trap 'rm -f "$jar"' EXIT

fail() { echo "FAIL  $1"; exit 1; }
ok() { echo "ok    $1"; }

for ((i = 0; i < wait; i += 3)); do
  curl -fsS -m 10 "$base/health" >/dev/null 2>&1 && break
  sleep 3
done
[ "$(curl -fsS -m 10 "$base/health")" = "ok" ] || fail "health"
ok "health"

loc=$(curl -sS -m 10 -c "$jar" -o /dev/null -w '%{redirect_url}' -d 'username=standard_user&password=secret_sauce' "$base/login")
[[ "$loc" == */products ]] || fail "login redirects to /products (got $loc)"
ok "login"

curl -sS -m 10 -b "$jar" -o /dev/null -d 'id=P-100' "$base/cart/add"
curl -sS -m 10 -b "$jar" "$base/cart" | grep -q 'data-testid="cart-total">\$29.99<' || fail "cart total"
ok "cart"

curl -fsS -m 10 "$base/api/products" | grep -q '"id":"P-400"' || fail "REST catalog"
ok "REST catalog"
curl -fsS -m 10 -H 'Content-Type: text/xml' -d '<Envelope><Body><GetProducts/></Body></Envelope>' "$base/soap/catalog" \
  | grep -q '<code>P-200</code>' || fail "SOAP catalog"
ok "SOAP catalog"
curl -fsS -m 10 "$base/legacy/catalog?q=ALL" | grep -q '^P-300|BOLT T-SHIRT|15.99$' || fail "legacy catalog"
ok "legacy catalog"
echo "Smoke test passed: $base"
