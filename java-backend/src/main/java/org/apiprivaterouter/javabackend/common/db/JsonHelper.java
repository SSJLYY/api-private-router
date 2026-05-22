package org.apiprivaterouter.javabackend.common.db;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
public class JsonHelper {

    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> OBJECT_LIST = new TypeReference<>() {
    };
    private final ObjectMapper objectMapper;

    public JsonHelper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> readObjectMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(raw, OBJECT_MAP);
        } catch (JsonProcessingException ex) {
            return Collections.emptyMap();
        }
    }

    public List<String> readStringList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    public List<Map<String, Object>> readObjectList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(raw, OBJECT_LIST);
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    public <T> T readObject(String raw, Class<T> type) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, type);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    public <T> List<T> readList(String raw, Class<T> itemType) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readerForListOf(itemType).readValue(raw);
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    public String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("failed to write json", ex);
        }
    }
}
