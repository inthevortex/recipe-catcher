package org.eu.angsumanray.recipecatcher.data.repo

import org.eu.angsumanray.recipecatcher.core.llm.LlmUsage
import org.eu.angsumanray.recipecatcher.data.db.entity.LlmUsageEntity

/** Maps an extraction call's usage to its spend-panel row; [fallbackModel] covers responses without a model. */
fun LlmUsage.toExtractionUsageEntity(fallbackModel: String) = LlmUsageEntity(
    purpose = LlmUsageEntity.PURPOSE_EXTRACTION,
    model = model ?: fallbackModel,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    cacheReadTokens = cacheReadTokens,
    webSearches = webSearches
)
