package io.axoniq.console.framework.starter;

import io.axoniq.console.framework.AxoniqConsoleDlqMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "axoniq.console")
public class AxoniqConsoleSpringProperties {
    /**
     * The host to connect to. Defaults to {@code framework.console.axoniq.io}.
     */
    private String host = "framework.console.axoniq.io";
    /**
     * The port to connect to. Defaults to {@code 7000}.
     */
    private Integer port = 7000;
    /**
     * The credentials used to connect to the AxonIQ Console. The module will not work without setting this.
     */
    private String credentials = null;
    /**
     * The display name of the application in the UI. Defaults to the application name of the Spring Boot application.
     * Some special characters, such as [ and ] will be filtered out of the name and replaced with a hyphen.
     */
    private String applicationName = null;
    /**
     * The mode of DLQ operations. Defaults to {@code FULL}, which can return sensitive information to the UI.
     * If this concerns you, consider {@code MASKED} to mask potentially sensitive data, or {@code NONE} to disable
     * DLQ visibility.
     */
    private AxoniqConsoleDlqMode dlqMode = AxoniqConsoleDlqMode.FULL;
    /**
     * Whether the connection should use SSL/TLs. Defaults to {@code true}.
     */
    private boolean secure = true;
    /**
     * The initial delay before connecting to the AxonIQ Console in milliseconds. Defaults to {@code 0}.
     */
    private Long initialDelay = 0L;

    /**
     * The maximum number of concurrent management tasks. Defaults to {@code 5}.
     * Management tasks are tasks executed at the request of the user, such as processing DLQ messages.
     */
    private int maxConcurrentManagementTasks = 5;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getCredentials() {
        return credentials;
    }

    public void setCredentials(String credentials) {
        this.credentials = credentials;
    }

    public String getApplicationName() {
        return applicationName;
    }

    public void setApplicationName(String applicationName) {
        this.applicationName = applicationName;
    }

    public AxoniqConsoleDlqMode getDlqMode() {
        return dlqMode;
    }

    public void setDlqMode(AxoniqConsoleDlqMode dlqMode) {
        this.dlqMode = dlqMode;
    }

    public boolean isSecure() {
        return secure;
    }

    public void setSecure(boolean secure) {
        this.secure = secure;
    }

    public Long getInitialDelay() {
        return initialDelay;
    }

    public void setInitialDelay(Long initialDelay) {
        this.initialDelay = initialDelay;
    }

    public int getMaxConcurrentManagementTasks() {
        return maxConcurrentManagementTasks;
    }

    public void setMaxConcurrentManagementTasks(int maxConcurrentManagementTasks) {
        this.maxConcurrentManagementTasks = maxConcurrentManagementTasks;
    }
}
