package com.niranjankagri.shop;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the shop over real HTTP, the way the frameworks use it: pages (forms, redirects, cookies) and the three
 * catalog APIs. One shop on a free port for the whole class; each test gets its own client, so its own session.
 */
class ShopAppTest {

    private static ShopApp app;
    private static URI base;

    /** A browser-like client: keeps cookies, does not follow redirects (so the tests can check them). */
    private HttpClient client;

    @BeforeAll
    static void startShop() {
        app = ShopApp.start(0);
        base = app.baseUrl("localhost");
    }

    @AfterAll
    static void stopShop() {
        app.close();
    }

    @BeforeEach
    void newClient() {
        client = HttpClient.newBuilder().cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Test
    @DisplayName("APP-001 health check answers ok")
    void health() throws Exception {
        HttpResponse<String> r = get("/health");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("ok");
    }

    @Test
    @DisplayName("APP-002 unknown paths answer 404")
    void unknownPath() throws Exception {
        assertThat(get("/nope").statusCode()).isEqualTo(404);
    }

    @Nested
    @DisplayName("Login")
    class Login {

        @Test
        @DisplayName("APP-101 an active user is signed in and sent to the products")
        void activeUser() throws Exception {
            HttpResponse<String> r = login("standard_user", ShopApp.PASSWORD);
            assertThat(r.statusCode()).isEqualTo(303);
            assertThat(r.headers().firstValue("Location")).hasValue("/products");
            assertThat(get("/products").body()).contains("Signed in as standard_user");
        }

        @Test
        @DisplayName("APP-102 a locked user gets the locked message")
        void lockedUser() throws Exception {
            assertThat(login("locked_user", ShopApp.PASSWORD).headers().firstValue("Location"))
                    .hasValue("/login?error=locked");
            assertThat(get("/login?error=locked").body()).contains("This user has been locked out.");
        }

        @Test
        @DisplayName("APP-103 a wrong password or unknown user gets the same message")
        void wrongPassword() throws Exception {
            assertThat(login("standard_user", "wrong").headers().firstValue("Location")).hasValue("/login?error=invalid");
            assertThat(login("nobody", ShopApp.PASSWORD).headers().firstValue("Location")).hasValue("/login?error=invalid");
        }

        @Test
        @DisplayName("APP-108 the form fields have an id, name, placeholder and test id")
        void fieldAttributes() throws Exception {
            assertThat(get("/login").body()).contains(
                    "<input id=\"username\" name=\"username\" placeholder=\"User name\" data-testid=\"username\"",
                    "<input id=\"password\" name=\"password\" type=\"password\" placeholder=\"Password\" data-testid=\"password\"");
        }

        @Test
        @DisplayName("APP-104 empty fields are required")
        void emptyFields() throws Exception {
            assertThat(login("", "").headers().firstValue("Location")).hasValue("/login?error=required");
        }

        @Test
        @DisplayName("APP-105 pages behind the login send a visitor to the login page, with a message")
        void needsLogin() throws Exception {
            for (String path : new String[] {"/products", "/product?id=P-100", "/cart", "/checkout/info", "/checkout/overview"}) {
                HttpResponse<String> r = get(path);
                assertThat(r.statusCode()).as(path).isEqualTo(303);
                assertThat(r.headers().firstValue("Location")).as(path).hasValue("/login?error=signin");
            }
            assertThat(get("/login?error=signin").body()).contains("Please sign in to see that page.");
        }

        @Test
        @DisplayName("APP-106 signing out ends the session")
        void logout() throws Exception {
            login("standard_user", ShopApp.PASSWORD);
            post("/logout", "");
            assertThat(get("/products").headers().firstValue("Location")).hasValue("/login?error=signin");
        }

        @Test
        @DisplayName("APP-107 slow_user is signed in, but only after the delay")
        void slowUser() throws Exception {
            long start = System.nanoTime();
            HttpResponse<String> r = login("slow_user", ShopApp.PASSWORD);
            long millis = (System.nanoTime() - start) / 1_000_000;
            assertThat(r.headers().firstValue("Location")).hasValue("/products");
            assertThat(millis).isGreaterThanOrEqualTo(ShopApp.SLOW_LOGIN_MILLIS - 50);
        }
    }

    @Nested
    @DisplayName("Products")
    class Products {

        @BeforeEach
        void signIn() throws Exception {
            login("standard_user", ShopApp.PASSWORD);
        }

        @Test
        @DisplayName("APP-401 the list is sorted by name by default, and by the chosen order")
        void sort() throws Exception {
            assertThat(names(get("/products").body())).containsExactly("Backpack", "Bike Light", "Bolt T-Shirt", "Fleece Jacket");
            assertThat(names(get("/products?sort=name-desc").body()))
                    .containsExactly("Fleece Jacket", "Bolt T-Shirt", "Bike Light", "Backpack");
            assertThat(names(get("/products?sort=price-asc").body()))
                    .containsExactly("Bike Light", "Bolt T-Shirt", "Backpack", "Fleece Jacket");
            assertThat(names(get("/products?sort=price-desc").body()))
                    .containsExactly("Fleece Jacket", "Backpack", "Bolt T-Shirt", "Bike Light");
            assertThat(get("/products?sort=price-desc").body()).contains("<option value=\"price-desc\" selected>");
        }

        @Test
        @DisplayName("APP-402 an unknown sort order falls back to name A to Z")
        void unknownSort() throws Exception {
            assertThat(names(get("/products?sort=nope").body())).startsWith("Backpack");
        }

        @Test
        @DisplayName("APP-403 every product has its own page with description and price")
        void productPage() throws Exception {
            HttpResponse<String> r = get("/product?id=P-200");
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(r.body()).contains("<h1 data-testid=\"product-detail-name\">Bike Light</h1>",
                    "data-testid=\"product-detail-description\">A rechargeable front light",
                    "<p data-testid=\"product-detail-price\">$9.99</p>", "data-testid=\"add-P-200\"",
                    "src=\"/img/P-200.svg\"");
            assertThat(get("/products").body()).contains("<a href=\"/product?id=P-200\" data-testid=\"product-name\">");
        }

        @Test
        @DisplayName("APP-404 an unknown product page answers 404")
        void unknownProductPage() throws Exception {
            assertThat(get("/product?id=P-999").statusCode()).isEqualTo(404);
        }

        @Test
        @DisplayName("APP-405 adding from a product page or a sorted list goes back to that page")
        void addGoesBack() throws Exception {
            assertThat(post("/cart/add", "id=P-200&back=%2Fproduct%3Fid%3DP-200").headers().firstValue("Location"))
                    .hasValue("/product?id=P-200");
            assertThat(post("/cart/add", "id=P-200&back=%2Fproducts%3Fsort%3Dprice-asc").headers().firstValue("Location"))
                    .hasValue("/products?sort=price-asc");
            // never back to another site
            assertThat(post("/cart/add", "id=P-200&back=https%3A%2F%2Fexample.com").headers().firstValue("Location"))
                    .hasValue("/products");
        }

        @Test
        @DisplayName("APP-406 each product has its own picture; problem_user gets the placeholder everywhere")
        void images() throws Exception {
            assertThat(get("/products").body()).contains("src=\"/img/P-100.svg\"", "src=\"/img/P-400.svg\"")
                    .doesNotContain("placeholder");
            HttpResponse<String> svg = get("/img/P-100.svg");
            assertThat(svg.statusCode()).isEqualTo(200);
            assertThat(svg.headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t).startsWith("image/svg+xml"));
            assertThat(svg.body()).startsWith("<svg").contains(">B</text>");
            assertThat(get("/img/placeholder.svg").statusCode()).isEqualTo(200);
            assertThat(get("/img/P-999.svg").statusCode()).isEqualTo(404);

            newClient();
            login("problem_user", ShopApp.PASSWORD);
            String body = get("/products").body();
            assertThat(body.split("src=\"/img/placeholder.svg\"", -1)).hasSize(5);
            assertThat(body).doesNotContain("/img/P-");
        }

        /** Product names of a list page, in page order. */
        private java.util.List<String> names(String html) {
            return java.util.regex.Pattern.compile("data-testid=\"product-name\">([^<]+)<").matcher(html).results()
                    .map(m -> m.group(1)).toList();
        }
    }

