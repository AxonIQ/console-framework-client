/*
 * Copyright (c) 2022-2023. AxonIQ B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.axoniq.console.framework.messaging

import io.axoniq.console.framework.api.metrics.*
import io.axoniq.console.framework.computeIfAbsentWithRetry
import org.axonframework.messaging.Message
import org.axonframework.messaging.unitofwork.CurrentUnitOfWork
import org.axonframework.tracing.Span
import org.axonframework.tracing.SpanAttributesProvider
import org.axonframework.tracing.SpanFactory
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Supplier


class AxoniqConsoleSpanFactory(private val spanMatcherPredicateMap: SpanMatcherPredicateMap) : SpanFactory {

    companion object {
        private val logger = LoggerFactory.getLogger(this::class.java)
        private val NOOP_SPAN = NoopSpan()
        private val ACTIVE_ROOT_SPANS = ConcurrentHashMap<String, MeasuringConsoleSpan>()
        private val CURRENT_MESSAGE_ID = ThreadLocal<String>()

        fun onTopLevelSpanIfActive(block: (MeasuringConsoleSpan) -> Unit) {
            if (CURRENT_MESSAGE_ID.get() == null) {
                return
            }

            ACTIVE_ROOT_SPANS[CURRENT_MESSAGE_ID.get()]?.let {
                try {
                    block(it)
                } catch (e: Exception) {
                    logger.info("Was unable to report AxonIQ Console metrics", e)
                }
            }
        }
    }

    inner class MeasuringConsoleSpan(private val messageId: String) : Span {
        private var timeStarted: Long? = null
        private var transactionSuccessful = true

        // Fields that should be set by the handler enhancer
        private var handlerMetricIdentifier: HandlerStatisticsMetricIdentifier? = null
        private var handlerSuccessful = true
        private var dispatchedMessages = mutableListOf<MessageIdentifier>()

        // Additional metrics that can be registered by other spans for processors
        private val metrics: MutableMap<Metric, Long> = mutableMapOf()

        fun registerHandler(handlerMetricIdentifier: HandlerStatisticsMetricIdentifier?, time: Long) {
            if (handlerMetricIdentifier == null) {
                return
            }
            this.handlerMetricIdentifier = handlerMetricIdentifier
            this.registerMetricValue(PreconfiguredMetric.MESSAGE_HANDLER_TIME, time)
        }

        fun registerMessageDispatched(message: MessageIdentifier) {
            dispatchedMessages.add(message)
        }

        fun registerMetricValue(metric: Metric, value: Long) {
            val actualValue = value - metric.breakDownMetrics.sumOf { metrics[it] ?: 0 }
            metrics[metric] = actualValue
        }

        override fun start(): Span {
            logger.trace("Starting span for message id $messageId")
            ACTIVE_ROOT_SPANS[messageId] = this
            CURRENT_MESSAGE_ID.set(messageId)
            timeStarted = System.nanoTime()
            CurrentUnitOfWork.map {
                it.onRollback { transactionSuccessful = false }
            }
            return this
        }

        override fun end() {
            val end = System.nanoTime()
            ACTIVE_ROOT_SPANS.remove(messageId)
            CURRENT_MESSAGE_ID.remove()
            logger.trace("Ending span for message id $messageId  = $handlerMetricIdentifier")

            if (handlerMetricIdentifier == null || timeStarted == null) return
            CurrentUnitOfWork.map {
                it.onCleanup { report(end) }
            }.orElseGet {
                report(end)
            }
        }

        private fun report(end: Long) {
            try {
                logger.trace("Reporting span for message id $messageId = $handlerMetricIdentifier")
                val success = handlerSuccessful && transactionSuccessful
                HandlerMetricsRegistry.getInstance()?.registerMessageHandled(
                        handler = handlerMetricIdentifier!!,
                        success = success,
                        duration = end - timeStarted!!,
                        metrics = metrics
                )
                if (success) {
                    dispatchedMessages.forEach {
                        HandlerMetricsRegistry.getInstance()?.registerMessageDispatchedDuringHandling(
                                DispatcherStatisticIdentifier(handlerMetricIdentifier, it)
                        )
                    }
                }
            } catch (e: Exception) {
                logger.info("Could not report metrics for message $handlerMetricIdentifier", e)
            }
        }

        override fun recordException(t: Throwable): Span {
            transactionSuccessful = false
            return this
        }
    }

    override fun createRootTrace(operationNameSupplier: Supplier<String>): Span {
        return NOOP_SPAN
    }

    override fun createHandlerSpan(
            operationNameSupplier: Supplier<String>,
            parentMessage: Message<*>,
            isChildTrace: Boolean,
            vararg linkedParents: Message<*>?
    ): Span {
        val name = operationNameSupplier.get()
        if (spanMatcherPredicateMap[SpanMatcher.HANDLER]!!.test(name)) {
            return startIfNotActive(parentMessage)
        }
        return NOOP_SPAN
    }

    override fun createDispatchSpan(
            operationNameSupplier: Supplier<String>,
            parentMessage: Message<*>?,
            vararg linkedSiblings: Message<*>?
    ): Span {
        return NOOP_SPAN
    }

    override fun createInternalSpan(operationNameSupplier: Supplier<String>): Span {
        val name = operationNameSupplier.get()
        if (spanMatcherPredicateMap[SpanMatcher.OBTAIN_LOCK]!!.test(name)) {
            return TimeRecordingSpan(PreconfiguredMetric.AGGREGATE_LOCK_TIME)
        }
        if (spanMatcherPredicateMap[SpanMatcher.LOAD]!!.test(name)) {
            return TimeRecordingSpan(PreconfiguredMetric.AGGREGATE_LOAD_TIME)
        }
        if (spanMatcherPredicateMap[SpanMatcher.COMMIT]!!.test(name)) {
            return TimeRecordingSpan(PreconfiguredMetric.EVENT_COMMIT_TIME)
        }

        return NOOP_SPAN
    }

    override fun createInternalSpan(operationNameSupplier: Supplier<String>, message: Message<*>): Span {
        val name = operationNameSupplier.get()
        if (spanMatcherPredicateMap[SpanMatcher.MESSAGE_START]!!.test(name)) {
            return startIfNotActive(message)
        }
        return NOOP_SPAN
    }

    override fun createChildHandlerSpan(operationNameSupplier: Supplier<String>, message: Message<*>, vararg linkedParents: Message<*>?): Span {
        val name = operationNameSupplier.get()
        if (spanMatcherPredicateMap[SpanMatcher.MESSAGE_START]!!.test(name)) {
            return startIfNotActive(message)
        }
        return NOOP_SPAN
    }

    override fun registerSpanAttributeProvider(provider: SpanAttributesProvider?) {
        // Not necessary
    }

    override fun <M : Message<*>?> propagateContext(message: M): M {
        return message
    }

    private fun startIfNotActive(message: Message<*>): Span {
        if (ACTIVE_ROOT_SPANS.containsKey(message.identifier)) {
            return NOOP_SPAN
        }
        return ACTIVE_ROOT_SPANS.computeIfAbsentWithRetry(message.identifier) {
            MeasuringConsoleSpan(message.identifier)
        }
    }

    class TimeRecordingSpan(private val metric: Metric) : Span {
        init {
            assert(metric.type == MetricType.TIMER)
        }

        private var started: Long? = null
        override fun start(): Span {
            started = System.nanoTime()
            return this
        }

        override fun end() {
            if (started == null) {
                return
            }
            val ended = System.nanoTime()
            onTopLevelSpanIfActive {
                it.registerMetricValue(metric, ended - started!!)
            }

        }

        override fun recordException(t: Throwable?): Span = this
    }

    class NoopSpan : Span {
        override fun start(): Span = this

        override fun end() {
            // Not implemented
        }

        override fun recordException(t: Throwable?): Span = this
    }
}
