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
        @DisplayName("APP-104 empty fields are required")
        void emptyFields() throws Exception {
            assertThat(login("", "").headers().firstValue("Location")).hasValue("/login?error=required");
        }

        @Test
        @DisplayName("APP-105 pages behind the login send a visitor to the login page")
        void needsLogin() throws Exception {
            HttpResponse<String> r = get("/products");
            assertThat(r.statusCode()).isEqualTo(303);
            assertThat(r.headers().firstValue("Location")).hasValue("/login");
        }

        @Test
        @DisplayName("APP-106 signing out ends the session")
        void logout() throws Exception {
            login("standard_user", ShopApp.PASSWORD);
            post("/logout", "");
            assertThat(get("/products").headers().firstValue("Location")).hasValue("/login");
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
        @DisplayName("APP-204 checkout confirms an order number and empties the cart")
        void checkout() throws Exception {
            post("/cart/add", "id=P-400");
            String location = post("/checkout", "").headers().firstValue("Location").orElseThrow();
            assertThat(location).matches("/complete\\?order=ORD-\\d{4}");
            assertThat(get(location).body()).contains("Thank you for your order");
            assertThat(get("/products").body()).contains("<span data-testid=\"cart-count\">0</span>");
        }

        @Test
        @DisplayName("APP-205 checkout with an empty cart goes back to the cart")
        void emptyCheckout() throws Exception {
            assertThat(post("/checkout", "").headers().firstValue("Location")).hasValue("/cart");
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
                    .contains("{\"username\":\"locked_user\",\"password\":\"secret_sauce\",\"state\":\"locked\"}");
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