    @Nested
    @DisplayName("Cart and checkout")
    class Cart {

        @BeforeEach
        void signIn() throws Exception {
            login("standard_user", ShopApp.PASSWORD);
        }

        @Test
        @DisplayName("APP-201 adding products counts them and totals the cart")
        void addAndTotal() throws Exception {
            post("/cart/add", "id=P-100");
            post("/cart/add", "id=P-200");
            assertThat(get("/products").body()).contains("<span data-testid=\"cart-count\">2</span>");
            assertThat(get("/cart").body()).contains("Backpack", "Bike Light", "<span data-testid=\"cart-total\">$39.98</span>");
        }

        @Test
        @DisplayName("APP-202 removing a product takes it out of the total")
        void remove() throws Exception {
            post("/cart/add", "id=P-100");
            post("/cart/add", "id=P-300");
            post("/cart/remove", "id=P-100");
            assertThat(get("/cart").body()).doesNotContain("Backpack").contains("$15.99");
        }

        @Test
        @DisplayName("APP-203 an unknown product is refused")
        void unknownProduct() throws Exception {
            assertThat(post("/cart/add", "id=P-999").statusCode()).isEqualTo(400);
        }

        @Test
        @DisplayName("APP-204 checkout asks for the buyer, shows the overview and confirms an order number")
        void checkout() throws Exception {
            post("/cart/add", "id=P-400");
            assertThat(post("/checkout", "").headers().firstValue("Location")).hasValue("/checkout/info");
            assertThat(get("/checkout/info").body()).contains("data-testid=\"first-name\"", "data-testid=\"postal-code\"");
            assertThat(post("/checkout/info", "firstName=Ada&lastName=Lovelace&postalCode=10115").headers()
                    .firstValue("Location")).hasValue("/checkout/overview");
            assertThat(get("/checkout/overview").body()).contains("Fleece Jacket", "Ship to: Ada Lovelace, 10115");
            String location = post("/checkout/finish", "").headers().firstValue("Location").orElseThrow();
            assertThat(location).matches("/complete\\?order=ORD-\\d{4}");
            assertThat(get(location).body()).contains("Thank you for your order");
            assertThat(get("/products").body()).contains("<span data-testid=\"cart-count\">0</span>");
        }

