package edu.oregonstate.forestrix.models

object SpeciesCatalog {
    val all: List<SpeciesConfig> = listOf(
        SpeciesConfig(
            code = "DF",
            commonName = "Douglas-fir",
            scientificName = "Pseudotsuga menziesii",
            merchTopDibCm = 10f,
            stumpHeightCm = 30f,
            expectedDbhMinCm = 5f,
            expectedDbhMaxCm = 200f,
            expectedHeightMinM = 2f,
            expectedHeightMaxM = 80f
        ),
        SpeciesConfig(
            code = "WH",
            commonName = "Western hemlock",
            scientificName = "Tsuga heterophylla",
            merchTopDibCm = 10f,
            stumpHeightCm = 30f,
            expectedDbhMinCm = 5f,
            expectedDbhMaxCm = 180f,
            expectedHeightMinM = 2f,
            expectedHeightMaxM = 70f
        ),
        SpeciesConfig(
            code = "RC",
            commonName = "Western redcedar",
            scientificName = "Thuja plicata",
            merchTopDibCm = 10f,
            stumpHeightCm = 30f,
            expectedDbhMinCm = 5f,
            expectedDbhMaxCm = 300f,
            expectedHeightMinM = 2f,
            expectedHeightMaxM = 70f
        ),
        SpeciesConfig(
            code = "RA",
            commonName = "Red alder",
            scientificName = "Alnus rubra",
            merchTopDibCm = 10f,
            stumpHeightCm = 30f,
            expectedDbhMinCm = 5f,
            expectedDbhMaxCm = 100f,
            expectedHeightMinM = 2f,
            expectedHeightMaxM = 35f
        )
    )

    val byCode: Map<String, SpeciesConfig> = all.associateBy { it.code }

    fun displayName(code: String): String =
        byCode[code]?.let { "${it.code} - ${it.commonName}" } ?: code

    fun recentCodes(trees: List<Tree>, limit: Int = 5): List<String> {
        if (limit <= 0) return emptyList()
        val counts = trees
            .filter { it.deletedAtMillis == null }
            .groupingBy { it.speciesCode }
            .eachCount()
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { it.key }
    }
}
