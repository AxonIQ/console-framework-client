/*
 * Copyright (c) 2022-2025. AxonIQ B.V.
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

import io.axoniq.console.framework.api.AxoniqConsoleDlqMode
import org.apache.commons.codec.digest.DigestUtils
import org.axonframework.config.EventProcessingConfiguration
import org.axonframework.eventhandling.EventMessage
import org.axonframework.messaging.MetaData
import org.axonframework.messaging.deadletter.DeadLetter
import org.axonframework.messaging.deadletter.SequencedDeadLetterQueue
import org.axonframework.serialization.Serializer
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import io.axoniq.console.framework.api.DeadLetter as ApiDeadLetter
import io.axoniq.console.framework.api.DeadLetterResponse

private const val LETTER_PAYLOAD_SIZE_LIMIT = 1024
private const val MASKED = "<MASKED>"
private const val LIMITED = "<LIMITED>"

class DeadLetterManager(
        private val eventProcessingConfig: EventProcessingConfiguration,
        private val eventSerializer: Serializer,
        private val dlqMode: AxoniqConsoleDlqMode,
        private val dlqDiagnosticsWhitelist: List<String>,
        private val executor: ExecutorService,
) {

    fun deadLetters(
        processingGroup: String,
        offset: Int = 0,
        size: Int = 25,
        maxSequenceLetters: Int = 10
    ): DeadLetterResponse {
        if (dlqMode == AxoniqConsoleDlqMode.NONE) {
            return DeadLetterResponse(emptyList(), 0)
        }
        val sequences = dlqFor(processingGroup)
            .deadLetters()
            .drop(offset)
            .take(size)
            .map { sequence ->
                sequence
                    .asIterable()
                    .take(maxSequenceLetters)
                    .map { toDeadLetter(it, processingGroup) }
            }
        val totalCount = totalDeadLetters(processingGroup)
        return DeadLetterResponse(sequences, totalCount)
    }

    private fun toDeadLetter(letter: DeadLetter<out EventMessage<*>>, processingGroup: String) =
        letter.toApiLetter(sequenceIdentifierFor(processingGroup, letter))

    private fun DeadLetter<out EventMessage<*>>.toApiLetter(sequenceIdentifier: String): io.axoniq.console.framework.api.DeadLetter {
        if (dlqMode == AxoniqConsoleDlqMode.MASKED) {
            return ApiDeadLetter(
                this.message().identifier,
                MASKED,
                this.message().payloadType.simpleName,
                this.cause().map { it.type() }.orElse(null),
                this.cause().map { MASKED }.orElse(null),
                this.enqueuedAt(),
                this.lastTouched(),
                MetaData.emptyInstance(),
                sequenceIdentifier.hashIfNeeded()
            )
        } else if (dlqMode == AxoniqConsoleDlqMode.LIMITED) {
            return ApiDeadLetter(
                    this.message().identifier,
                    LIMITED,
                    this.message().payloadType.simpleName,
                    this.cause().map { it.type() }.orElse(null),
                    this.cause().map { LIMITED }.orElse(null),
                    this.enqueuedAt(),
                    this.lastTouched(),
                    this.diagnostics().filtered(),
                    sequenceIdentifier
            )
        }
        return ApiDeadLetter(
            this.message().identifier,
            serializePayload(),
            this.message().payloadType.simpleName,
            this.cause().map { it.type() }.orElse(null),
            this.cause().map { it.message() }.orElse(null),
            this.enqueuedAt(),
            this.lastTouched(),
            this.diagnostics(),
            sequenceIdentifier
        )
    }

    private fun DeadLetter<out EventMessage<*>>.serializePayload() =
        try {
            eventSerializer
                .serialize(this.message().payload, String::class.java)
                .data
        } catch (_: Exception) {
            this.message().payload.toString()
        }.take(LETTER_PAYLOAD_SIZE_LIMIT)

    private fun sequenceIdentifierFor(
        processingGroup: String,
        letter: DeadLetter<out EventMessage<*>>
    ): String = eventProcessingConfig
        .sequencingPolicy(processingGroup)
        .getSequenceIdentifierFor(letter.message())
        ?.let {
            if (it is String) it else it.hashCode().toString()
        }
        ?: letter.message().identifier

    fun totalDeadLetters(processingGroup: String): Int {
        if (dlqMode == AxoniqConsoleDlqMode.NONE) {
            return 0
        }
        return dlqFor(processingGroup).deadLetters().sumOf { it.asIterable().count() }
    }

    fun sequenceSize(
        processingGroup: String,
        sequenceIdentifier: String
    ) = dlqFor(processingGroup).sequenceSize(sequenceIdentifier)

    fun delete(
        processingGroup: String,
        sequenceIdentifier: String,
    ) {
        val dlq = dlqFor(processingGroup)
        dlq.deadLetterSequence(sequenceIdentifier)
            .forEach { dlq.evict(it) }
    }

    fun delete(
        processingGroup: String,
        sequenceIdentifier: String,
        messageIdentifier: String
    ) {
        val dlq = dlqFor(processingGroup)
        dlq.deadLetterSequence(sequenceIdentifier)
            .first { it.message().identifier == messageIdentifier }
            .let { dlq.evict(it) }
    }

    private fun dlqFor(processingGroup: String): SequencedDeadLetterQueue<EventMessage<*>> =
        eventProcessingConfig
            .deadLetterQueue(processingGroup)
            .orElseThrow {
                IllegalArgumentException(
                    "There's no dead-letter queue configured for Processing Group [$processingGroup]!"
                )
            }

    fun process(
        processingGroup: String,
        messageIdentifier: String
    ): Boolean {
        return executor.submit(Callable {
            letterProcessorFor(processingGroup).process {
                it.message().identifier.hashIfNeeded() == messageIdentifier
            }
        }).get(60, TimeUnit.SECONDS)
    }

    private fun String.hashIfNeeded(): String {
        return if (dlqMode == AxoniqConsoleDlqMode.MASKED) {
            DigestUtils.sha256Hex(this)
        } else {
            this
        }
    }

    private fun letterProcessorFor(processingGroup: String) =
        eventProcessingConfig
            .sequencedDeadLetterProcessor(processingGroup)
            .orElseThrow {
                IllegalArgumentException(
                    "There's no dead-letter queue configured for Processing Group [$processingGroup]!"
                )
            }

    private fun MetaData.filtered() = this.subset(*dlqDiagnosticsWhitelist.toTypedArray())
}