        @Test
        @DisplayName("APP-205 checkout with an empty cart goes back to the cart")
        void emptyCheckout() throws Exception {
            assertThat(post("/checkout", "").headers().firstValue("Location")).hasValue("/cart");
            assertThat(get("/checkout/info").headers().firstValue("Location")).hasValue("/cart");
            assertThat(post("/checkout/finish", "").headers().firstValue("Location")).hasValue("/cart");
        }

        @Test
        @DisplayName("APP-207 the buyer fields are required, in order, and what was typed is shown again")
        void buyerRequired() throws Exception {
            post("/cart/add", "id=P-100");
            assertThat(post("/checkout/info", "firstName=&lastName=&postalCode=").headers().firstValue("Location"))
                    .hasValue("/checkout/info?error=first-name");
            assertThat(get("/checkout/info?error=first-name").body()).contains("First name is required.");
            assertThat(post("/checkout/info", "firstName=Ada&lastName=+&postalCode=").headers().firstValue("Location"))
                    .hasValue("/checkout/info?error=last-name");
            assertThat(get("/checkout/info?error=last-name").body()).contains("Last name is required.", "value=\"Ada\"");
            assertThat(post("/checkout/info", "firstName=Ada&lastName=Lovelace&postalCode=").headers().firstValue("Location"))
                    .hasValue("/checkout/info?error=postal-code");
            assertThat(get("/checkout/info?error=postal-code").body()).contains("Postal code is required.");
        }

        @Test
        @DisplayName("APP-208 the overview shows item total, 8 % tax rounded to the cent, and the total")
        void overviewTotals() throws Exception {
            post("/cart/add", "id=P-100");
            post("/cart/add", "id=P-200");
            post("/checkout/info", "firstName=Ada&lastName=Lovelace&postalCode=10115");
            // 39.98 * 0.08 = 3.1984 -> 3.20
            assertThat(get("/checkout/overview").body()).contains("<span data-testid=\"subtotal\">$39.98</span>",
                    "<span data-testid=\"tax\">$3.20</span>", "<span data-testid=\"total\">$43.18</span>");
            assertThat(ShopApp.tax(999)).isEqualTo(80);   // 79.92 -> 80
            assertThat(ShopApp.tax(1599)).isEqualTo(128); // 127.92 -> 128
            assertThat(ShopApp.tax(4999)).isEqualTo(400); // 399.92 -> 400
            assertThat(ShopApp.tax(625)).isEqualTo(50);   // 50.00
            assertThat(ShopApp.tax(631)).isEqualTo(50);   // 50.48 -> 50
            assertThat(ShopApp.tax(632)).isEqualTo(51);   // 50.56 -> 51
        }

