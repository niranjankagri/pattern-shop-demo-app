package com.niranjankagri.shop;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * The application under test: a tiny shop served by the JDK's own HTTP server.
 *
 * <p>One shared application for the test automation frameworks: it runs as a Docker image next to the tests in CI
 * (fast, nothing shared between runs) and as a hosted demo. Plain JDK, no dependencies, starts in well under a second.
 * It offers the same catalog in three formats on purpose, so a framework's Adapter pattern has three different client
 * APIs to translate:
 * <ul>
 *   <li>{@code GET /api/products}: JSON (a modern REST API)</li>
 *   <li>{@code POST /soap/catalog}: a SOAP-style XML envelope</li>
 *   <li>{@code GET /legacy/catalog?q=ALL}: pipe-separated text lines, read through {@code LegacyInventorySdk}</li>
 * </ul>
 * The HTML pages use plain forms (no JavaScript), and every element a test needs has a {@code data-testid}.
 * {@code GET /api/test-data/users} is a test-data service (demo users and their state), and {@code GET /health}
 * answers {@code ok} for container and hosting health checks. Sessions and carts live in memory only.
 */
public final class ShopApp implements AutoCloseable {

    /** Password of every demo user. */
    public static final String PASSWORD = "secret_sauce";

    private static final Map<String, Product> PRODUCTS = new LinkedHashMap<>();
    private static final Map<String, String> USERS = new LinkedHashMap<>();

    static {
        add(new Product("P-100", "Backpack", 2999));
        add(new Product("P-200", "Bike Light", 999));
        add(new Product("P-300", "Bolt T-Shirt", 1599));
        add(new Product("P-400", "Fleece Jacket", 4999));
        // user name -> state: active users can log in, locked ones get a message
        USERS.put("standard_user", "active");
        USERS.put("locked_user", "locked");
        USERS.put("visual_user", "active");
    }

    private final HttpServer server;
    private final Map<String, String> sessions = new ConcurrentHashMap<>();
    private final Map<String, List<String>> carts = new ConcurrentHashMap<>();

    private ShopApp(int port) throws IOException {
        // 0.0.0.0, so a browser in a container (Selenium Grid) can reach it too
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/", this::route);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
    }

    /** Most sessions kept at once; a hosted demo must not grow without limit. */
    static final int MAX_SESSIONS = 10_000;

