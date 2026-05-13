package edu.oregonstate.forestrix

import edu.oregonstate.forestrix.export.CsvExporter
import edu.oregonstate.forestrix.models.DbhMethod
import edu.oregonstate.forestrix.models.Tree
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class CsvExporterTest {
    @Test
    fun treeCsvQuotesFieldsAndKeepsIosCoreColumns() {
        val tree = Tree(
            id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            plotId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
            treeNumber = 7,
            speciesCode = "DF",
            dbhCm = 42.25f,
            dbhMethod = DbhMethod.RAW_DEPTH_CHORD_SILHOUETTE,
            notes = "forked, check"
        )

        val csv = CsvExporter.treesCsv(listOf(tree))

        assertTrue(csv.startsWith("id,plot_id,tree_number,species_code,status,dbh_cm"))
        assertTrue(csv.contains("7,DF,live,42.25,rawDepthChordSilhouette"))
        assertTrue(csv.contains("\"forked, check\""))
        assertTrue(csv.endsWith("\r\n"))
    }
}
