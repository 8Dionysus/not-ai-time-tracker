package dev.notai.tracker

import kotlinx.serialization.Serializable

@Serializable enum class QuantityRepresentation { NUMBER, FACET }
@Serializable data class QuantityVariant(val id: String,val meaning: String,val kind: QuantityRepresentation,
    val dimension: String,val integer: Boolean)
@Serializable data class QuantityProjection(val pointer: String,val sourceFile: String,val sourceId: String,
    val variants: List<QuantityVariant>,val remainingGap: String,val definition: String)
@Serializable internal data class QuantityProjectionFile(val packageSha256: String,val rows: List<QuantityProjection>)

internal object ResearchQuantities {
    val policy: QuantityProjectionFile by lazy {
        val text = ResearchQuantities::class.java.getResourceAsStream("/research-quantity-projections.json")
            ?.bufferedReader()?.use { it.readText() } ?: fail(ErrorCode.STORAGE,"Quantity policy missing")
        codec.decodeFromString<QuantityProjectionFile>(text)
    }
}
