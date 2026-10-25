package com.viswa2k.smsforwarder.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Contents of a settings file, ready to replace the current rules and destinations. */
data class Imported(
    val rules: List<Rule>,
    val destinations: List<Destination>,
    val deviceName: String,
    /** The bot token in plain text, only when the file carries one. */
    val token: String?,
    /** Missing in older files: off / OTP only. */
    val whatsAppEnabled: Boolean = false,
    val whatsAppTypes: Set<MessageType> = SourceFilter.DEFAULT_WHATSAPP_TYPES,
)

/**
 * Settings file (spec 5.9): `{"version":2, deviceName, destinations, rules, token?}`. Ids in the file
 * only link rules to destinations; the importer assigns fresh ids. The bot token is written only when
 * the caller passes one (the user ticked "include bot token").
 */
object SettingsExport {
    const val VERSION = 2

    fun toJson(rules: List<Rule>, destinations: List<Destination>, deviceName: String, token: String?,
        whatsAppEnabled: Boolean = false,
        whatsAppTypes: Set<MessageType> = SourceFilter.DEFAULT_WHATSAPP_TYPES,
    ): String {
        val o = JSONObject()
        o.put("version", VERSION)
        o.put("deviceName", deviceName)
        o.put("destinations", JSONArray().also { a -> destinations.forEach { a.put(destination(it)) } })
        o.put("rules", JSONArray().also { a -> rules.forEach { a.put(rule(it)) } })
        o.put("whatsAppEnabled", whatsAppEnabled)
        o.put("whatsAppTypes", JSONArray().also { a -> whatsAppTypes.sortedBy { it.code }.forEach { a.put(it.code) } })
        if (token != null) o.put("token", token)
        return o.toString(2)
    }

    /** Throws [IllegalArgumentException] (user-readable message) for anything that is not a v2 settings file. */
    fun fromJson(json: String): Imported {
        try {
            val o = JSONObject(json)
            val version = o.optInt("version", -1)
            require(version == VERSION) {
                if (version == -1) "Not a settings file" else "Unsupported settings file (version $version)"
            }
            val dests = o.getJSONArray("destinations").objects().map(::parseDestination)
            val known = dests.map { it.id }.toSet()
            val rules = o.getJSONArray("rules").objects().map { parseRule(it, known) }
            val token = if (o.has("token") && !o.isNull("token")) o.getString("token").trim().ifEmpty { null } else null
            val waTypes = o.optJSONArray("whatsAppTypes")?.let { a -> SourceFilter.fromCodes((0 until a.length()).map { a.get(it).toString() }) }
                ?: SourceFilter.DEFAULT_WHATSAPP_TYPES
            return Imported(rules, dests, o.optString("deviceName", ""), token, o.optBoolean("whatsAppEnabled", false), waTypes)
        } catch (e: JSONException) {
            throw IllegalArgumentException("Not a valid settings file")
        }
    }

    /**
     * SIM ids are per phone: keeps an SMS destination's [Destination.simSubscriptionId] only when it is one
     * of this phone's [activeSubscriptionIds] (empty when unknown), else falls back to the default SIM.
     * Returns the destinations and how many SIM choices were reset.
     */
    fun localizeSims(destinations: List<Destination>, activeSubscriptionIds: Set<Int>): Pair<List<Destination>, Int> {
        var reset = 0
        val out = destinations.map { d ->
            val sim = d.simSubscriptionId
            if (sim != null && sim !in activeSubscriptionIds) {
                reset++
                d.copy(simSubscriptionId = null)
            } else d
        }
        return out to reset
    }

    private fun destination(d: Destination) = JSONObject().apply {
        put("id", d.id)
        put("channel", d.channel.code)
        put("label", d.label)
        put("address", d.address)
        d.topicId?.let { put("topicId", it) }
        d.simSubscriptionId?.let { put("simSubscriptionId", it) }
    }

    private fun rule(r: Rule) = JSONObject().apply {
        put("id", r.id)
        put("name", r.name)
        put("enabled", r.enabled)
        put("allMessages", r.allMessages)
        put("types", JSONArray().also { a -> r.types.sortedBy { it.code }.forEach { a.put(it.code) } })
        put("sender", r.sender.code)
        put("destinationIds", JSONArray().also { a -> r.destinationIds.forEach { a.put(it) } })
        put("style", r.style.code)
        r.customTemplate?.let { put("customTemplate", it) }
        put("position", r.position)
    }

    private fun parseDestination(o: JSONObject) = Destination(
        id = o.getLong("id"),
        channel = code(Channel.entries, o.getInt("channel")) { it.code },
        label = o.getString("label"),
        address = o.getString("address"),
        topicId = if (o.has("topicId") && !o.isNull("topicId")) o.getLong("topicId") else null,
        simSubscriptionId = if (o.has("simSubscriptionId") && !o.isNull("simSubscriptionId")) o.getInt("simSubscriptionId") else null,
    )

    private fun parseRule(o: JSONObject, knownDestinations: Set<Long>): Rule {
        val types = o.getJSONArray("types").let { a -> (0 until a.length()).map { a.getInt(it) } }
            .map { c -> MessageType.fromCode(c) ?: throw IllegalArgumentException("Unknown message type $c") }
            .filter { it != MessageType.SPAM }
            .toSet()
        val ids = o.getJSONArray("destinationIds").let { a -> (0 until a.length()).map { a.getLong(it) } }
        return Rule(
            id = o.getLong("id"),
            name = o.getString("name"),
            enabled = o.getBoolean("enabled"),
            allMessages = o.getBoolean("allMessages"),
            types = types,
            sender = code(SenderCondition.entries, o.getInt("sender")) { it.code },
            destinationIds = ids.filter { it in knownDestinations }.distinct(),
            style = code(Style.entries, o.getInt("style")) { it.code },
            customTemplate = if (o.has("customTemplate") && !o.isNull("customTemplate")) o.getString("customTemplate") else null,
            position = o.getInt("position"),
        )
    }

    private fun <T> code(values: List<T>, c: Int, codeOf: (T) -> Int): T =
        values.firstOrNull { codeOf(it) == c } ?: throw IllegalArgumentException("Unknown value $c in settings file")

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
}
