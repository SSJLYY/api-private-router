package org.apiprivaterouter.javabackend.admin.userattribute.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apiprivaterouter.javabackend.admin.userattribute.model.BatchUserAttributesResponse;
import org.apiprivaterouter.javabackend.admin.userattribute.model.CreateUserAttributeDefinitionRequest;
import org.apiprivaterouter.javabackend.admin.userattribute.model.UpdateUserAttributeDefinitionRequest;
import org.apiprivaterouter.javabackend.admin.userattribute.model.UserAttributeDefinitionResponse;
import org.apiprivaterouter.javabackend.admin.userattribute.model.UserAttributeOption;
import org.apiprivaterouter.javabackend.admin.userattribute.model.UserAttributeValidation;
import org.apiprivaterouter.javabackend.admin.userattribute.model.UserAttributeValueResponse;
import org.apiprivaterouter.javabackend.admin.userattribute.repository.AdminUserAttributeRepository;
import org.apiprivaterouter.javabackend.common.api.HttpStatusException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Service
public class AdminUserAttributeService {

    private static final Set<String> VALID_TYPES = Set.of(
            "text", "textarea", "number", "email", "url", "date", "select", "multi_select"
    );

    private final AdminUserAttributeRepository repository;
    private final ObjectMapper objectMapper;

