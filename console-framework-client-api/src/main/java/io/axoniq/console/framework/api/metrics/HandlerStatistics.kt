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

package io.axoniq.console.framework.api.metrics

import com.fasterxml.jackson.annotation.JsonProperty

data class HandlerStatistics(
    @JsonProperty("c")
    val count: Double,
    @JsonProperty("f")
    val failed: Double,
    @JsonProperty("t")
    val timer: StatisticDistribution?,
    @JsonProperty("m")
    val metrics: Map<String, StatisticDistribution>,
)
