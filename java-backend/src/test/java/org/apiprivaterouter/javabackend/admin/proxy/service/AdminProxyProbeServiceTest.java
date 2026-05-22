package org.apiprivaterouter.javabackend.admin.proxy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.apiprivaterouter.javabackend.admin.proxy.model.AdminProxyResponse;
import org.apiprivaterouter.javabackend.admin.proxy.model.ProxyQualityCheckResultResponse;
import org.apiprivaterouter.javabackend.admin.proxy.model.TestProxyResponse;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminProxyProbeServiceTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void testProxyReturnsExitInfoFromIpApiProbe() throws Exception {
        server = startServer();
        server.createContext("/ip-api", exchange -> {
            byte[] body = """
                    {"status":"success","query":"203.0.113.10","city":"Tokyo","regionName":"Tokyo","country":"Japan","countryCode":"JP"}
                    """.getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        AdminProxyProbeService service = service(
                List.of(new AdminProxyProbeService.ExitProbeTarget(url("/ip-api"), "ip-api")),
                List.of()
        );

        TestProxyResponse response = service.testProxy(proxy());

        assertTrue(response.success());
        assertEquals("203.0.113.10", response.ip_address());
        assertEquals("Tokyo", response.city());
        assertEquals("Japan", response.country());
        assertEquals("JP", response.country_code());
        assertNotNull(response.latency_ms());
    }

    @Test
    void testProxyFallsBackToHttpBinProbe() throws Exception {
        server = startServer();
        server.createContext("/ip-api", exchange -> {
            byte[] body = """
                    {"status":"fail","message":"reserved range"}
                    """.getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/httpbin", exchange -> {
            byte[] body = """
                    {"origin":"198.51.100.20"}
                    """.getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        AdminProxyProbeService service = service(
                List.of(
                        new AdminProxyProbeService.ExitProbeTarget(url("/ip-api"), "ip-api"),
                        new AdminProxyProbeService.ExitProbeTarget(url("/httpbin"), "httpbin")
                ),
                List.of()
        );

        TestProxyResponse response = service.testProxy(proxy());

        assertTrue(response.success());
        assertEquals("198.51.100.20", response.ip_address());
    }

    @Test
    void testProxyReturnsFailurePayloadWhenAllProbesFail() throws Exception {
        server = startServer();
        server.createContext("/ip-api", exchange -> {
            byte[] body = "nope".getBytes();
            exchange.sendResponseHeaders(502, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        AdminProxyProbeService service = service(
                List.of(new AdminProxyProbeService.ExitProbeTarget(url("/ip-api"), "ip-api")),
                List.of()
        );

        TestProxyResponse response = service.testProxy(proxy());

        assertFalse(response.success());
        assertEquals("request failed with status: 502", response.message());
    }

    @Test
    void qualityCheckScoresTargetsAndDetectsCloudflareChallenge() throws Exception {
        server = startServer();
        server.createContext("/httpbin", exchange -> {
            byte[] body = """
                    {"origin":"198.51.100.20"}
                    """.getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/openai", exchange -> {
            byte[] body = "{}".getBytes();
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/gemini", exchange -> {
            byte[] body = "{}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/anthropic", exchange -> {
            byte[] body = "<!doctype html><title>Just a moment</title><script>window._cf_chl_opt={};</script>".getBytes();
            exchange.getResponseHeaders().set("content-type", "text/html");
            exchange.getResponseHeaders().set("cf-ray", "test-ray-123");
            exchange.sendResponseHeaders(403, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        AdminProxyProbeService service = service(
                List.of(new AdminProxyProbeService.ExitProbeTarget(url("/httpbin"), "httpbin")),
                List.of(
                        new AdminProxyProbeService.QualityTarget("openai", url("/openai"), "GET", Set.of(401)),
                        new AdminProxyProbeService.QualityTarget("gemini", url("/gemini"), "GET", Set.of(200)),
                        new AdminProxyProbeService.QualityTarget("anthropic", url("/anthropic"), "GET", Set.of(401))
                )
        );

        ProxyQualityCheckResultResponse response = service.checkProxyQuality(9L, proxy());

        assertEquals(9L, response.proxy_id());
        assertEquals("198.51.100.20", response.exit_ip());
        assertEquals(2, response.passed_count());
        assertEquals(1, response.warn_count());
        assertEquals(0, response.failed_count());
        assertEquals(1, response.challenge_count());
        assertEquals(60, response.score());
        assertEquals("C", response.grade());
        assertEquals("challenge", response.items().get(3).status());
        assertEquals("test-ray-123", response.items().get(3).cf_ray());
    }

    private AdminProxyProbeService service(
            List<AdminProxyProbeService.ExitProbeTarget> exitTargets,
            List<AdminProxyProbeService.QualityTarget> qualityTargets
    ) {
        return new AdminProxyProbeService(
                new ObjectMapper(),
                (proxy, connectTimeout) -> HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(2))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                exitTargets,
                qualityTargets
        );
    }

    private HttpServer startServer() throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.start();
        return httpServer;
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private AdminProxyResponse proxy() {
        return new AdminProxyResponse(
                1L,
                "proxy",
                "http",
                "proxy.example.test",
                8080,
                null,
                null,
                "active",
                0L,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "2026-01-01T00:00:00Z",
                "2026-01-01T00:00:00Z"
        );
    }
}
