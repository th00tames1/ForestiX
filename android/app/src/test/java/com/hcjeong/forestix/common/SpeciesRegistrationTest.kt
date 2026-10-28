package com.hcjeong.forestix.common

import com.hcjeong.forestix.data.*
import com.hcjeong.forestix.data.cruise.SpeciesConfig
import com.hcjeong.forestix.data.cruise.SpeciesConfigRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.zip.ZipInputStream

private class SpeciesTestRepository : SpeciesConfigRepository {
    val values = mutableListOf<SpeciesConfig>()
    var failing = false
    override suspend fun create(s: SpeciesConfig): SpeciesConfig { values += s; return s }
    override suspend fun read(code: String) = values.firstOrNull { it.code == code }
    override suspend fun update(s: SpeciesConfig): SpeciesConfig { values.removeAll { it.code == s.code }; values += s; return s }
    override suspend fun delete(code: String) { values.removeAll { it.code == code } }
    override suspend fun list(): List<SpeciesConfig> { check(!failing) { "Synthetic read failure" }; return values.toList() }
    override fun observeList() = flowOf(values.toList())
}

class SpeciesRegistrationTest {
    @Test fun internationalBundleLeavesImplicitLogRuleAndBoardFeetBlank() {
        val plot = QuickMeasurePlot(name = "Synthetic plot", acres = 0.1)
        val dbh = QuickMeasureEntry(kind = MeasureKind.DBH, value = 50.0, confidenceRaw = "yellow", method = "synthetic", treeNumber = 1, plotID = plot.id)
        val height = dbh.copy(id = UUID.randomUUID(), kind = MeasureKind.HEIGHT, value = 20.0)
        fun calculations(include: Boolean): String {
            val bytes = QuickMeasureExport.buildBundleZip(listOf(dbh, height), listOf(plot), com.hcjeong.forestix.sensors.LogRule.SCRIBNER, include)
            ZipInputStream(bytes.inputStream()).use { input ->
                while (true) { val entry = input.nextEntry ?: break; val body = input.readBytes(); if (entry.name == "Calculations.csv") return body.toString(Charsets.UTF_8) }
            }
            error("Missing calculation table")
        }
        assertTrue(calculations(true).contains("\"scribner\""))
        val international = calculations(false)
        assertFalse(international.contains("\"scribner\"")); assertTrue(international.contains(",\"\",\"\",\"\"\r\n"))
        assertEquals(50.0, dbh.value, 0.0); assertEquals(20.0, height.value, 0.0)
    }
    private val id = UUID.fromString("12345678-1234-1234-1234-123456789abc")
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) {} }
    @Test fun registrationHasStableCodeAndNoEquation() {
        val s = SpeciesRegistration.make(" Test cedar ", " Testus cedrus ", emptyList(), id)
        assertEquals("USR-12345678-1234-1234-1234-123456789ABC", s.code)
        assertEquals("Test cedar", s.commonName); assertEquals("Testus cedrus", s.scientificName)
        assertEquals("", s.unconfigured().volumeEquationId)
        assertTrue(s.matches("CEDRUS")); assertTrue(s.matches("12345678"))
        assertEquals("", SpeciesRegistration.make("Optional", "", emptyList()).scientificName)
    }
    @Test fun invalidAndDuplicateNamesOrCodeAreRejected() {
        listOf(" ", "x".repeat(121), "Name\nother").forEach { name -> rejects { SpeciesRegistration.make(name, "", emptyList()) } }
        val s = SpeciesRegistration.make("Sample", "Example species", emptyList(), id)
        rejects { SpeciesRegistration.make(" sample ", "", listOf(s)) }
        rejects { SpeciesRegistration.make("Other", "EXAMPLE SPECIES", listOf(s)) }
        rejects { SpeciesRegistration.make("Other", "", listOf(s), id) }
    }
    @Test fun portableArchiveRoundTripAndConflicts() {
        val s = SpeciesRegistration.make("시험 수종, \"A\"", "Testus example", emptyList(), id)
        val archive = SpeciesCatalogArchive(species = listOf(s))
        val decoded = SpeciesCatalogArchive.decode(archive.encode())
        assertEquals(listOf(s), decoded.additions(emptyList()))
        assertTrue(decoded.additions(listOf(s)).isEmpty())
        rejects { archive.copy(schemaVersion = 2).additions(emptyList()) }
        rejects { SpeciesCatalogArchive(species = listOf(s.copy(commonName = "Wrong"))).additions(listOf(s)) }
        rejects { SpeciesCatalogArchive(species = listOf(SpeciesRecord("DF", "Replacement"))).additions(emptyList()) }
        // Exact Swift-compatible names-only wire format, not platform DB bytes.
        val wire = """{"schemaVersion":1,"species":[{"code":"${s.code}","commonName":"Synthetic cedar","scientificName":"Testus cedrus"}]}"""
        assertEquals("Synthetic cedar", SpeciesCatalogArchive.decode(wire.toByteArray()).additions(emptyList()).single().commonName)
    }
    @Test fun countryIdsAndInternationalDefaultsRemainCompatible() {
        assertEquals(listOf("us", "fi", "de", "kr", "international"), Country.entries.map { it.raw })
        assertEquals(Country.UNITED_STATES, Country.default)
        val c = Country.INTERNATIONAL
        assertEquals(UnitSystem.METRIC, c.defaultUnitSystem); assertFalse(c.usesLogRule); assertFalse(c.hasRegions)
        assertTrue(c.volumeStandardPending); assertEquals("No default volume equation", c.volumeStandardLabel)
    }
    @Test fun allSpeciesAndRegisteredOptionsPersistAcrossCountries() = runBlocking {
        val repo = SpeciesTestRepository(); val store = SpeciesCatalogStore(repo); store.refresh()
        assertTrue(store.options(Country.UNITED_STATES, Region.ALL).size > 30)
        assertEquals("Lodgepole pine", store.options(Country.UNITED_STATES, Region.PNW_EAST).first { it.code == "LP" }.commonName)
        assertEquals("Loblolly pine", store.options(Country.UNITED_STATES, Region.COASTAL_PLAIN).first { it.code == "LP" }.commonName)
        val s = store.add("Synthetic oak", "Testus oak")
        Country.entries.forEach { c -> assertTrue(store.options(c, Region.PNW_WEST).contains(s)) }
        assertEquals("Synthetic oak", RegionalSpecies.nameForCode(s.code))
        val reopened = SpeciesCatalogStore(repo); reopened.refresh()
        assertTrue(reopened.state.value.records.contains(s)); assertEquals("", repo.read(s.code)!!.volumeEquationId)
    }
    @Test fun standaloneBackupRestoreNeedsNoProjectAndNeverOverwrites() = runBlocking {
        val source = SpeciesCatalogStore(SpeciesTestRepository()); val s = source.add("Synthetic birch", "")
        val destination = SpeciesCatalogStore(SpeciesTestRepository())
        assertEquals(1, destination.restore(source.archiveData())); assertEquals(0, destination.restore(source.archiveData()))
        val conflict = SpeciesCatalogArchive(species = listOf(s.copy(commonName = "Wrong"))).encode()
        try { destination.restore(conflict); fail("Expected conflict") } catch (_: IllegalArgumentException) {}
        assertTrue(destination.state.value.records.contains(s))
    }
    @Test fun readFailureDoesNotPretendToRegisterOrExport() = runBlocking {
        val repo = SpeciesTestRepository(); repo.failing = true
        val store = SpeciesCatalogStore(repo); store.refresh(); assertNotNull(store.state.value.loadError)
        try { store.add("Lost", ""); fail("Expected failure") } catch (_: IllegalStateException) {}
        try { store.archiveData(); fail("Expected failure") } catch (_: IllegalStateException) {}
        assertTrue(repo.values.isEmpty())
    }
    @Test fun quickCSVAndBundleCarryNamesAndLeaveValuesUnchanged() = runBlocking {
        val store = SpeciesCatalogStore(SpeciesTestRepository()); val s = store.add("Synthetic \"oak\", A", "Testus alpha")
        val e = QuickMeasureEntry(kind = MeasureKind.DBH, value = 23.5, sigma = null, confidenceRaw = "yellow", method = "synthetic", treeNumber = 1, speciesCode = s.code)
        val csv = QuickMeasureExport.buildCsv(listOf(e), emptyList()).toString(Charsets.UTF_8)
        assertTrue(csv.contains("species_common_name")); assertTrue(csv.contains("Synthetic \"\"oak\"\", A"))
        assertTrue(csv.contains("Testus alpha")); assertTrue(csv.contains(s.code)); assertEquals(23.5, e.value, 0.0)
        val zip = QuickMeasureExport.buildBundleZip(listOf(e), emptyList(), com.hcjeong.forestix.sensors.LogRule.SCRIBNER)
        ZipInputStream(zip.inputStream()).use { input ->
            var trees: String? = null
            while (true) { val entry = input.nextEntry ?: break; val body = input.readBytes(); if (entry.name == "Trees.csv") trees = body.toString(Charsets.UTF_8) }
            assertTrue(trees!!.contains("Testus alpha"))
        }
    }
}
