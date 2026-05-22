package org.apiprivaterouter.javabackend.admin.account.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class UpdateAccountRequest {

    private String name;
    private String notes;
    private String type;
    private Map<String, Object> credentials;
    private Map<String, Object> extra;
    private Long proxy_id;
    private Integer concurrency;
    private Integer load_factor;
    private Integer priority;
    private Double rate_multiplier;
    private Boolean schedulable;
    private String status;
    private List<Long> group_ids;
    private Long expires_at;
    private Boolean auto_pause_on_expired;
    private Boolean confirm_mixed_channel_risk;

    @JsonIgnore
    private boolean namePresent;
    @JsonIgnore
    private boolean notesPresent;
    @JsonIgnore
    private boolean typePresent;
    @JsonIgnore
    private boolean credentialsPresent;
    @JsonIgnore
    private boolean extraPresent;
    @JsonIgnore
    private boolean proxyIdPresent;
    @JsonIgnore
    private boolean concurrencyPresent;
    @JsonIgnore
    private boolean loadFactorPresent;
    @JsonIgnore
    private boolean priorityPresent;
    @JsonIgnore
    private boolean rateMultiplierPresent;
    @JsonIgnore
    private boolean schedulablePresent;
    @JsonIgnore
    private boolean statusPresent;
    @JsonIgnore
    private boolean groupIdsPresent;
    @JsonIgnore
    private boolean expiresAtPresent;
    @JsonIgnore
    private boolean autoPauseOnExpiredPresent;
    @JsonIgnore
    private boolean confirmMixedChannelRiskPresent;

    public String getName() {
        return name;
    }

    public String getNotes() {
        return notes;
    }

    public String getType() {
        return type;
    }

    public Map<String, Object> getCredentials() {
        return credentials;
    }

    public Map<String, Object> getExtra() {
        return extra;
    }

    public Long getProxy_id() {
        return proxy_id;
    }

    public Integer getConcurrency() {
        return concurrency;
    }

    public Integer getLoad_factor() {
        return load_factor;
    }

    public Integer getPriority() {
        return priority;
    }

    public Double getRate_multiplier() {
        return rate_multiplier;
    }

    public Boolean getSchedulable() {
        return schedulable;
    }

    public String getStatus() {
        return status;
    }

    public List<Long> getGroup_ids() {
        return group_ids;
    }

    public Long getExpires_at() {
        return expires_at;
    }

    public Boolean getAuto_pause_on_expired() {
        return auto_pause_on_expired;
    }

    public Boolean getConfirm_mixed_channel_risk() {
        return confirm_mixed_channel_risk;
    }

    public boolean isNamePresent() {
        return namePresent;
    }

    public boolean isNotesPresent() {
        return notesPresent;
    }

    public boolean isTypePresent() {
        return typePresent;
    }

    public boolean isCredentialsPresent() {
        return credentialsPresent;
    }

    public boolean isExtraPresent() {
        return extraPresent;
    }

    public boolean isProxyIdPresent() {
        return proxyIdPresent;
    }

    public boolean isConcurrencyPresent() {
        return concurrencyPresent;
    }

    public boolean isLoadFactorPresent() {
        return loadFactorPresent;
    }

    public boolean isPriorityPresent() {
        return priorityPresent;
    }

    public boolean isRateMultiplierPresent() {
        return rateMultiplierPresent;
    }

    public boolean isSchedulablePresent() {
        return schedulablePresent;
    }

    public boolean isStatusPresent() {
        return statusPresent;
    }

    public boolean isGroupIdsPresent() {
        return groupIdsPresent;
    }

    public boolean isExpiresAtPresent() {
        return expiresAtPresent;
    }

    public boolean isAutoPauseOnExpiredPresent() {
        return autoPauseOnExpiredPresent;
    }

    public boolean isConfirmMixedChannelRiskPresent() {
        return confirmMixedChannelRiskPresent;
    }

    @JsonSetter("name")
    public void setName(String name) {
        this.name = name;
        this.namePresent = true;
    }

    @JsonSetter("notes")
    public void setNotes(String notes) {
        this.notes = notes;
        this.notesPresent = true;
    }

    @JsonSetter("type")
    public void setType(String type) {
        this.type = type;
        this.typePresent = true;
    }

    @JsonSetter("credentials")
    public void setCredentials(Map<String, Object> credentials) {
        this.credentials = credentials;
        this.credentialsPresent = true;
    }

    @JsonSetter("extra")
    public void setExtra(Map<String, Object> extra) {
        this.extra = extra;
        this.extraPresent = true;
    }

    @JsonSetter("proxy_id")
    public void setProxy_id(Long proxy_id) {
        this.proxy_id = proxy_id;
        this.proxyIdPresent = true;
    }

    @JsonSetter("concurrency")
    public void setConcurrency(Integer concurrency) {
        this.concurrency = concurrency;
        this.concurrencyPresent = true;
    }

    @JsonSetter("load_factor")
    public void setLoad_factor(Integer load_factor) {
        this.load_factor = load_factor;
        this.loadFactorPresent = true;
    }

    @JsonSetter("priority")
    public void setPriority(Integer priority) {
        this.priority = priority;
        this.priorityPresent = true;
    }

    @JsonSetter("rate_multiplier")
    public void setRate_multiplier(Double rate_multiplier) {
        this.rate_multiplier = rate_multiplier;
        this.rateMultiplierPresent = true;
    }

    @JsonSetter("schedulable")
    public void setSchedulable(Boolean schedulable) {
        this.schedulable = schedulable;
        this.schedulablePresent = true;
    }

    @JsonSetter("status")
    public void setStatus(String status) {
        this.status = status;
        this.statusPresent = true;
    }

    @JsonSetter("group_ids")
    public void setGroup_ids(List<Long> group_ids) {
        this.group_ids = group_ids;
        this.groupIdsPresent = true;
    }

    @JsonSetter("expires_at")
    public void setExpires_at(Long expires_at) {
        this.expires_at = expires_at;
        this.expiresAtPresent = true;
    }

    @JsonSetter("auto_pause_on_expired")
    public void setAuto_pause_on_expired(Boolean auto_pause_on_expired) {
        this.auto_pause_on_expired = auto_pause_on_expired;
        this.autoPauseOnExpiredPresent = true;
    }

    @JsonSetter("confirm_mixed_channel_risk")
    public void setConfirm_mixed_channel_risk(Boolean confirm_mixed_channel_risk) {
        this.confirm_mixed_channel_risk = confirm_mixed_channel_risk;
        this.confirmMixedChannelRiskPresent = true;
    }
}
