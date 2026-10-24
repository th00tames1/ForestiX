package com.hcjeong.forestix.data

import com.hcjeong.forestix.common.*
import com.hcjeong.forestix.data.cruise.SpeciesConfig
import com.hcjeong.forestix.data.cruise.SpeciesConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SpeciesCatalogState(val records: List<SpeciesRecord> = emptyList(), val loadError: String? = null)

class SpeciesCatalogStore(private val repository: SpeciesConfigRepository, private val bundled: List<SpeciesConfig> = emptyList()) {
    private val mutableState = MutableStateFlow(SpeciesCatalogState())
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()

    private fun publish(saved: List<SpeciesConfig>) {
        val all = linkedMapOf<String, SpeciesRecord>()
        Region.entries.filter { it != Region.ALL }.forEach { region ->
            RegionalSpecies.defaultSpecies(region).forEach { (code, name) -> all.putIfAbsent(code, SpeciesRecord(code, name)) }
        }
        Country.entries.forEach { country -> CountrySpecies.defaultSpecies(country).forEach { (code, name) -> all[code] = SpeciesRecord(code, name) } }
        (bundled + saved).forEach { all[it.code] = SpeciesRecord(it) }
        all["OT"] = SpeciesRecord("OT", "Other")
        val records = all.values.sortedWith(compareBy<SpeciesRecord> { it.commonName.lowercase(java.util.Locale.ROOT) }.thenBy { it.code })
        SpeciesNameLookup.replace(records)
        mutableState.value = SpeciesCatalogState(records)
    }
    suspend fun refresh() = withContext(Dispatchers.IO) { mutex.withLock {
        try { publish(repository.list()) }
        catch (e: Exception) { mutableState.value = mutableState.value.copy(loadError = "Unable to load saved species. Try again before adding a species.") }
    } }
    fun options(country: Country, region: Region?): List<SpeciesRecord> {
        val records = state.value.records
        if (country == Country.INTERNATIONAL || (country == Country.UNITED_STATES && (region == null || region == Region.ALL))) return records
        val byCode = records.associateBy { it.code }
        val presets = (if (country.hasRegions) RegionalSpecies.defaultSpecies(region ?: Region.ALL) else CountrySpecies.defaultSpecies(country)).map { (code, name) ->
            SpeciesRecord(code, name, byCode[code]?.takeIf { it.commonName == name }?.scientificName ?: "")
        }
        return presets + records.filter { it.code.startsWith("USR-") || it.code == "OT" }
    }
    suspend fun add(commonName: String, scientificName: String): SpeciesRecord = withContext(Dispatchers.IO) { mutex.withLock {
        publish(repository.list())
        val record = SpeciesRegistration.make(commonName, scientificName, state.value.records)
        repository.create(record.unconfigured())
        publish(repository.list())
        record
    } }
    suspend fun archiveData(): ByteArray = withContext(Dispatchers.IO) { mutex.withLock {
        SpeciesCatalogArchive(species = repository.list().filter { it.code.startsWith("USR-") }.map(::SpeciesRecord)).encode()
    } }
    suspend fun restore(data: ByteArray): Int = withContext(Dispatchers.IO) { mutex.withLock {
        require(data.size <= 8 * 1024 * 1024) { SpeciesRegistration.INVALID_ARCHIVE }
        val archive = SpeciesCatalogArchive.decode(data)
        val additions = archive.additions(repository.list().map(::SpeciesRecord))
        additions.forEach { repository.create(it.unconfigured()) }
        publish(repository.list())
        additions.size
    } }
}
