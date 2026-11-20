# Pattern Shop: a demo application for test automation

[![CI](https://github.com/niranjankagri/pattern-shop-demo-app/actions/workflows/ci.yml/badge.svg)](https://github.com/niranjankagri/pattern-shop-demo-app/actions/workflows/ci.yml)

A small shop built to be tested: sign-in, product list, cart and checkout pages, and the same catalog through three
different APIs (REST/JSON, SOAP-style XML and a legacy text format). It is shared by my test automation frameworks,
so they test one application I control instead of public demo sites that can be slow or down.

**Live demo: https://pattern-shop.onrender.com** (sign in as `standard_user` / `secret_sauce`)

![Product list](docs/images/products.png)

| | |
|---|---|
| Runtime | JDK only (`com.sun.net.httpserver`), no dependencies; compiled for Java 21, runs on Java 25 in the image; starts in under a second |
| Image | `ghcr.io/niranjankagri/pattern-shop-demo-app:latest` (and `:sha-<commit>`), published by CI from `main` |
| Hosted demo | https://pattern-shop.onrender.com: Render free plan, from [`render.yaml`](render.yaml); it sleeps when idle, so the first request can take about a minute |
| Tests | 19 JUnit 6 tests over real HTTP, plus a smoke test of the built image |

## How the frameworks use it

```text
framework CI job ──▶ docker run ghcr.io/.../pattern-shop-demo-app ──▶ tests against http://localhost:8080
                     (fresh shop per job: fast, no shared data, never down)

people, demos     ──▶ hosted demo on Render (https://pattern-shop.onrender.com)
```

Framework CI never depends on the hosted demo. Used by:
[design-patterns-test-framework](https://github.com/niranjankagri/design-patterns-test-framework) (Selenium and
Playwright for Java).

## Run it

```bash
docker run -d -p 8080:8080 ghcr.io/niranjankagri/pattern-shop-demo-app:latest
# or from source:
./mvnw package && java -jar target/pattern-shop.jar          # port 8080, or $PORT
```

Open http://localhost:8080 and sign in as `standard_user` / `secret_sauce`.

## What it offers

**Users** (password `secret_sauce` for all): `standard_user` and `visual_user` sign in; `locked_user` gets
"This user has been locked out."; a wrong password or unknown user gets "User name and password do not match any
user."; empty fields get "User name and password are required."

**Pages** (plain HTML forms, no JavaScript; every element a test needs has a `data-testid`):

| Path | Content | Test ids |
|---|---|---|
| `/login` | sign-in form, error message | `username`, `password`, `login-button`, `login-error` |
| `/products` | catalog, cart counter, sign-out | `product-card`, `product-name`, `product-price`, `add-<id>`, `cart-count`, `cart-link`, `logout`, `welcome` |
| `/cart` | items, total, remove, checkout | `cart-item`, `cart-item-name`, `cart-total`, `remove-<id>`, `checkout`, `continue-shopping` |
| `/complete` | order confirmation | `complete-title`, `order-number`, `back-to-products` |

**APIs** (the same four products in three formats, for adapter and contract tests):

| Endpoint | Format | Example |
|---|---|---|
| `GET /api/products` | JSON, prices in cents | `{"id":"P-200","name":"Bike Light","priceCents":999}` |
| `POST /soap/catalog` with `<GetProducts/>` | SOAP-style XML envelope, prices in dollars | `<product><code>P-200</code><title>Bike Light</title><price currency="USD">9.99</price></product>` |
| `GET /legacy/catalog?q=ALL` | text lines `CODE\|NAME\|PRICE`, upper-case names | `P-200\|BIKE LIGHT\|9.99`; errors as `ERR\|QUERY` |
| `GET /api/test-data/users` | JSON: the demo users and their state | for a test-data service strategy |
| `GET /health` | `ok` | container and hosting health checks |

Sessions and carts are kept in memory per browser session (cookie `SESSION`), so parallel tests do not share a cart.
The hosted demo keeps at most 10,000 sessions and then starts over.

## CI

[`ci.yml`](.github/workflows/ci.yml) on every pull request, push to `main`, weekly (Friday 23:00 UTC) and on demand:

1. **App tests**: `./mvnw verify` (19 tests over HTTP).
2. **Image build and smoke test**: builds the Docker image, waits for its health check, runs
   [`scripts/smoke.sh`](scripts/smoke.sh) (sign-in, cart, all three APIs). On `main` the tested image is published to
   ghcr.io as `:latest` and `:sha-<commit>`.

`scripts/smoke.sh <url>` also checks the hosted demo (`bash scripts/smoke.sh https://pattern-shop.onrender.com`, passed on
20 Nov 2026); it waits up to 90 s for a sleeping free instance.

## License

[MIT](LICENSE)
