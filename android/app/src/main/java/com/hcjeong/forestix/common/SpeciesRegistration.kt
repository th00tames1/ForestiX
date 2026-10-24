package com.hcjeong.forestix.common

import com.hcjeong.forestix.data.cruise.SpeciesConfig
import kotlinx.serialization.json.*
import java.util.Locale
import java.util.UUID

data class SpeciesRecord(val code: String, val commonName: String, val scientificName: String = "") {
    constructor(config: SpeciesConfig) : this(config.code, config.commonName, config.scientificName)
    fun matches(query: String): Boolean = query.trim().let { q ->
        q.isEmpty() || listOf(commonName, scientificName, code).any { it.contains(q, ignoreCase = true) }
    }
    // No equation binding: a registered name never invents volume coefficients.
    fun unconfigured() = SpeciesConfig(code, commonName, scientificName, "", 0f, 0f, 0f, Float.MAX_VALUE, 0f, Float.MAX_VALUE)
    fun json() = buildJsonObject { put("code", code); put("commonName", commonName); put("scientificName", scientificName) }
}

object SpeciesRegistration {
    const val INVALID_NAME = "Enter a common name (up to 120 characters) and an optional scientific name (up to 160 characters)."
    const val INVALID_ARCHIVE = "This is not a supported species catalogue."
    const val CONFLICT = "A species code already has a different name. Nothing has been overwritten."
    internal fun valid(name: String, scientific: String) = name.isNotEmpty() &&
        name.codePointCount(0, name.length) <= 120 && scientific.codePointCount(0, scientific.length) <= 160 &&
        name.none { it.isISOControl() } && scientific.none { it.isISOControl() }

    fun make(commonName: String, scientificName: String, existing: List<SpeciesRecord>, id: UUID = UUID.randomUUID()): SpeciesRecord {
        val name = commonName.trim(); val scientific = scientificName.trim()
        require(valid(name, scientific)) { INVALID_NAME }
        require(existing.none { it.commonName.equals(name, ignoreCase = true) ||
            (scientific.isNotEmpty() && it.scientificName.equals(scientific, ignoreCase = true)) }) {
            "This species is already listed. Select the existing entry."
        }
        val code = "USR-" + id.toString().uppercase(Locale.US)
        require(existing.none { it.code == code }) { CONFLICT }
        return SpeciesRecord(code, name, scientific)
    }
}

data class SpeciesCatalogArchive(val schemaVersion: Int = 1, val species: List<SpeciesRecord>) {
    fun encode(): ByteArray = buildJsonObject {
        put("schemaVersion", schemaVersion); put("species", JsonArray(species.map { it.json() }))
    }.toString().toByteArray(Charsets.UTF_8)
    companion object {
        fun decode(data: ByteArray): SpeciesCatalogArchive {
            require(data.size <= 8 * 1024 * 1024) { SpeciesRegistration.INVALID_ARCHIVE }
            val root = Json.parseToJsonElement(data.toString(Charsets.UTF_8)).jsonObject
            val version = requireNotNull(root["schemaVersion"]?.jsonPrimitive?.intOrNull) { SpeciesRegistration.INVALID_ARCHIVE }
            val entries = requireNotNull(root["species"] as? JsonArray) { SpeciesRegistration.INVALID_ARCHIVE }
            require(entries.size <= 10000) { SpeciesRegistration.INVALID_ARCHIVE }
            val records = entries.map { entry ->
                val o = entry.jsonObject
                fun text(key: String): String {
                    val p = requireNotNull(o[key] as? JsonPrimitive) { SpeciesRegistration.INVALID_ARCHIVE }
                    require(p.isString) { SpeciesRegistration.INVALID_ARCHIVE }; return p.content
                }
                SpeciesRecord(text("code"), text("commonName"), text("scientificName"))
            }
            return SpeciesCatalogArchive(version, records)
        }
    }
    fun additions(existing: List<SpeciesRecord>): List<SpeciesRecord> {
        require(schemaVersion == 1 && species.size <= 10000) { SpeciesRegistration.INVALID_ARCHIVE }
        val byCode = existing.associateBy { it.code }.toMutableMap()
        val result = mutableListOf<SpeciesRecord>()
        for (s in species) {
            val validId = runCatching { UUID.fromString(s.code.removePrefix("USR-")).toString().uppercase(Locale.US) == s.code.removePrefix("USR-") }.getOrDefault(false)
            require(s.code.startsWith("USR-") && validId &&
                SpeciesRegistration.valid(s.commonName, s.scientificName) &&
                s.commonName == s.commonName.trim() && s.scientificName == s.scientificName.trim()) { SpeciesRegistration.INVALID_ARCHIVE }
            val old = byCode[s.code]
            require(old == null || old == s) { SpeciesRegistration.CONFLICT }
            if (old == null) { result += s; byCode[s.code] = s }
        }
        return result
    }
}

object SpeciesNameLookup {
    @Volatile private var records: Map<String, SpeciesRecord> = emptyMap()
    fun replace(species: List<SpeciesRecord>) { records = species.associateBy { it.code.uppercase(Locale.US) } }
    fun record(code: String): SpeciesRecord? = records[code.trim().uppercase(Locale.US)]
}
