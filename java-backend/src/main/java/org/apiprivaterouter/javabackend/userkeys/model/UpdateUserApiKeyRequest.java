package org.apiprivaterouter.javabackend.userkeys.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class UpdateUserApiKeyRequest {

    private String name;
    private Long groupId;
    private String status;
    private List<String> ipWhitelist;
    private List<String> ipBlacklist;
    private Double quota;
    private String expiresAt;
    private Boolean resetQuota;
    private Double rateLimit5h;
    private Double rateLimit1d;
    private Double rateLimit7d;
    private Boolean resetRateLimitUsage;

    @JsonIgnore
    private boolean namePresent;
    @JsonIgnore
    private boolean groupIdPresent;
    @JsonIgnore
    private boolean statusPresent;
    @JsonIgnore
    private boolean ipWhitelistPresent;
    @JsonIgnore
    private boolean ipBlacklistPresent;
    @JsonIgnore
    private boolean quotaPresent;
    @JsonIgnore
    private boolean expiresAtPresent;
    @JsonIgnore
    private boolean resetQuotaPresent;
    @JsonIgnore
    private boolean rateLimit5hPresent;
    @JsonIgnore
    private boolean rateLimit1dPresent;
    @JsonIgnore
    private boolean rateLimit7dPresent;
    @JsonIgnore
    private boolean resetRateLimitUsagePresent;

    public String getName() {
        return name;
    }

    public Long getGroupId() {
        return groupId;
    }

    public String getStatus() {
        return status;
    }

    public List<String> getIpWhitelist() {
        return ipWhitelist;
    }

    public List<String> getIpBlacklist() {
        return ipBlacklist;
    }

    public Double getQuota() {
        return quota;
    }

    public String getExpiresAt() {
        return expiresAt;
    }

    public Boolean getResetQuota() {
        return resetQuota;
    }

    public Double getRateLimit5h() {
        return rateLimit5h;
    }

    public Double getRateLimit1d() {
        return rateLimit1d;
    }

    public Double getRateLimit7d() {
        return rateLimit7d;
    }

    public Boolean getResetRateLimitUsage() {
        return resetRateLimitUsage;
    }

    public boolean isNamePresent() {
        return namePresent;
    }

    public boolean isGroupIdPresent() {
        return groupIdPresent;
    }

    public boolean isStatusPresent() {
        return statusPresent;
    }

    public boolean isIpWhitelistPresent() {
        return ipWhitelistPresent;
    }

    public boolean isIpBlacklistPresent() {
        return ipBlacklistPresent;
    }

    public boolean isQuotaPresent() {
        return quotaPresent;
    }

    public boolean isExpiresAtPresent() {
        return expiresAtPresent;
    }

    public boolean isResetQuotaPresent() {
        return resetQuotaPresent;
    }

    public boolean isRateLimit5hPresent() {
        return rateLimit5hPresent;
    }

    public boolean isRateLimit1dPresent() {
        return rateLimit1dPresent;
    }

    public boolean isRateLimit7dPresent() {
        return rateLimit7dPresent;
    }

    public boolean isResetRateLimitUsagePresent() {
        return resetRateLimitUsagePresent;
    }

    @JsonSetter("name")
    public void setName(String name) {
        this.name = name;
        this.namePresent = true;
    }

    @JsonSetter("group_id")
    public void setGroupId(Long groupId) {
        this.groupId = groupId;
        this.groupIdPresent = true;
    }

    @JsonSetter("status")
    public void setStatus(String status) {
        this.status = status;
        this.statusPresent = true;
    }

    @JsonSetter("ip_whitelist")
    public void setIpWhitelist(List<String> ipWhitelist) {
        this.ipWhitelist = ipWhitelist;
        this.ipWhitelistPresent = true;
    }

    @JsonSetter("ip_blacklist")
    public void setIpBlacklist(List<String> ipBlacklist) {
        this.ipBlacklist = ipBlacklist;
        this.ipBlacklistPresent = true;
    }

    @JsonSetter("quota")
    public void setQuota(Double quota) {
        this.quota = quota;
        this.quotaPresent = true;
    }

    @JsonSetter("expires_at")
    public void setExpiresAt(String expiresAt) {
        this.expiresAt = expiresAt;
        this.expiresAtPresent = true;
    }

    @JsonSetter("reset_quota")
    public void setResetQuota(Boolean resetQuota) {
        this.resetQuota = resetQuota;
        this.resetQuotaPresent = true;
    }

    @JsonSetter("rate_limit_5h")
    public void setRateLimit5h(Double rateLimit5h) {
        this.rateLimit5h = rateLimit5h;
        this.rateLimit5hPresent = true;
    }

    @JsonSetter("rate_limit_1d")
    public void setRateLimit1d(Double rateLimit1d) {
        this.rateLimit1d = rateLimit1d;
        this.rateLimit1dPresent = true;
    }

    @JsonSetter("rate_limit_7d")
    public void setRateLimit7d(Double rateLimit7d) {
        this.rateLimit7d = rateLimit7d;
        this.rateLimit7dPresent = true;
    }

    @JsonSetter("reset_rate_limit_usage")
    public void setResetRateLimitUsage(Boolean resetRateLimitUsage) {
        this.resetRateLimitUsage = resetRateLimitUsage;
        this.resetRateLimitUsagePresent = true;
    }
}
