package org.apiprivaterouter.javabackend.gateway.service;

import org.junit.jupiter.api.Test;
import org.apiprivaterouter.javabackend.admin.account.model.AdminAccountResponse;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockModelResolverTest {

    private final BedrockModelResolver resolver = new BedrockModelResolver();

    @Test
    void resolvesDefaultAliasAndAdjustsRegionPrefix() {
        AdminAccountResponse account = account(Map.of("aws_region", "eu-west-1"));

        String modelId = resolver.resolveModelId(account, "claude-opus-4-6");

        assertEquals("eu.anthropic.claude-opus-4-6-v1", modelId);
    }

    @Test
    void forcesGlobalPrefixWhenConfigured() {
        AdminAccountResponse account = account(Map.of(
                "aws_region", "us-east-1",
                "aws_force_global", "true"
        ));

        String modelId = resolver.resolveModelId(account, "claude-sonnet-4-5");

        assertEquals("global.anthropic.claude-sonnet-4-5-20250929-v1:0", modelId);
    }

    @Test
    void respectsAccountModelMappingAndWildcard() {
        AdminAccountResponse account = account(Map.of(
                "aws_region", "ap-southeast-2",
                "model_mapping", Map.of("claude-sonnet-*", "us.anthropic.claude-sonnet-4-6")
        ));

        String modelId = resolver.resolveModelId(account, "claude-sonnet-4-6-thinking");

        assertEquals("au.anthropic.claude-sonnet-4-6", modelId);
    }

    @Test
    void passesThroughLikelyBedrockArnWithoutRegionRewrite() {
        AdminAccountResponse account = account(Map.of("aws_region", "eu-west-1"));
        String arn = "arn:aws:bedrock:us-east-1:123456789012:inference-profile/some-model";

        assertEquals(arn, resolver.resolveModelId(account, arn));
    }

    @Test
    void returnsNullForUnsupportedModel() {
        AdminAccountResponse account = account(Map.of("aws_region", "us-east-1"));

        assertNull(resolver.resolveModelId(account, "claude-3-unknown"));
    }

    @Test
    void buildsBedrockUrlEscapingColon() {
        String url = resolver.buildBedrockUrl("us-east-1", "us.anthropic.claude-sonnet-4-5-20250929-v1:0", false);

        assertTrue(url.contains("v1%3A0/invoke"));
    }

    private AdminAccountResponse account(Map<String, Object> credentials) {
        try {
            RecordComponent[] components = AdminAccountResponse.class.getRecordComponents();
            Class<?>[] parameterTypes = new Class<?>[components.length];
            Object[] args = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                RecordComponent component = components[i];
                parameterTypes[i] = component.getType();
                args[i] = defaultValue(component.getType());
                switch (component.getName()) {
                    case "id" -> args[i] = 1L;
                    case "name" -> args[i] = "bedrock";
                    case "platform" -> args[i] = "anthropic";
                    case "type" -> args[i] = "bedrock";
                    case "credentials" -> args[i] = credentials;
                    case "extra" -> args[i] = Map.of();
                    case "concurrency" -> args[i] = 1;
                    case "priority" -> args[i] = 1;
                    case "rate_multiplier" -> args[i] = 1.0d;
                    case "status" -> args[i] = "active";
                    case "group_ids" -> args[i] = List.<Long>of();
                    case "groups" -> args[i] = List.of();
                    default -> {
                    }
                }
            }
            Constructor<AdminAccountResponse> constructor = AdminAccountResponse.class.getDeclaredConstructor(parameterTypes);
            return constructor.newInstance(args);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0.0d;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        return '\0';
    }
}
