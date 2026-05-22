package org.apiprivaterouter.javabackend.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockRequestBodyTransformerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BedrockRequestBodyTransformer transformer = new BedrockRequestBodyTransformer(objectMapper);

    @Test
    void injectsAndFiltersBetaTokens() {
        ObjectNode body = objectMapper.createObjectNode();
        body.putObject("thinking").put("type", "enabled");
        ArrayNode tools = body.putArray("tools");
        tools.add(objectMapper.createObjectNode()
                .put("type", "tool_search_tool_regex_20251119"));

        List<String> tokens = transformer.resolveBetaTokens(
                "advanced-tool-use-2025-11-20,foo-beta,context-1m-2025-08-07",
                body,
                "us.anthropic.claude-sonnet-4-5-20250929-v1:0"
        );

        assertEquals(List.of(
                "tool-search-tool-2025-10-19",
                "context-1m-2025-08-07",
                "interleaved-thinking-2025-05-14",
                "tool-examples-2025-10-29"
        ), tokens);
    }

    @Test
    void transformsRequestBodyForBedrock() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", "claude-sonnet-4-5");
        body.put("stream", true);
        body.putObject("output_config").put("effort", "high");
        body.set("output_format", objectMapper.createObjectNode()
                .set("schema", objectMapper.createObjectNode().put("type", "object")));

        ArrayNode system = body.putArray("system");
        system.add(objectMapper.createObjectNode()
                .put("type", "text")
                .put("text", "rules")
                .set("cache_control", objectMapper.createObjectNode()
                        .put("type", "ephemeral")
                        .put("scope", "global")
                        .put("ttl", "1h")));

        ArrayNode tools = body.putArray("tools");
        tools.add(objectMapper.createObjectNode()
                .put("name", "toolA")
                .put("type", "custom")
                .set("custom", objectMapper.createObjectNode().put("defer_loading", true)));

        ArrayNode messages = body.putArray("messages");
        messages.add(objectMapper.createObjectNode()
                .put("role", "assistant")
                .put("content", "ignore"));
        messages.add(objectMapper.createObjectNode()
                .put("role", "user")
                .put("content", "hello"));

        ObjectNode transformed = transformer.transform(body, "us.anthropic.claude-sonnet-4-5-20250929-v1:0", "context-1m-2025-08-07");

        assertEquals("bedrock-2023-05-31", transformed.path("anthropic_version").asText());
        assertFalse(transformed.has("model"));
        assertFalse(transformed.has("stream"));
        assertFalse(transformed.has("output_config"));
        assertFalse(transformed.has("output_format"));
        assertFalse(transformed.withArray("tools").get(0).has("custom"));
        assertFalse(transformed.withArray("system").get(0).path("cache_control").has("scope"));
        assertEquals("1h", transformed.withArray("system").get(0).path("cache_control").path("ttl").asText());

        ArrayNode content = (ArrayNode) transformed.withArray("messages").get(1).get("content");
        assertEquals(2, content.size());
        assertEquals("hello", content.get(0).path("text").asText());
        assertEquals("{\"type\":\"object\"}", content.get(1).path("text").asText());
    }

    @Test
    void removesUnsupportedCacheControlTtlOnOlderModels() {
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode messages = body.putArray("messages");
        ObjectNode message = objectMapper.createObjectNode().put("role", "user");
        ArrayNode content = objectMapper.createArrayNode();
        content.add(objectMapper.createObjectNode()
                .put("type", "text")
                .put("text", "hi")
                .set("cache_control", objectMapper.createObjectNode()
                        .put("type", "ephemeral")
                        .put("scope", "global")
                        .put("ttl", "5m")));
        message.set("content", content);
        messages.add(message);

        ObjectNode transformed = transformer.transform(body, "us.anthropic.claude-sonnet-3-v1:0", "");

        assertFalse(transformed.withArray("messages").get(0).path("content").get(0).path("cache_control").has("scope"));
        assertFalse(transformed.withArray("messages").get(0).path("content").get(0).path("cache_control").has("ttl"));
    }

    @Test
    void preservesArrayContentWhenInliningSchema() {
        ObjectNode body = objectMapper.createObjectNode();
        body.set("output_format", objectMapper.createObjectNode()
                .set("schema", objectMapper.createObjectNode().put("type", "array")));
        ArrayNode messages = body.putArray("messages");
        ObjectNode message = objectMapper.createObjectNode().put("role", "user");
        ArrayNode content = objectMapper.createArrayNode();
        content.add(objectMapper.createObjectNode().put("type", "text").put("text", "before"));
        message.set("content", content);
        messages.add(message);

        ObjectNode transformed = transformer.transform(body, "us.anthropic.claude-opus-4-6-v1", "");

        ArrayNode transformedContent = (ArrayNode) transformed.withArray("messages").get(0).get("content");
        assertEquals(2, transformedContent.size());
        assertEquals("before", transformedContent.get(0).path("text").asText());
        assertEquals("{\"type\":\"array\"}", transformedContent.get(1).path("text").asText());
    }

    @Test
    void parsesJsonArrayHeader() {
        List<String> tokens = transformer.parseAnthropicBetaHeader("[\"context-management-2025-06-27\", \"compact-2026-01-12\"]");

        assertEquals(List.of("context-management-2025-06-27", "compact-2026-01-12"), tokens);
    }

    @Test
    void haikuDoesNotAutoInjectToolSearchBeta() {
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode tools = body.putArray("tools");
        tools.add(objectMapper.createObjectNode().put("type", "tool_search_tool_bm25_20251119"));

        List<String> tokens = transformer.resolveBetaTokens("", body, "us.anthropic.claude-haiku-4-5-20251001-v1:0");

        assertTrue(tokens.isEmpty());
    }
}
