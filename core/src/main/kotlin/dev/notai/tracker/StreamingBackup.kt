package dev.notai.tracker

import java.io.InputStream
import java.io.OutputStream
import java.io.Reader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.security.DigestInputStream
import java.security.MessageDigest
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.*

/** Portable JSON 7 without a whole-file String/ByteArray/JSON tree. Only one record is decoded
 * at a time; the resulting domain graph has the same size as a loaded store. Total file size
 * is not capped by CSV's text limit. Re-openable inputs permit a bounded metadata first pass.
 */
object StreamingBackup {
    fun portable(data: Dataset,now: Moment): Backup {
        validateMoment(now);validate(data)
        return Backup(asOf = now.wall,data = data.copy(segments = data.segments.map { materialize(it,now).copy(anchor = null) },
            focus = Focus.freeze(data,now)))
    }
    fun write(backup: Backup,output: OutputStream) {
        validateBackup(backup)
        val writer = OutputStreamWriter(output,Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)).buffered(64*1024)
        fun field(name: String,value: String,first: Boolean = false) {
            if(!first) writer.write(",")
            writer.write(codec.encodeToString(name));writer.write(":");writer.write(value)
        }
        fun <T> rows(name: String,values: List<T>,encode: (T)->String) {
            writer.write(",");writer.write(codec.encodeToString(name));writer.write(":[")
            values.forEachIndexed { i,row -> if(i != 0) writer.write(",");writer.write(encode(row)) }
            writer.write("]")
        }
        writer.write("{");field("formatVersion","7",true);field("backupId",codec.encodeToString(backup.backupId));field("asOf",backup.asOf.toString())
        writer.write(",\"data\":{");val d = backup.data
        field("datasetId",codec.encodeToString(d.datasetId),true);field("revision",d.revision.toString())
        field("settings",codec.encodeToString(d.settings));field("focus",codec.encodeToString(d.focus))
        field("catalogVersion",codec.encodeToString(d.catalogVersion))
        field("timerEpoch",d.timerEpoch.toString());field("controlBarrier",codec.encodeToString(d.controlBarrier))
        rows("types",d.types) { codec.encodeToString(it) };rows("axes",d.axes) { codec.encodeToString(it) }
        rows("metrics",d.metrics) { codec.encodeToString(it) };rows("units",d.units) { codec.encodeToString(it) }
        rows("cards",d.cards) { codec.encodeToString(it) };rows("favorites",d.favorites) { codec.encodeToString(it) }
        rows("sessions",d.sessions) { codec.encodeToString(it) };rows("segments",d.segments) { codec.encodeToString(it) }
        rows("measurements",d.measurements) { codec.encodeToString(it) };rows("goals",d.goals) { codec.encodeToString(it) }
        writer.write("}}");writer.flush() // caller owns the stream and durability
    }
    fun read(open: ()->InputStream): Backup = try {
        var version: Int? = null;var id: String? = null;var asOf: Long? = null;var hasData = false
        val firstDigest = MessageDigest.getInstance("SHA-256")
        reader(DigestInputStream(open(),firstDigest)).use { input ->
            val json = JsonRecords(input)
            json.members { key -> when(key) {
                "formatVersion" -> version = codec.decodeFromString(json.value())
                "backupId" -> id = codec.decodeFromString(json.value())
                "asOf" -> asOf = codec.decodeFromString(json.value())
                "data" -> { hasData = true;json.skip() }
                else -> fail(ErrorCode.UNSUPPORTED_IMPORT,"Unknown backup field: $key")
            } };json.finish()
        }
        ensure(version in 1..7 && !id.isNullOrBlank() && asOf != null && hasData,"Unsupported backup metadata",ErrorCode.UNSUPPORTED_IMPORT)
        val format = version!!
        val types = mutableListOf<ActivityType>();val axes = mutableListOf<Axis>();val metrics = mutableListOf<Metric>();val units = mutableListOf<UnitDefinition>()
        val cards = mutableListOf<Card>();val favorites = mutableListOf<Favorite>();val sessions = mutableListOf<Session>();val segments = mutableListOf<Segment>()
        val measurements = mutableListOf<Measurement>();val goals = mutableListOf<Goal>()
        // Freeze/intern each decoded selection before retaining its row. A large
        // restore must not keep thousands of identical mutable JSON collections
        // until the final graph validation, especially alongside the live store.
        val selections = SelectionFreezer()
        var datasetId: String? = null;var revision: Long? = null;var settings: Settings? = null;var focus: FocusRun? = null;var catalogVersion: String? = null
        var timerEpoch = 0L;var controlBarrier = ""
        val seen = mutableSetOf<String>()
        val secondDigest = MessageDigest.getInstance("SHA-256")
        reader(DigestInputStream(open(),secondDigest)).use { input ->
            val json = JsonRecords(input)
            fun record(): String { val raw = json.value();return if(format == 1) migrateV1Payload(raw) else raw }
            json.members { key -> if(key != "data") json.skip() else json.members { field ->
                seen += field
                when(field) {
                    "datasetId" -> datasetId = codec.decodeFromString(json.value())
                    "revision" -> revision = codec.decodeFromString(json.value())
                    "settings" -> settings = codec.decodeFromString(json.value())
                    "focus" -> focus = codec.decodeFromString(json.value())
                    "catalogVersion" -> catalogVersion = codec.decodeFromString(json.value())
                    "timerEpoch" -> timerEpoch = codec.decodeFromString(json.value())
                    "controlBarrier" -> controlBarrier = codec.decodeFromString(json.value())
                    "types" -> json.array { types += codec.decodeFromString<ActivityType>(record()) }
                    "axes" -> json.array { axes += codec.decodeFromString<Axis>(record()) }
                    "metrics" -> json.array { metrics += codec.decodeFromString<Metric>(record()) }
                    "units" -> json.array { units += codec.decodeFromString<UnitDefinition>(record()) }
                    "cards" -> json.array { cards += codec.decodeFromString<Card>(record()) }
                    "favorites" -> json.array { val row = codec.decodeFromString<Favorite>(record());favorites += row.copy(selection = selections.capture(row.selection)) }
                    "sessions" -> json.array { val row = codec.decodeFromString<Session>(record());sessions += row.copy(selection = selections.capture(row.selection)) }
                    "segments" -> json.array { val row = codec.decodeFromString<Segment>(record());segments += row.copy(selection = selections.capture(row.selection)) }
                    "measurements" -> json.array { measurements += codec.decodeFromString<Measurement>(record()) }
                    "goals" -> json.array { goals += codec.decodeFromString<Goal>(record()) }
                    else -> fail(ErrorCode.UNSUPPORTED_IMPORT,"Unknown dataset field: $field")
                }
            } };json.finish()
        }
        ensure(firstDigest.digest().contentEquals(secondDigest.digest()),"Backup changed while reading",ErrorCode.UNSUPPORTED_IMPORT)
        ensure(seen.containsAll(listOf("datasetId","revision","settings","types","cards","favorites","sessions","segments","measurements")),"Missing dataset fields",ErrorCode.UNSUPPORTED_IMPORT)
        ensure(if(format == 1) seen.none { it in setOf("axes","metrics","units") } else seen.containsAll(listOf("axes","metrics","units")),"Missing or incompatible dictionary fields",ErrorCode.UNSUPPORTED_IMPORT)
        ensure(format < 6 || "goals" in seen,"Missing goals",ErrorCode.UNSUPPORTED_IMPORT)
        ensure(format < 7 || "focus" in seen,"Missing Focus",ErrorCode.UNSUPPORTED_IMPORT)
        val data = Dataset(datasetId = datasetId!!,revision = revision!!,types = types,cards = cards,favorites = favorites,
            sessions = sessions,segments = segments,measurements = measurements,settings = settings!!,axes = axes,metrics = metrics,units = units,
            goals = goals,focus = focus,catalogVersion = catalogVersion,timerEpoch = timerEpoch,controlBarrier = controlBarrier)
        Backup(formatVersion = 7,backupId = id!!,asOf = asOf!!,data = data.frozen()).also(::validateBackup)
    } catch(e: CoreError) { if(e.code == ErrorCode.UNSUPPORTED_IMPORT) throw e else fail(ErrorCode.UNSUPPORTED_IMPORT,"Invalid backup: ${e.message}") }
      catch(_: Exception) { fail(ErrorCode.UNSUPPORTED_IMPORT,"Malformed or unreadable backup") }
    private fun reader(input: InputStream): Reader = InputStreamReader(input,Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)).buffered(64*1024)
    internal fun validateBackup(b: Backup) {
        ensure(b.formatVersion == 7 && b.backupId.isNotBlank() && b.asOf in 0..MAX_TIME,"Invalid backup metadata",ErrorCode.UNSUPPORTED_IMPORT)
        validate(b.data)
        ensure(b.data.focus?.breakAnchor == null && b.data.segments.all { it.anchor == null },"Backup contains device anchors",ErrorCode.UNSUPPORTED_IMPORT)
        ensure(b.data.segments.all { (it.start <= b.asOf || it.uncertain) && (it.end == null || it.end <= b.asOf || it.uncertain) &&
            (it.observedUntil == null || it.observedUntil <= b.asOf || it.uncertain) },"Time after snapshot",ErrorCode.UNSUPPORTED_IMPORT)
    }
}

