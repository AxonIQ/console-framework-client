package io.axoniq.console.framework;

import io.axoniq.console.framework.client.AxoniqConsoleRSocketClient;
import io.axoniq.console.framework.client.RSocketHandlerRegistrar;
import io.axoniq.console.framework.client.ServerProcessorReporter;
import io.axoniq.console.framework.client.SetupPayloadCreator;
import io.axoniq.console.framework.client.strategy.CborEncodingStrategy;
import io.axoniq.console.framework.client.strategy.RSocketPayloadEncodingStrategy;
import io.axoniq.console.framework.eventprocessor.*;
import io.axoniq.console.framework.eventprocessor.metrics.AxoniqConsoleProcessorInterceptor;
import io.axoniq.console.framework.eventprocessor.metrics.ProcessorMetricsRegistry;
import io.axoniq.console.framework.messaging.AxoniqConsoleSpanFactory;
import io.axoniq.console.framework.messaging.HandlerMetricsRegistry;
import org.axonframework.common.BuilderUtils;
import org.axonframework.common.transaction.TransactionManager;
import org.axonframework.config.Configurer;
import org.axonframework.config.ConfigurerModule;
import org.axonframework.tracing.SpanFactory;
import org.jetbrains.annotations.NotNull;

import java.lang.management.ManagementFactory;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Applies the configuration necessary for AxonIQ Console to the {@link Configurer} of Axon Framework.
 * The module will automatically start when Axon Framework does.
 */
public class AxoniqConsoleConfigurerModule implements ConfigurerModule {
    private final String environmentId;
    private final String accessToken;
    private final String applicationName;
    private final String host;
    private final Integer port;
    private final Boolean secure;
    private final Long initialDelay;
    private final AxoniqConsoleDlqMode dlqMode;
    private final ScheduledExecutorService executorService;
    private final boolean configureSpanFactory;

    /**
     * Creates the {@link AxoniqConsoleConfigurerModule} with the given {@code builder}.
     *
     * @param builder The configured builder for the {@link AxoniqConsoleConfigurerModule}.
     */
    protected AxoniqConsoleConfigurerModule(Builder builder) {
        this.environmentId = builder.environmentId;
        this.accessToken = builder.accessToken;
        this.applicationName = builder.applicationName.replaceAll("([\\[\\]])", "-");
        this.host = builder.host;
        this.port = builder.port;
        this.secure = builder.secure;
        this.initialDelay = builder.initialDelay;
        this.dlqMode = builder.dlqMode;
        this.executorService = builder.executorService;
        this.configureSpanFactory = !builder.disableSpanFactoryInConfiguration;
    }

    /**
     * Creates the base builder with the required parameters. Defaults to the public production environment of AxonIQ
     * console.
     *
     * @param environmentId   The environment identifier of AxonIQ Console to connect.
     * @param accessToken     The access token needed to authenticate to the environment.
     * @param applicationName The display name of the application. Some special characters may be replaced with a hyphen.
     * @return The builder with which you can further configure this module
     */
    public static Builder builder(String environmentId, String accessToken, String applicationName) {
        return new Builder(environmentId, accessToken, applicationName);
    }