        @Test
        @DisplayName("APP-209 the overview and finish need the buyer step first")
        void overviewNeedsBuyer() throws Exception {
            post("/cart/add", "id=P-100");
            assertThat(get("/checkout/overview").headers().firstValue("Location")).hasValue("/checkout/info");
            assertThat(post("/checkout/finish", "").headers().firstValue("Location")).hasValue("/checkout/info");
        }

        @Test
        @DisplayName("APP-210 typed text is escaped when the page shows it again")
        void escaped() throws Exception {
            post("/cart/add", "id=P-100");
            post("/checkout/info", "firstName=%3Cb%3E&lastName=%22x%22&postalCode=1");
            assertThat(get("/checkout/overview").body()).contains("Ship to: &lt;b&gt; &quot;x&quot;, 1").doesNotContain("<b>");
        }

        @Test
        @DisplayName("APP-206 two sessions have separate carts")
        void separateCarts() throws Exception {
            post("/cart/add", "id=P-100");
            HttpClient mine = client;
            newClient();
            login("visual_user", ShopApp.PASSWORD);
            assertThat(get("/products").body()).contains("<span data-testid=\"cart-count\">0</span>");
            client = mine;
            assertThat(get("/products").body()).contains("<span data-testid=\"cart-count\">1</span>");
        }
    }

    @Nested
    @DisplayName("Catalog APIs")
    class Catalog {

        @Test
        @DisplayName("APP-301 REST lists the catalog as JSON with prices in cents")
        void rest() throws Exception {
            HttpResponse<String> r = get("/api/products");
            assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t).startsWith("application/json"));
            assertThat(r.body()).startsWith("[{\"id\":\"P-100\",\"name\":\"Backpack\",\"priceCents\":2999}")
                    .contains("\"P-400\"");
        }

        @Test
        @DisplayName("APP-302 the SOAP-style service answers GetProducts in an envelope, prices in dollars")
        void soap() throws Exception {
            HttpResponse<String> r = send(HttpRequest.newBuilder(base.resolve("/soap/catalog"))
                    .header("Content-Type", "text/xml")
                    .POST(HttpRequest.BodyPublishers.ofString("<soap:Envelope><soap:Body><GetProducts/></soap:Body></soap:Envelope>")));
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(r.body()).contains("<soap:Envelope", "<code>P-200</code><title>Bike Light</title><price currency=\"USD\">9.99</price>");
        }

        @Test
        @DisplayName("APP-303 the SOAP-style service answers an unknown operation with a fault")
        void soapFault() throws Exception {
            HttpResponse<String> r = send(HttpRequest.newBuilder(base.resolve("/soap/catalog"))
                    .POST(HttpRequest.BodyPublishers.ofString("<Other/>")));
            assertThat(r.statusCode()).isEqualTo(400);
            assertThat(r.body()).contains("<soap:Fault>");
        }

        @Test
        @DisplayName("APP-304 the legacy service returns CODE|NAME|PRICE lines with upper-case names")
        void legacy() throws Exception {
            assertThat(get("/legacy/catalog?q=ALL").body().lines())
                    .containsExactly("P-100|BACKPACK|29.99", "P-200|BIKE LIGHT|9.99", "P-300|BOLT T-SHIRT|15.99",
                            "P-400|FLEECE JACKET|49.99");
            assertThat(get("/legacy/catalog?q=X").body()).isEqualTo("ERR|QUERY");
        }

        @Test
        @DisplayName("APP-305 the test-data service lists the demo users and their state")
        void users() throws Exception {
            assertThat(get("/api/test-data/users").body())
                    .contains("{\"username\":\"locked_user\",\"password\":\"secret_sauce\",\"state\":\"locked\"}",
                            "\"problem_user\"", "\"slow_user\"");
        }
    }

    // ---------------------------------------------------------------- helpers

    private HttpResponse<String> login(String user, String password) throws Exception {
        return post("/login", "username=" + user + "&password=" + password);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(base.resolve(path)).GET());
    }

    private HttpResponse<String> post(String path, String form) throws Exception {
        return send(HttpRequest.newBuilder(base.resolve(path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
