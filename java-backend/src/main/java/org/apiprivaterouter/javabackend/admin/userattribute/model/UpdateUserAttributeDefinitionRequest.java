package org.apiprivaterouter.javabackend.admin.userattribute.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class UpdateUserAttributeDefinitionRequest {

    private String name;
    private String description;
    private String type;
    private List<UserAttributeOption> options;
    private Boolean required;
    private UserAttributeValidation validation;
    private String placeholder;
    private Boolean enabled;

    @JsonIgnore
    private boolean namePresent;
    @JsonIgnore
    private boolean descriptionPresent;
    @JsonIgnore
    private boolean typePresent;
    @JsonIgnore
    private boolean optionsPresent;
    @JsonIgnore
    private boolean requiredPresent;
    @JsonIgnore
    private boolean validationPresent;
    @JsonIgnore
    private boolean placeholderPresent;
    @JsonIgnore
    private boolean enabledPresent;

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getType() {
        return type;
    }

    public List<UserAttributeOption> getOptions() {
        return options;
    }

    public Boolean getRequired() {
        return required;
    }

    public UserAttributeValidation getValidation() {
        return validation;
    }

    public String getPlaceholder() {
        return placeholder;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public boolean isNamePresent() {
        return namePresent;
    }

    public boolean isDescriptionPresent() {
        return descriptionPresent;
    }

    public boolean isTypePresent() {
        return typePresent;
    }

    public boolean isOptionsPresent() {
        return optionsPresent;
    }

    public boolean isRequiredPresent() {
        return requiredPresent;
    }

    public boolean isValidationPresent() {
        return validationPresent;
    }

    public boolean isPlaceholderPresent() {
        return placeholderPresent;
    }

    public boolean isEnabledPresent() {
        return enabledPresent;
    }

    @JsonSetter("name")
    public void setName(String name) {
        this.name = name;
        this.namePresent = true;
    }

    @JsonSetter("description")
    public void setDescription(String description) {
        this.description = description;
        this.descriptionPresent = true;
    }

    @JsonSetter("type")
    public void setType(String type) {
        this.type = type;
        this.typePresent = true;
    }

    @JsonSetter("options")
    public void setOptions(List<UserAttributeOption> options) {
        this.options = options;
        this.optionsPresent = true;
    }

    @JsonSetter("required")
    public void setRequired(Boolean required) {
        this.required = required;
        this.requiredPresent = true;
    }

    @JsonSetter("validation")
    public void setValidation(UserAttributeValidation validation) {
        this.validation = validation;
        this.validationPresent = true;
    }

    @JsonSetter("placeholder")
    public void setPlaceholder(String placeholder) {
        this.placeholder = placeholder;
        this.placeholderPresent = true;
    }

    @JsonSetter("enabled")
    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
        this.enabledPresent = true;
    }
}