    @Override
    public void configureModule(@NotNull Configurer configurer) {
        configurer
                .registerComponent(ProcessorMetricsRegistry.class,
                        c -> new ProcessorMetricsRegistry()
                )
                .registerComponent(ProcessorReportCreator.class,
                        c -> new ProcessorReportCreator(
                                c.eventProcessingConfiguration(),
                                c.getComponent(ProcessorMetricsRegistry.class)
                        )
                )
                .registerComponent(SetupPayloadCreator.class,
                        SetupPayloadCreator::new
                )
                .registerComponent(EventProcessorManager.class,
                        c -> new EventProcessorManager(
                                c.eventProcessingConfiguration(),
                                c.getComponent(TransactionManager.class)
                        )
                )
                .registerComponent(RSocketPayloadEncodingStrategy.class,
                        c -> new CborEncodingStrategy()
                )
                .registerComponent(RSocketHandlerRegistrar.class,
                        c -> new RSocketHandlerRegistrar(c.getComponent(RSocketPayloadEncodingStrategy.class))
                )
                .registerComponent(RSocketProcessorResponder.class,
                        c -> new RSocketProcessorResponder(
                                c.getComponent(EventProcessorManager.class),
                                c.getComponent(ProcessorReportCreator.class),
                                c.getComponent(RSocketHandlerRegistrar.class)
                        )
                )
                .registerComponent(AxoniqConsoleRSocketClient.class,
                        c -> new AxoniqConsoleRSocketClient(
                                environmentId,
                                accessToken,
                                applicationName,
                                host,
                                port,
                                secure,
                                initialDelay,
                                c.getComponent(SetupPayloadCreator.class),
                                c.getComponent(RSocketHandlerRegistrar.class),
                                c.getComponent(RSocketPayloadEncodingStrategy.class),
                                executorService,
                                ManagementFactory.getRuntimeMXBean().getName()
                        )
                )
                .registerComponent(ServerProcessorReporter.class,
                        c -> new ServerProcessorReporter(
                                c.getComponent(AxoniqConsoleRSocketClient.class),
                                c.getComponent(ProcessorReportCreator.class),
                                executorService)
                )
                .registerComponent(HandlerMetricsRegistry.class,
                        c -> new HandlerMetricsRegistry(
                                c.getComponent(AxoniqConsoleRSocketClient.class),
                                executorService,
                                applicationName
                        )
                )
                .registerComponent(DeadLetterManager.class,
                        c -> new DeadLetterManager(
                                c.eventProcessingConfiguration(),
                                c.eventSerializer(),
                                dlqMode
                        ))
                .registerComponent(RSocketDlqResponder.class,
                        c -> new RSocketDlqResponder(
                                c.getComponent(DeadLetterManager.class),
                                c.getComponent(RSocketHandlerRegistrar.class)
                        ))
                .eventProcessing()
                .registerDefaultHandlerInterceptor((
                        c, name) -> new AxoniqConsoleProcessorInterceptor(
                        c.getComponent(ProcessorMetricsRegistry.class),
                        name
                ));

        if (configureSpanFactory) {
            configurer.registerComponent(SpanFactory.class, c -> new AxoniqConsoleSpanFactory());
        }

        configurer.onInitialize(c -> {
            c.getComponent(ServerProcessorReporter.class);
            c.getComponent(RSocketProcessorResponder.class);
            c.getComponent(RSocketDlqResponder.class);
            c.getComponent(HandlerMetricsRegistry.class);
        });

        new AxoniqConsoleAggregateConfigurerModule().configureModule(configurer);
    }

    /**
     * Builder class to instantiate a {@link AxoniqConsoleConfigurerModule}.
     */
    public static class Builder {
        private final String environmentId;
        private final String accessToken;
        private final String applicationName;

        private String host = "framework.console.axoniq.io";
        private Boolean secure = true;
        private Integer port = 7000;
        private AxoniqConsoleDlqMode dlqMode = AxoniqConsoleDlqMode.FULL;
        private Long initialDelay = 0L;
        private Integer threadPoolSize = 2;
        private boolean disableSpanFactoryInConfiguration = false;
        private ScheduledExecutorService executorService;

        /**
         * Constructor to instantiate a {@link Builder} based on the fields contained in the {@link
         * AxoniqConsoleConfigurerModule.Builder}. Requires the {@code environmentId}, {@code accessToken} and {@code
         * applicationName} to be set.
         *
         * @param environmentId   The environment identifier of AxonIQ Console to connect.
         * @param accessToken     The access token needed to authenticate to the environment.
         * @param applicationName The display name of the application. Some special characters may be replaced with a hyphen.
         */
        public Builder(String environmentId, String accessToken, String applicationName) {
            BuilderUtils.assertNonEmpty(environmentId, "AxonIQ Console environmentId may not be null or empty");
            BuilderUtils.assertNonEmpty(accessToken, "AxonIQ Console accessToken may not be null or empty");
            BuilderUtils.assertNonEmpty(applicationName, "AxonIQ Console applicationName may not be null or empty");
            this.environmentId = environmentId;
            this.accessToken = accessToken;
            this.applicationName = applicationName;
        }