    public AdminUserAttributeService(AdminUserAttributeRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public List<UserAttributeDefinitionResponse> listDefinitions(boolean enabledOnly) {
        return repository.listDefinitions(enabledOnly);
    }

    @Transactional
    public UserAttributeDefinitionResponse createDefinition(CreateUserAttributeDefinitionRequest request) {
        String key = normalizeRequiredText(request.key(), "key", 100);
        String name = normalizeRequiredText(request.name(), "name", 255);
        String type = normalizeType(request.type());
        if (repository.existsActiveDefinitionKey(key, null)) {
            throw new HttpStatusException(409, "attribute key already exists");
        }
        List<UserAttributeOption> options = normalizeOptions(request.options());
        UserAttributeValidation validation = normalizeValidation(request.validation());
        validateDefinitionPattern(name, validation);
        validateOptionsForType(name, type, options);
        long id = repository.createDefinition(
                key,
                name,
                normalizeText(request.description(), ""),
                type,
                options,
                Boolean.TRUE.equals(request.required()),
                validation,
                normalizeText(request.placeholder(), ""),
                request.enabled() == null || request.enabled()
        );
        return getDefinitionOrThrow(id);
    }

    @Transactional
    public UserAttributeDefinitionResponse updateDefinition(long id, UpdateUserAttributeDefinitionRequest request) {
        UserAttributeDefinitionResponse current = getDefinitionOrThrow(id);
        String name = request.isNamePresent() ? normalizeRequiredText(request.getName(), "name", 255) : current.name();
        String description = request.isDescriptionPresent() ? normalizeText(request.getDescription(), "") : current.description();
        String type = request.isTypePresent() ? normalizeType(request.getType()) : current.type();
        List<UserAttributeOption> options = request.isOptionsPresent() ? normalizeOptions(request.getOptions()) : current.options();
        boolean required = request.isRequiredPresent() ? Boolean.TRUE.equals(request.getRequired()) : current.required();
        UserAttributeValidation validation = request.isValidationPresent() ? normalizeValidation(request.getValidation()) : current.validation();
        String placeholder = request.isPlaceholderPresent() ? normalizeText(request.getPlaceholder(), "") : current.placeholder();
        boolean enabled = request.isEnabledPresent() ? Boolean.TRUE.equals(request.getEnabled()) : current.enabled();
        validateDefinitionPattern(name, validation);
        validateOptionsForType(name, type, options);
        if (!repository.updateDefinition(id, name, description, type, options, required, validation, placeholder, enabled)) {
            throw new HttpStatusException(404, "attribute definition not found");
        }
        return getDefinitionOrThrow(id);
    }

    @Transactional
    public Map<String, String> deleteDefinition(long id) {
        getDefinitionOrThrow(id);
        repository.deleteValuesByAttributeId(id);
        if (!repository.softDeleteDefinition(id)) {
            throw new HttpStatusException(404, "attribute definition not found");
        }
        return Map.of("message", "Attribute definition deleted successfully");
    }

    @Transactional
    public Map<String, String> reorderDefinitions(List<Long> ids) {
        repository.reorderDefinitions(normalizeIds(ids));
        return Map.of("message", "Reorder successful");
    }

    public List<UserAttributeValueResponse> getUserValues(long userId) {
        requireUserExists(userId);
        return repository.listUserValues(userId);
    }

    @Transactional
    public List<UserAttributeValueResponse> updateUserValues(long userId, Map<Long, String> values) {
        requireUserExists(userId);
        Map<Long, String> normalizedValues = values == null ? Map.of() : values;
        Map<Long, UserAttributeDefinitionResponse> definitionMap = new LinkedHashMap<>();
        for (UserAttributeDefinitionResponse definition : repository.listDefinitions(true)) {
            definitionMap.put(definition.id(), definition);
        }
        for (Map.Entry<Long, String> entry : normalizedValues.entrySet()) {
            Long attributeId = entry.getKey();
            if (attributeId == null || attributeId <= 0) {
                throw new HttpStatusException(400, "attribute definition not found");
            }
            UserAttributeDefinitionResponse definition = definitionMap.get(attributeId);
            if (definition == null) {
                throw new HttpStatusException(400, "attribute definition not found");
            }
            String value = entry.getValue() == null ? "" : entry.getValue();
            validateValue(definition, value);
            repository.upsertUserValue(userId, attributeId, value);
        }
        return repository.listUserValues(userId);
    }

    public BatchUserAttributesResponse batchUserValues(List<Long> userIds) {
        List<Long> normalized = normalizeIds(userIds);
        if (normalized.isEmpty()) {
            return new BatchUserAttributesResponse(Map.of());
        }
        return new BatchUserAttributesResponse(repository.batchUserValues(normalized));
    }

    private UserAttributeDefinitionResponse getDefinitionOrThrow(long id) {
        return repository.findDefinitionById(id)
                .orElseThrow(() -> new HttpStatusException(404, "attribute definition not found"));
    }

    private void requireUserExists(long userId) {
        if (!repository.userExists(userId)) {
            throw new HttpStatusException(404, "user not found");
        }
    }

    private String normalizeRequiredText(String value, String field, int maxLength) {
        String normalized = normalizeText(value, null);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " is too long");
        }
        return normalized;
    }

    private String normalizeText(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return fallback;
        }
        return normalized;
    }

    private String normalizeType(String rawType) {
        String type = normalizeRequiredText(rawType, "type", 32).toLowerCase();
        if (!VALID_TYPES.contains(type)) {
            throw new HttpStatusException(400, "invalid attribute type");
        }
        return type;
    }

    private List<UserAttributeOption> normalizeOptions(List<UserAttributeOption> rawOptions) {
        if (rawOptions == null || rawOptions.isEmpty()) {
            return List.of();
        }
        List<UserAttributeOption> normalized = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (UserAttributeOption option : rawOptions) {
            if (option == null) {
                continue;
            }
            String value = normalizeRequiredText(option.value(), "option value", 255);
            String label = normalizeRequiredText(option.label(), "option label", 255);
            if (seen.add(value)) {
                normalized.add(new UserAttributeOption(value, label));
            }
        }
        return List.copyOf(normalized);
    }

    private UserAttributeValidation normalizeValidation(UserAttributeValidation validation) {
        if (validation == null) {
            return new UserAttributeValidation(null, null, null, null, null, null);
        }
        return new UserAttributeValidation(
                validation.minLength(),
                validation.maxLength(),
                validation.min(),
                validation.max(),
                normalizeText(validation.pattern(), null),
                normalizeText(validation.message(), null)
        );
    }

    private List<Long> normalizeIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
    }

    private void validateDefinitionPattern(String name, UserAttributeValidation validation) {
        if (validation == null || validation.pattern() == null || validation.pattern().isBlank()) {
            return;
        }
        try {
            Pattern.compile(validation.pattern());
        } catch (PatternSyntaxException ex) {
            throw new HttpStatusException(400, "invalid pattern for " + name + ": " + ex.getMessage());
        }
    }

    private void validateOptionsForType(String name, String type, List<UserAttributeOption> options) {
        if (Set.of("select", "multi_select").contains(type) && (options == null || options.isEmpty())) {
            throw new HttpStatusException(400, name + " options are required");
        }
    }

    private void validateValue(UserAttributeDefinitionResponse definition, String value) {
        boolean empty = value == null || value.isEmpty();
        if (empty && !definition.required()) {
            return;
        }
        if (definition.required() && empty) {
            throw new HttpStatusException(400, definition.name() + " is required");
        }
        UserAttributeValidation validation = definition.validation() == null
                ? new UserAttributeValidation(null, null, null, null, null, null)
                : definition.validation();
        if (validation.minLength() != null && value.length() < validation.minLength()) {
            throw new HttpStatusException(400, definition.name() + " must be at least " + validation.minLength() + " characters");
        }
        if (validation.maxLength() != null && value.length() > validation.maxLength()) {
            throw new HttpStatusException(400, definition.name() + " must be at most " + validation.maxLength() + " characters");
        }
        if ("number".equals(definition.type()) && !empty) {
            int numericValue;
            try {
                numericValue = Integer.parseInt(value);
            } catch (NumberFormatException ex) {
                throw new HttpStatusException(400, definition.name() + " must be a number");
            }
            if (validation.min() != null && numericValue < validation.min()) {
                throw new HttpStatusException(400, definition.name() + " must be at least " + validation.min());
            }
            if (validation.max() != null && numericValue > validation.max()) {
                throw new HttpStatusException(400, definition.name() + " must be at most " + validation.max());
            }
        }
        if (validation.pattern() != null && !validation.pattern().isBlank() && !empty) {
            if (!Pattern.compile(validation.pattern()).matcher(value).matches()) {
                throw new HttpStatusException(400, validation.message() == null || validation.message().isBlank()
                        ? definition.name() + " format is invalid"
                        : validation.message());
            }
        }
        if ("select".equals(definition.type()) && !empty) {
            validateSelectValue(definition, value);
        }
        if ("multi_select".equals(definition.type()) && !empty) {
            validateMultiSelectValue(definition, value);
        }
    }

    private void validateSelectValue(UserAttributeDefinitionResponse definition, String value) {
        boolean found = definition.options() != null && definition.options().stream()
                .anyMatch(option -> option != null && value.equals(option.value()));
        if (!found) {
            throw new HttpStatusException(400, definition.name() + ": invalid option");
        }
    }

    private void validateMultiSelectValue(UserAttributeDefinitionResponse definition, String value) {
        List<String> selected;
        try {
            selected = objectMapper.readerForListOf(String.class).readValue(value);
        } catch (JsonProcessingException ex) {
            selected = List.of(value.split(","));
        }
        Set<String> allowed = definition.options() == null
                ? Set.of()
                : definition.options().stream()
                .filter(option -> option != null && option.value() != null)
                .map(UserAttributeOption::value)
                .collect(java.util.stream.Collectors.toSet());
        for (String item : selected) {
            String candidate = item == null ? "" : item.trim();
            if (!candidate.isEmpty() && !allowed.contains(candidate)) {
                throw new HttpStatusException(400, definition.name() + ": invalid option " + candidate);
            }
        }
    }
}
