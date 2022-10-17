package org.javacs.kt.classpath

import java.nio.file.Path

/** A classpath resolver that ensures another resolver contains the stdlib */
fun withUniqueStdlibs(inner: ClassPathResolver)
    = DecoratedClassPathResolver(inner) { entries ->
        wrapWithStdlibEntries(entries)
    }

private fun wrapWithStdlibEntries(entries: Set<ClassPathEntry>): Set<ClassPathEntry> {
    // Ensure that there is exactly one kotlin-stdlib present, and/or exactly one of kotlin-stdlib-common, -jdk8, etc.
    return entries
        .map { Pair(StdLibItem.from(it.compiledJar), it) }

        .partition { it.first != null }
        .let { (stdlibs, other) ->
            stdlibs
                .groupBy { it.first!!.key }
                .map { it.value }
                    // For each "kotlin-stdlib-blah", use the newest.
                    // This may not be correct behavior if the project has lots of
                .map { it.sortedWith(compareByDescending<Pair<StdLibItem?,*>> { it.first!! }) }
                .map{it.first()}
                .let{other.union(it)}
        }
        .map{it.second}
        .toSet()
}

private data class StdLibItem(
    val key : String,
    val major : Int,
    val minor: Int,
    val patch : Int,
): Comparable<StdLibItem> {
    companion object {
        // Matches names like: "kotlin-stdlib-jdk7-1.2.51.jar"
        /* val parser = """(kotlin-stdlib(?!-common)(-[^-]+)?)-(\d+)\.(\d+)\.(\d+)\.jar""".toRegex() */
        val parser = """(kotlin-stdlib(?!-common)(-[^-]+)?)-(\d+)\.(\d+)\.(\d+)\.jar""".toRegex()

        fun from(path: Path): StdLibItem?
            = parser
            .matchEntire(path.fileName.toString())
            ?.let { it.groups as? MatchNamedGroupCollection }
            ?.let { StdLibItem(
                    key = it[1]?.value ?: it[0]?.value!!,
                    major = it[3]?.value?.toInt() ?: 0,
                    minor = it[4]?.value?.toInt() ?: 0,
                    patch = it[5]?.value?.toInt() ?: 0,
            ) }
    }

    override fun compareTo(other: StdLibItem): Int = when {
        major != other.major -> major - other.major
        minor != other.minor -> minor - other.minor
        else -> patch - other.patch
    }
}