        /**
         * The host to connect to. Defaults to {@code framework.console.axoniq.io}.
         *
         * @param host The host to connect to
         * @return The builder for fluent interfacing
         */
        public Builder host(String host) {
            BuilderUtils.assertNonEmpty(host, "AxonIQ Console host may not be null or empty");
            this.host = host;
            return this;
        }

        /**
         * The port to connect to. Defaults to {@code 7000}.
         *
         * @param port The port to connect to
         * @return The builder for fluent interfacing
         */
        public Builder port(Integer port) {
            BuilderUtils.assertNonNull(host, "AxonIQ Console port may not be null");
            this.port = port;
            return this;
        }

        /**
         * The mode of the DLQ to operate in. Defaults to {@link AxoniqConsoleDlqMode#FULL}, which means that all
         * information can be accessed from AxonIQ Console.
         *
         * @param dlqMode The mode to set the DLQ to
         * @return The builder for fluent interfacing
         */
        public Builder dlqMode(AxoniqConsoleDlqMode dlqMode) {
            BuilderUtils.assertNonNull(dlqMode, "AxonIQ Console dlqMode may not be null");
            this.dlqMode = dlqMode;
            return this;
        }

        /**
         * The initial delay before attempting to establish a connection. Defaults to {@code 0}.
         *
         * @param initialDelay The delay in milliseconds
         * @return The builder for fluent interfacing
         */
        public Builder initialDelay(Long initialDelay) {
            BuilderUtils.assertPositive(initialDelay, "AxonIQ Console initialDelay must be positive");
            this.initialDelay = initialDelay;
            return this;
        }

        /**
         * The thread pool's size that is used for various tasks, such as sending metrics to AxonIQ Console.
         * Defaults to {@code 2}.
         *
         * @param threadPoolSize The thread pool size
         * @return The builder for fluent interfacing
         */
        public Builder threadPoolSize(Integer threadPoolSize) {
            BuilderUtils.assertPositive(threadPoolSize, "AxonIQ Console threadPoolSize must be positive");
            this.threadPoolSize = threadPoolSize;
            return this;
        }

        /**
         * The {@link ScheduledExecutorService} that should be used for reporting metrics.
         * Defaults to a {@link Executors#newScheduledThreadPool(int)} with
         * the {@code threadPoolSize} of this builder if not set.
         *
         * @param executorService The executor service.
         * @return The builder for fluent interfacing
         */
        public Builder executorService(ScheduledExecutorService executorService) {
            BuilderUtils.assertNonNull(threadPoolSize, "AxonIQ Console executorService must be non-null");
            this.executorService = executorService;
            return this;
        }

        /**
         * Disables setting the {@link SpanFactory} if set to {@code true}. Defaults to {@code
         * false}. Useful in case frameworks override this and can cause a split-brain situation.
         *
         * @return The builder for fluent interfacing
         */
        public Builder disableSpanFactoryInConfiguration() {
            this.disableSpanFactoryInConfiguration = true;
            return this;
        }

        /**
         * Whether to use a secure connection using SSL/TLS or not. Defaults to {@code true}.
         *
         * @param secure Whether to use a secure connection or not
         * @return The builder for fluent interfacing
         */
        public Builder secure(boolean secure) {
            this.secure = secure;
            return this;
        }

        /**
         * Builds the {@link AxoniqConsoleConfigurerModule} based on the fields set in this {@link Builder}.
         *
         * @return The module
         */
        public AxoniqConsoleConfigurerModule build() {
            if(executorService == null) {
                executorService = Executors.newScheduledThreadPool(threadPoolSize);
            }
            return new AxoniqConsoleConfigurerModule(this);
        }
    }
}