package org.apiprivaterouter.javabackend.admin.proxy.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;

@JsonIgnoreProperties(ignoreUnknown = true)
public class UpdateProxyRequest {

    private String name;
    private String protocol;
    private String host;
    private Integer port;
    private String username;
    private String password;
    private String status;
    private String expires_at;
    private String fallback_mode;
    private Long backup_proxy_id;
    private Integer expiry_warn_days;

    @JsonIgnore
    private boolean namePresent;
    @JsonIgnore
    private boolean protocolPresent;
    @JsonIgnore
    private boolean hostPresent;
    @JsonIgnore
    private boolean portPresent;
    @JsonIgnore
    private boolean usernamePresent;
    @JsonIgnore
    private boolean passwordPresent;
    @JsonIgnore
    private boolean statusPresent;
    @JsonIgnore
    private boolean expiresAtPresent;
    @JsonIgnore
    private boolean fallbackModePresent;
    @JsonIgnore
    private boolean backupProxyIdPresent;
    @JsonIgnore
    private boolean expiryWarnDaysPresent;

    public String getName() {
        return name;
    }

    public String getProtocol() {
        return protocol;
    }

    public String getHost() {
        return host;
    }

    public Integer getPort() {
        return port;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getStatus() {
        return status;
    }

    public boolean isNamePresent() {
        return namePresent;
    }

    public boolean isProtocolPresent() {
        return protocolPresent;
    }

    public boolean isHostPresent() {
        return hostPresent;
    }

    public boolean isPortPresent() {
        return portPresent;
    }

    public boolean isUsernamePresent() {
        return usernamePresent;
    }

    public boolean isPasswordPresent() {
        return passwordPresent;
    }

    public boolean isStatusPresent() {
        return statusPresent;
    }

    public String getExpires_at() {
        return expires_at;
    }

    public String getFallback_mode() {
        return fallback_mode;
    }

    public Long getBackup_proxy_id() {
        return backup_proxy_id;
    }

    public Integer getExpiry_warn_days() {
        return expiry_warn_days;
    }

    public boolean isExpiresAtPresent() {
        return expiresAtPresent;
    }

    public boolean isFallbackModePresent() {
        return fallbackModePresent;
    }

    public boolean isBackupProxyIdPresent() {
        return backupProxyIdPresent;
    }

    public boolean isExpiryWarnDaysPresent() {
        return expiryWarnDaysPresent;
    }

    @JsonSetter("status")
    public void setStatus(String status) {
        this.status = status;
        this.statusPresent = true;
    }

    @JsonSetter("expires_at")
    public void setExpires_at(String expires_at) {
        this.expires_at = expires_at;
        this.expiresAtPresent = true;
    }

    @JsonSetter("fallback_mode")
    public void setFallback_mode(String fallback_mode) {
        this.fallback_mode = fallback_mode;
        this.fallbackModePresent = true;
    }

    @JsonSetter("backup_proxy_id")
    public void setBackup_proxy_id(Long backup_proxy_id) {
        this.backup_proxy_id = backup_proxy_id;
        this.backupProxyIdPresent = true;
    }

    @JsonSetter("expiry_warn_days")
    public void setExpiry_warn_days(Integer expiry_warn_days) {
        this.expiry_warn_days = expiry_warn_days;
        this.expiryWarnDaysPresent = true;
    }

    @JsonSetter("name")
    public void setName(String name) {
        this.name = name;
        this.namePresent = true;
    }

    @JsonSetter("protocol")
    public void setProtocol(String protocol) {
        this.protocol = protocol;
        this.protocolPresent = true;
    }

    @JsonSetter("host")
    public void setHost(String host) {
        this.host = host;
        this.hostPresent = true;
    }

    @JsonSetter("port")
    public void setPort(Integer port) {
        this.port = port;
        this.portPresent = true;
    }

    @JsonSetter("username")
    public void setUsername(String username) {
        this.username = username;
        this.usernamePresent = true;
    }

    @JsonSetter("password")
    public void setPassword(String password) {
        this.password = password;
        this.passwordPresent = true;
    }
}
