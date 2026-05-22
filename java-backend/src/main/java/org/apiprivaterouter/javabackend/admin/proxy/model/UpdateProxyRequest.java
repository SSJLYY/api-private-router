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

    @JsonSetter("status")
    public void setStatus(String status) {
        this.status = status;
        this.statusPresent = true;
    }
}