    /**
     * Runs the shop until the process stops. The port comes from the {@code PORT} environment variable (set by
     * hosting platforms such as Render), else 8080.
     */
    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        ShopApp app = start(port);
        Runtime.getRuntime().addShutdownHook(new Thread(app::close));
        System.out.println("Pattern Shop listening on port " + app.port());
    }

    /** Starts a shop on this port (0 = any free port); close it when done. */
    public static ShopApp start(int port) {
        try {
            return new ShopApp(port);
        } catch (IOException e) {
            throw new IllegalStateException("The shop could not start on port " + port, e);
        }
    }

    /** The port the shop listens on. */
    public int port() {
        return server.getAddress().getPort();
    }

    /** Base URL for a client on this machine, or in a container that shares the host network. */
    public URI baseUrl(String host) {
        return URI.create("http://" + host + ":" + port());
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static void add(Product p) {
        PRODUCTS.put(p.id(), p);
    }

    // ---------------------------------------------------------------- routing

    private void route(HttpExchange ex) throws IOException {
        try (ex) {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            switch (method + " " + path) {
                case "GET /" -> redirect(ex, "/login");
                case "GET /login" -> loginPage(ex);
                case "POST /login" -> login(ex);
                case "GET /products" -> withUser(ex, user -> productsPage(ex, user));
                case "POST /cart/add" -> withUser(ex, user -> changeCart(ex, true));
                case "POST /cart/remove" -> withUser(ex, user -> changeCart(ex, false));
                case "GET /cart" -> withUser(ex, user -> cartPage(ex));
                case "POST /checkout" -> withUser(ex, user -> checkout(ex));
                case "GET /complete" -> withUser(ex, user -> completePage(ex));
                case "POST /logout" -> logout(ex);
                case "GET /health" -> send(ex, 200, "text/plain", "ok");
                case "GET /api/products" -> send(ex, 200, "application/json", productsJson());
                case "GET /api/test-data/users" -> send(ex, 200, "application/json", usersJson());
                case "POST /soap/catalog" -> soapCatalog(ex);
                case "GET /legacy/catalog" -> legacyCatalog(ex);
                default -> send(ex, 404, "text/plain", "Not found: " + path);
            }
        } catch (RuntimeException e) {
            send(ex, 500, "text/plain", "Server error: " + e.getMessage());
        }
    }

    private interface UserAction {
        void run(String user) throws IOException;
    }

    /** Runs the action for a logged-in user, or sends the browser to the login page. */
    private void withUser(HttpExchange ex, UserAction action) throws IOException {
        String user = sessions.get(sessionId(ex));
        if (user == null) {
            redirect(ex, "/login");
        } else {
            action.run(user);
        }
    }

    // ---------------------------------------------------------------- pages

    private void loginPage(HttpExchange ex) throws IOException {
        String error = query(ex).getOrDefault("error", "");
        String message = switch (error) {
            case "locked" -> "This user has been locked out.";
            case "invalid" -> "User name and password do not match any user.";
            case "required" -> "User name and password are required.";
            default -> "";
        };
        String errorHtml = message.isEmpty() ? "" : "<p class=\"error\" data-testid=\"login-error\">" + message + "</p>";
        html(ex, "Login", """
                <h1 data-testid="login-title">Sign in</h1>
                %s
                <form method="post" action="/login">
                  <label>User name <input name="username" data-testid="username" autocomplete="off"></label>
                  <label>Password <input name="password" type="password" data-testid="password"></label>
                  <button type="submit" data-testid="login-button">Sign in</button>
                </form>""".formatted(errorHtml));
    }

    private void login(HttpExchange ex) throws IOException {
        Map<String, String> form = form(ex);
        String user = form.getOrDefault("username", "");
        String password = form.getOrDefault("password", "");
        if (user.isBlank() || password.isBlank()) {
            redirect(ex, "/login?error=required");
        } else if (!USERS.containsKey(user) || !PASSWORD.equals(password)) {
            redirect(ex, "/login?error=invalid");
        } else if ("locked".equals(USERS.get(user))) {
            redirect(ex, "/login?error=locked");
        } else {
            String id = UUID.randomUUID().toString();
            if (sessions.size() >= MAX_SESSIONS) {
                // Simple bound for the hosted demo: start over rather than run out of memory
                sessions.clear();
                carts.clear();
            }
            sessions.put(id, user);
            carts.put(id, new ArrayList<>());
            ex.getResponseHeaders().add("Set-Cookie", "SESSION=" + id + "; Path=/; HttpOnly");
            redirect(ex, "/products");
        }
    }

    private void productsPage(HttpExchange ex, String user) throws IOException {
        StringBuilder cards = new StringBuilder();
        for (Product p : PRODUCTS.values()) {
            cards.append("""
                    <li data-testid="product-card">
                      <span data-testid="product-name">%s</span>
                      <span data-testid="product-price">%s</span>
                      <form method="post" action="/cart/add"><input type="hidden" name="id" value="%s">
                        <button type="submit" data-testid="add-%s">Add to cart</button></form>
                    </li>""".formatted(p.name(), p.price(), p.id(), p.id()));
        }
        html(ex, "Products", """
                <header><span data-testid="welcome">Signed in as %s</span>
                  <a href="/cart" data-testid="cart-link">Cart (<span data-testid="cart-count">%d</span>)</a>
                  <form method="post" action="/logout"><button type="submit" data-testid="logout">Sign out</button></form></header>
                <h1 data-testid="products-title">Products</h1>
                <ul>%s</ul>""".formatted(user, cart(ex).size(), cards));
    }

    private void changeCart(HttpExchange ex, boolean add) throws IOException {
        String id = form(ex).getOrDefault("id", "");
        if (!PRODUCTS.containsKey(id)) {
            send(ex, 400, "text/plain", "Unknown product: " + id);
            return;
        }
        List<String> cart = cart(ex);
        if (add) {
            cart.add(id);
            redirect(ex, "/products");
        } else {
            cart.remove(id);
            redirect(ex, "/cart");
        }
    }

    private void cartPage(HttpExchange ex) throws IOException {
        StringBuilder items = new StringBuilder();
        int total = 0;
        for (String id : cart(ex)) {
            Product p = PRODUCTS.get(id);
            total += p.priceCents();
            items.append("""
                    <li data-testid="cart-item"><span data-testid="cart-item-name">%s</span> %s
                      <form method="post" action="/cart/remove"><input type="hidden" name="id" value="%s">
                        <button type="submit" data-testid="remove-%s">Remove</button></form></li>""".formatted(
                    p.name(), p.price(), p.id(), p.id()));
        }
        html(ex, "Cart", """
                <h1 data-testid="cart-title">Your cart</h1>
                <ul>%s</ul>
                <p>Total: <span data-testid="cart-total">%s</span></p>
                <a href="/products" data-testid="continue-shopping">Continue shopping</a>
                <form method="post" action="/checkout"><button type="submit" data-testid="checkout">Checkout</button></form>"""
                .formatted(items, Product.format(total)));
    }

    private void checkout(HttpExchange ex) throws IOException {
        List<String> cart = cart(ex);
        if (cart.isEmpty()) {
            redirect(ex, "/cart");
            return;
        }
        String order = "ORD-" + (1000 + Math.abs(sessionId(ex).hashCode() % 9000));
        cart.clear();
        redirect(ex, "/complete?order=" + order);
    }

    private void completePage(HttpExchange ex) throws IOException {
        String order = query(ex).getOrDefault("order", "");
        html(ex, "Order complete", """
                <h1 data-testid="complete-title">Thank you for your order</h1>
                <p data-testid="order-number">%s</p>
                <a href="/products" data-testid="back-to-products">Back to products</a>""".formatted(order));
    }

    private void logout(HttpExchange ex) throws IOException {
        String id = sessionId(ex);
        sessions.remove(id);
        carts.remove(id);
        ex.getResponseHeaders().add("Set-Cookie", "SESSION=; Path=/; Max-Age=0");
        redirect(ex, "/login");
    }

    // ---------------------------------------------------------------- APIs

    private static String productsJson() {
        List<String> items = new ArrayList<>();
        for (Product p : PRODUCTS.values()) {
            items.add("{\"id\":\"%s\",\"name\":\"%s\",\"priceCents\":%d}".formatted(p.id(), p.name(), p.priceCents()));
        }
        return "[" + String.join(",", items) + "]";
    }

    private static String usersJson() {
        List<String> items = new ArrayList<>();
        for (Map.Entry<String, String> u : USERS.entrySet()) {
            items.add("{\"username\":\"%s\",\"password\":\"%s\",\"state\":\"%s\"}".formatted(u.getKey(), PASSWORD, u.getValue()));
        }
        return "[" + String.join(",", items) + "]";
    }

    /** SOAP-style catalog: the request body must contain a GetProducts element. */
    private void soapCatalog(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (!body.contains("GetProducts")) {
            send(ex, 400, "text/xml", envelope("<soap:Fault><faultstring>Unknown operation</faultstring></soap:Fault>"));
            return;
        }
        StringBuilder products = new StringBuilder();
        for (Product p : PRODUCTS.values()) {
            products.append("<product><code>%s</code><title>%s</title><price currency=\"USD\">%s</price></product>"
                    .formatted(p.id(), p.name(), Product.decimal(p.priceCents())));
        }
        send(ex, 200, "text/xml", envelope("<GetProductsResponse><products>" + products + "</products></GetProductsResponse>"));
    }

    private static String envelope(String body) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>"
                + body + "</soap:Body></soap:Envelope>";
    }

    /** Legacy text format: one product per line, CODE|NAME|PRICE with the price in dollars. */
    private void legacyCatalog(HttpExchange ex) throws IOException {
        if (!"ALL".equals(query(ex).get("q"))) {
            send(ex, 400, "text/plain", "ERR|QUERY");
            return;
        }
        StringBuilder lines = new StringBuilder();
        for (Product p : PRODUCTS.values()) {
            lines.append(p.id()).append('|').append(p.name().toUpperCase()).append('|')
                    .append(Product.decimal(p.priceCents())).append('\n');
        }
        send(ex, 200, "text/plain", lines.toString());
    }

    // ---------------------------------------------------------------- helpers

    private List<String> cart(HttpExchange ex) {
        return carts.computeIfAbsent(sessionId(ex), k -> new ArrayList<>());
    }

    private static String sessionId(HttpExchange ex) {
        List<String> cookies = ex.getRequestHeaders().getOrDefault("Cookie", List.of());
        for (String header : cookies) {
            for (String c : header.split(";")) {
                String[] kv = c.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equals("SESSION")) {
                    return kv[1];
                }
            }
        }
        return "";
    }

    private static Map<String, String> query(HttpExchange ex) {
        return parse(ex.getRequestURI().getRawQuery());
    }

    private static Map<String, String> form(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static Map<String, String> parse(String encoded) {
        Map<String, String> values = new HashMap<>();
        if (encoded == null || encoded.isEmpty()) {
            return values;
        }
        for (String pair : encoded.split("&")) {
            String[] kv = pair.split("=", 2);
            values.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return values;
    }

    private static void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().add("Location", location);
        ex.sendResponseHeaders(303, -1);
    }

    private static void html(HttpExchange ex, String title, String body) throws IOException {
        send(ex, 200, "text/html", """
                <!doctype html><html lang="en"><head><meta charset="utf-8"><title>%s | Pattern Shop</title>
                <meta name="viewport" content="width=device-width, initial-scale=1"><style>%s</style>
                </head><body><div class="brand">Pattern Shop <small>demo for test automation</small></div><main>%s</main></body></html>"""
                .formatted(title, CSS, body));
    }

    /** One small stylesheet for every page (no JavaScript anywhere: forms and links only). */
    private static final String CSS = """
            :root{--ink:#1d2433;--muted:#5b6475;--line:#dfe3ea;--accent:#2f5bea;--bg:#f5f7fb;--error:#b42318}
            *{box-sizing:border-box}body{margin:0;font:16px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;
            color:var(--ink);background:var(--bg)}.brand{background:var(--ink);color:#fff;padding:.8rem 1.25rem;
            font-weight:700}.brand small{font-weight:400;opacity:.7;margin-left:.5rem}main{max-width:46rem;
            margin:1.5rem auto;padding:0 1rem}h1{margin:.5rem 0 1rem;font-size:1.6rem}header{display:flex;gap:1rem;
            align-items:center;justify-content:space-between;flex-wrap:wrap;color:var(--muted)}header form{margin:0}
            ul{list-style:none;padding:0;display:grid;gap:.75rem}li{background:#fff;border:1px solid var(--line);
            border-radius:10px;padding:.8rem 1rem;display:flex;align-items:center;gap:1rem}li form{margin:0 0 0 auto}
            [data-testid=product-name],[data-testid=cart-item-name]{font-weight:600}
            [data-testid=product-price]{color:var(--muted)}label{display:block;margin:.75rem 0;font-weight:600}
            input{display:block;width:100%;max-width:22rem;margin-top:.25rem;padding:.55rem .7rem;font:inherit;
            border:1px solid var(--line);border-radius:8px;background:#fff}button{font:inherit;cursor:pointer;
            padding:.45rem .9rem;border-radius:8px;border:1px solid var(--accent);background:var(--accent);color:#fff}
            header button,li button{background:#fff;color:var(--accent)}a{color:var(--accent)}
            .error{color:var(--error);background:#fdecea;border:1px solid #f5c2bd;border-radius:8px;padding:.6rem .8rem}
            """;

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** A product of the shop's own catalog (the app side; tests have their own model). */
    private record Product(String id, String name, int priceCents) {
        String price() {
            return format(priceCents);
        }

        static String format(int cents) {
            return "$" + decimal(cents);
        }

        static String decimal(int cents) {
            return "%d.%02d".formatted(cents / 100, cents % 100);
        }
    }
}
