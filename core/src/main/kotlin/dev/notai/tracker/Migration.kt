package dev.notai.tracker

import kotlinx.serialization.json.*

/** The very first v1 exporter (2664dd9) had no type table. Its built-in
 * template IDs and labels are the only type definitions present in that release.
 * Do not invent a definition for a custom partOf value: an archive that needs
 * one cannot be restored without more information. */
internal fun originalV1Types(data: Dataset): List<ActivityType> {
    val referenced = (data.cards.mapNotNull { it.activityTypeId } +
        data.favorites.mapNotNull { it.selection.activityTypeId } +
        data.sessions.mapNotNull { it.selection.activityTypeId } +
        data.segments.mapNotNull { it.selection.activityTypeId }).toSet()
    val builtins = listOf(ActivityType("language","Языковая практика"),ActivityType("fitness","Тренировка"),
        ActivityType("reading","Чтение"),ActivityType("work","Работа"),ActivityType("rest","Отдых"))
    ensure(referenced.all { id -> builtins.any { it.id == id } },
        "Legacy backup references an activity type without a definition",ErrorCode.UNSUPPORTED_IMPORT)
    return builtins.filter { it.id in referenced }
}

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
    val version = root["formatVersion"]?.jsonPrimitive?.intOrNull ?: fail(ErrorCode.UNSUPPORTED_IMPORT,"Missing backup version")
    ensure(version in 8..CURRENT_BACKUP_FORMAT || listOf("schedule","transfer").none { it in root.getValue("data").jsonObject },"Schedule in legacy archive")
    ensure(version >= 14 || root.getValue("data").jsonObject.keys.none { it in setOf("subjects","savedReports") },
        "New domain fields in legacy archive",ErrorCode.UNSUPPORTED_IMPORT)
    val backup = when(version) {
        8,9,10,11,12,13,14,15,16 -> {
            ensure(listOf("goals","focus","schedule","transfer").all { it in root.getValue("data").jsonObject },"Missing v8 backup fields")
            codec.decodeFromJsonElement<Backup>(root).also { ensure(version != 8 || !it.data.transfer.hasCompactionState(),"Compaction in v8 archive") }
        }
        7 -> {
            ensure(listOf("goals","focus").all { it in root.getValue("data").jsonObject },"Missing v7 backup fields")
            codec.decodeFromJsonElement<Backup>(root)
        }
        6 -> {
            ensure("goals" in root.getValue("data").jsonObject,"Missing goals in v6 backup")
            codec.decodeFromJsonElement<Backup>(root)
        }
        5 -> codec.decodeFromJsonElement<Backup>(root)
        4 -> codec.decodeFromJsonElement<Backup>(root)
        3 -> codec.decodeFromJsonElement<Backup>(root)
        2 -> codec.decodeFromJsonElement<Backup>(root)
        1 -> {
            val missingTypes = "types" !in root.getValue("data").jsonObject
            val migrated = migrateV1(root).jsonObject.toMutableMap()
            migrated["formatVersion"] = JsonPrimitive(CURRENT_BACKUP_FORMAT)
            if(missingTypes) {
                val data = migrated.getValue("data").jsonObject.toMutableMap()
                data["types"] = JsonArray(emptyList())
                migrated["data"] = JsonObject(data)
            }
            val decoded = codec.decodeFromJsonElement<Backup>(JsonObject(migrated))
            if(missingTypes) decoded.copy(data = decoded.data.copy(types = originalV1Types(decoded.data))) else decoded
        }
        else -> fail(ErrorCode.UNSUPPORTED_IMPORT,"Unsupported backup version")
    }
    ensure(version in 10..CURRENT_BACKUP_FORMAT || (backup.data.settings.schedule == ScheduleSettings() && !backup.data.transfer.hasCatalogRoots()),"Functional settings/catalog roots in legacy archive")
    ensure(version in 11..CURRENT_BACKUP_FORMAT || backup.data.settings.surfaces.shortcut,"Shortcut preference in legacy archive")
    ensure(version in 12..CURRENT_BACKUP_FORMAT || !backup.data.hasManualTimeConfirmation(),"Manual confirmation in legacy archive",ErrorCode.UNSUPPORTED_IMPORT)
    ensure(version in 13..CURRENT_BACKUP_FORMAT || backup.data.settings.longRecordReminder == LongRecordPreference(),"Ordinary reminder preference in legacy archive",ErrorCode.UNSUPPORTED_IMPORT)
    ensure(version >= 14 || !backup.data.hasChoicePersonalization(),"Personal choices in legacy archive",ErrorCode.UNSUPPORTED_IMPORT)
    ensure(version >= 14 || !backup.data.hasReportingExtensions(),"Reporting extensions in legacy archive",ErrorCode.UNSUPPORTED_IMPORT)
    ensure(version >= 15 || !backup.data.settings.surfaces.hasPresentationPreferences(),"Surface presentation preferences in legacy archive",ErrorCode.UNSUPPORTED_IMPORT)
    ensure(version >= 16 || "deletedRecords" !in root.getValue("data").jsonObject,"Deleted records in legacy archive",ErrorCode.UNSUPPORTED_IMPORT)
    ensure(version >= 14 || !backup.data.hasSubjectQuantities(),"Quantity extension in legacy archive",ErrorCode.UNSUPPORTED_IMPORT)
    return backup.copy(formatVersion = CURRENT_BACKUP_FORMAT)
}
