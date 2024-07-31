/*
 * Copyright (c) 2022-2024. AxonIQ B.V.
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

package io.axoniq.console.framework.eventprocessor

import io.axoniq.console.framework.api.ResetDecision
import io.axoniq.console.framework.api.ResetDecisions
import org.axonframework.common.ReflectionUtils
import org.axonframework.common.transaction.TransactionManager
import org.axonframework.config.EventProcessingConfiguration
import org.axonframework.eventhandling.StreamingEventProcessor
import org.axonframework.eventhandling.TrackingEventProcessor
import org.axonframework.eventhandling.pooled.PooledStreamingEventProcessor
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class EventProcessorManager(
    private val eventProcessingConfig: EventProcessingConfiguration,
    private val transactionManager: TransactionManager
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private var lastLogForOldVersion = Instant.EPOCH

    fun start(processorName: String) {
        eventProcessor(processorName).start()
    }

    fun stop(processorName: String) {
        eventProcessor(processorName).shutDown()
    }

    fun releaseSegment(processorName: String, segmentId: Int) {
        val eventProcessor = eventProcessor(processorName)
        eventProcessor.releaseSegment(segmentId)
        waitForProcessorToHaveUnclaimedSegment(eventProcessor, segmentId)
    }

    fun splitSegment(processorName: String, segmentId: Int) =
        eventProcessor(processorName)
            .splitSegment(segmentId)
            .get(5, TimeUnit.SECONDS)

    fun mergeSegment(processorName: String, segmentId: Int) =
        eventProcessor(processorName)
            .mergeSegment(segmentId)
            .get(5, TimeUnit.SECONDS)

    fun resetTokens(resetDecision: ResetDecision) =
        eventProcessor(resetDecision.processorName)
            .resetTokens { messageSource ->
                when (resetDecision.decision) {
                    ResetDecisions.HEAD -> messageSource.createHeadToken()
                    ResetDecisions.TAIL -> messageSource.createTailToken()
                    ResetDecisions.FROM -> messageSource.createTokenAt(resetDecision.from!!)
                }
            }

    fun claimSegment(processorName: String, segmentId: Int): Boolean {
        val processor = eventProcessor(processorName)
        transactionManager.executeInTransaction {
            eventProcessingConfig.tokenStore(processorName).fetchToken(processorName, segmentId)
        }

        executeClaimMethodOrFallback(processor, segmentId)
        return waitForProcessorToHaveClaimedSegment(processor, segmentId)
    }

    private fun waitForProcessorToHaveClaimedSegment(
        processor: StreamingEventProcessor,
        segmentId: Int,
    ): Boolean {
        var loop = 0
        while (loop < 300) {
            Thread.sleep(100)
            if (processor.processingStatus().containsKey(segmentId)) {
                logger.debug("Processor [${processor.name}] successfully claimed segment [$segmentId] in approx. [${loop * 100}ms].")
                return true
            }
            loop++
        }

        logger.debug("Processor [${processor.name}] failed to claim [$segmentId] in approx. [${loop * 100}ms].")
        return false
    }

    private fun waitForProcessorToHaveUnclaimedSegment(
        processor: StreamingEventProcessor,
        segmentId: Int,
    ): Boolean {
        var loop = 0
        while (loop < 300) {
            Thread.sleep(100)
            if (!processor.processingStatus().containsKey(segmentId) || processor.processingStatus().get(segmentId)!!.isErrorState) {
                logger.debug("Processor [${processor.name}] successfully unclaimed segment [$segmentId] in approx. [${loop * 100}ms].")
                return true
            }
            loop++
        }

        logger.debug("Processor [${processor.name}] failed to unclaim [$segmentId] in approx. [${loop * 100}ms].")
        return false
    }

    private fun executeClaimMethodOrFallback(processor: StreamingEventProcessor, segmentId: Int) {
        try {
            val claimMethod = processor.getMethod("claimSegment")
            ReflectionUtils.ensureAccessible(claimMethod)
            claimMethod.invoke(processor, segmentId)
        } catch (e: Exception) {
            executeFallback(processor, segmentId)
        }
    }

    private fun executeFallback(processor: StreamingEventProcessor, segmentId: Int) {
        logIfTimeExpired {
            logger.info("You processor is being load-balanced and you are using Axon Framework 4.8.x or earlier. Using 4.9.x or later will improve the usability and performance greatly. Read the information in the AxonIQ Console UI.")

            if (processor is TrackingEventProcessor) {
                logger.info("Your TrackingEventProcessor ${processor.name} is being load-balanced. This can cause interruptions due to the long waits. Read the information in the AxonIQ Console UI.")
            }
        }

        if (processor is TrackingEventProcessor) {
            try {
                removeReleaseDeadlineForTrackingProcoessor(processor, segmentId)
            }catch (e: Exception) {
                logger.warn("Was unable to remove release deadline for the TrackingEventProcessor", e)
            }
        } else if (processor is PooledStreamingEventProcessor) {
            try {
                triggerImmediateCoordinationTaskWithTokenClaim(processor, segmentId)
            } catch (e: Exception) {
                logger.warn("Was unable to trigger coordination task with immediate claim for the PooledStreamingEventProcessor", e)
            }
        }
    }

    private fun logIfTimeExpired(block: () -> Unit) {
        if(Instant.now().isAfter(lastLogForOldVersion.plusSeconds(120))) {
            block()
            lastLogForOldVersion = Instant.now()
        }
    }

    /**
     * This is a hack to trigger the coordination task to claim a token.
     * It will, using reflection, set fields of the CoordinationTask to 0 and then trigger it,
     * so it immediately checks the TokenStore whether there are tokens to pick up.
     */
    private fun triggerImmediateCoordinationTaskWithTokenClaim(processor: StreamingEventProcessor, segmentId: Int) {
        val coordinatorField = processor.getField("coordinator")
        val coordinator = ReflectionUtils.getFieldValue<Any>(coordinatorField, processor)
        val coordinationTaskField = coordinator.getField("coordinationTask")
        val coordinationTaskAtomicReference = ReflectionUtils.getFieldValue<AtomicReference<*>>(
            coordinationTaskField,
            coordinator
        )
        val coordinationTask = coordinationTaskAtomicReference.get()
        val unclaimedSegmentValidationThresholdField = coordinationTask.getField("unclaimedSegmentValidationThreshold")
        ReflectionUtils.setFieldValue(unclaimedSegmentValidationThresholdField, coordinationTask, 0L)

        val releasesDeadlinesField = coordinator.getField("releasesDeadlines")
        val map = ReflectionUtils.getFieldValue<MutableMap<Int, Instant>>(releasesDeadlinesField, coordinator)
        map.remove(segmentId)

        val taskMethod = coordinationTask.getMethod("scheduleImmediateCoordinationTask")
        ReflectionUtils.ensureAccessible(taskMethod)
        taskMethod.invoke(coordinationTask)
    }

    private fun removeReleaseDeadlineForTrackingProcoessor(processor: StreamingEventProcessor, segmentId: Int) {
        val releasesDeadlinesField = processor.getField("segmentReleaseDeadlines")
        val map = ReflectionUtils.getFieldValue<MutableMap<Int, Instant>>(releasesDeadlinesField, processor)
        map.remove(segmentId)
    }

    private fun eventProcessor(processorName: String): StreamingEventProcessor =
        eventProcessingConfig.eventProcessor(processorName, StreamingEventProcessor::class.java)
            .orElseThrow { IllegalArgumentException("Event Processor [$processorName] not found!") }

    private fun Any.getField(name: String) =
        this::class.java.declaredFields.firstOrNull { it.name == name }
            ?: throw IllegalStateException("Could not find field [$name]!")

    private fun Any.getMethod(name: String) =
        this::class.java.declaredMethods.firstOrNull { it.name == name }
            ?: throw IllegalStateException("Could not find method [$name]!")
}
