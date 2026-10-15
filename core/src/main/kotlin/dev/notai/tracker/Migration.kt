package dev.notai.tracker

import kotlinx.serialization.json.*

/** Only the old flat link is known. Preserve it as a one-node historical path, never infer a new parent. */
fun migrateV1Payload(json: String): String = migrateV1(codec.parseToJsonElement(json)).toString()
private fun migrateV1(element: JsonElement): JsonElement = when(element) {
    is JsonArray -> JsonArray(element.map(::migrateV1))
    is JsonObject -> {
        val fields = element.mapValues { (key,value) ->
            if (key in setOf("details","tags","settings")) value else migrateV1(value)
        }.toMutableMap()
        if ("partOf" in fields) {
            ensure("activityTypeId" !in fields,"Conflicting legacy classification")
            val type = fields.remove("partOf")!!; fields["activityTypeId"] = type
            // Cards have their own ID; embedded classifications do not.
            if ("id" !in fields) fields["ancestorPathAtWrite"] = JsonArray(if(type is JsonNull) emptyList() else listOf(type))
        }
        if ("fieldId" in fields && "scope" in fields && "value" in fields) {
            val number = fields.getValue("value").jsonPrimitive
            ensure(!number.isString && number.longOrNull != null && number.long >= 0,"Invalid legacy integer measurement")
            fields["value"] = JsonPrimitive(number.long.toString())
        }
        if ("datasetId" in fields) {
            for (name in listOf("axes","metrics","units")) {
                ensure(name !in fields,"Unexpected v2 dictionary in v1 data")
                fields[name] = JsonArray(emptyList())
            }
        }
        JsonObject(fields)
    }
    else -> element
}
internal fun decodeBackupVersion(json: String): Backup {
    val root = codec.parseToJsonElement(json).jsonObject
    val version = root["formatVersion"]?.jsonPrimitive?.intOrNull
    ensure(version in 8..9 || listOf("schedule","transfer").none { it in root.getValue("data").jsonObject },"Schedule in legacy archive")
    return when(root["formatVersion"]?.jsonPrimitive?.intOrNull) {
        8,9 -> {
            ensure(listOf("goals","focus","schedule","transfer").all { it in root.getValue("data").jsonObject },"Missing v8 backup fields")
            codec.decodeFromJsonElement<Backup>(root).also { ensure(version == 9 || !it.data.transfer.hasCompactionState(),"Compaction in v8 archive") }.copy(formatVersion = 9)
        }
        7 -> {
            ensure(listOf("goals","focus").all { it in root.getValue("data").jsonObject },"Missing v7 backup fields")
            codec.decodeFromJsonElement<Backup>(root).copy(formatVersion = 9)
        }
        6 -> {
            ensure("goals" in root.getValue("data").jsonObject,"Missing goals in v6 backup")
            codec.decodeFromJsonElement<Backup>(root).copy(formatVersion = 9)
        }
        5 -> codec.decodeFromJsonElement<Backup>(root).copy(formatVersion = 9)
        4 -> codec.decodeFromJsonElement<Backup>(root).copy(formatVersion = 9)
        3 -> codec.decodeFromJsonElement<Backup>(root).copy(formatVersion = 9)
        2 -> codec.decodeFromJsonElement<Backup>(root).copy(formatVersion = 9)
        1 -> {
            val migrated = migrateV1(root).jsonObject.toMutableMap()
            migrated["formatVersion"] = JsonPrimitive(9)
            codec.decodeFromJsonElement<Backup>(JsonObject(migrated))
        }
        else -> fail(ErrorCode.UNSUPPORTED_IMPORT,"Unsupported backup version")
    }
}