/** Structural framing only. Each value is validated by the existing strict serializer; source
 * dictionaries/classifications are never rewritten or guessed. skip() doesn't allocate the value.
 */
private class JsonRecords(private val reader: Reader,private val validateValues: Boolean = true) {
    private var held = -2
    init { if(peek() == 0xFEFF) take() }
    private fun peek(): Int { if(held == -2) held = reader.read();return held }
    private fun take(): Int { val c = peek();held = -2;return c }
    private fun whitespace() { while(peek() in listOf(9,10,13,32)) take() }
    private fun expect(c: Char) { whitespace();ensure(take() == c.code,"Invalid JSON framing",ErrorCode.UNSUPPORTED_IMPORT) }
    fun members(read: (String)->Unit) {
        expect('{');whitespace();val keys = mutableSetOf<String>()
        if(peek() == '}'.code) { take();return }
        while(true) {
            val name = codec.decodeFromString<String>(value());ensure(keys.add(name),"Duplicate JSON field",ErrorCode.UNSUPPORTED_IMPORT)
            expect(':');read(name);whitespace()
            if(peek() == '}'.code) { take();return }
            expect(',')
        }
    }
    fun array(read: ()->Unit) {
        expect('[');whitespace();if(peek() == ']'.code) { take();return }
        while(true) { read();whitespace();if(peek() == ']'.code) { take();return };expect(',') }
    }
    fun value(): String = consume(true)!!.also { raw ->
        if(validateValues) {
            val check = JsonRecords(java.io.StringReader(raw),false)
            check.inspect(0);check.finish()
        }
    }
    private fun inspect(depth: Int) {
        ensure(depth <= 128,"JSON nesting too deep",ErrorCode.UNSUPPORTED_IMPORT)
        whitespace()
        when(peek()) {
            '{'.code -> members { inspect(depth+1) }
            '['.code -> array { inspect(depth+1) }
            else -> skip()
        }
    }
    fun skip() { consume(false) }
    private fun consume(retain: Boolean): String? {
        whitespace();ensure(peek() >= 0,"Truncated JSON",ErrorCode.UNSUPPORTED_IMPORT)
        val out = if(retain) StringBuilder() else null;var depth = 0;var quoted = false;var escaped = false;var started = false
        while(true) {
            val c = peek()
            if(c < 0) { ensure(started && depth == 0 && !quoted,"Truncated JSON value",ErrorCode.UNSUPPORTED_IMPORT);break }
            if(started && depth == 0 && !quoted && (c in listOf(','.code,'}'.code,']'.code,9,10,13,32))) break
            take();out?.append(c.toChar());started = true
            ensure(out == null || out.length <= 16*1024*1024,"Single JSON record exceeds 16 MiB characters",ErrorCode.UNSUPPORTED_IMPORT)
            if(quoted) { if(escaped) escaped = false else if(c == '\\'.code) escaped = true else if(c == '"'.code) quoted = false }
            else when(c) {
                '"'.code -> quoted = true
                '{'.code,'['.code -> { depth++;ensure(depth <= 128,"JSON nesting too deep",ErrorCode.UNSUPPORTED_IMPORT) }
                '}'.code,']'.code -> { depth--;ensure(depth >= 0,"Invalid JSON value",ErrorCode.UNSUPPORTED_IMPORT) }
            }
            if(!quoted && depth == 0 && (c == '"'.code || c == '}'.code || c == ']'.code)) break
        }
        return out?.toString()
    }
    fun finish() { whitespace();ensure(peek() == -1,"Trailing content after backup",ErrorCode.UNSUPPORTED_IMPORT) }
}
