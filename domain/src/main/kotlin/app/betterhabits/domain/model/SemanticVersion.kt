package app.betterhabits.domain.model

/**
 * MAJOR.MINOR.PATCH version as used by app releases and their `vX.Y.Z` git tags.
 * Build metadata/suffixes (e.g. "-debug") are ignored for comparison.
 * Used to decide whether a newer GitHub Release exists than the installed build.
 */
data class SemanticVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<SemanticVersion> {

    override fun compareTo(other: SemanticVersion): Int =
        compareValuesBy(this, other, SemanticVersion::major, SemanticVersion::minor, SemanticVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$""")

        /** Parses "1.2.3", "v1.2.3" or "1.2.3-debug"; returns null for anything else. */
        fun parse(text: String): SemanticVersion? {
            val match = PATTERN.matchEntire(text.trim()) ?: return null
            val (major, minor, patch) = match.destructured
            return SemanticVersion(major.toInt(), minor.toInt(), patch.toInt())
        }
    }
}
