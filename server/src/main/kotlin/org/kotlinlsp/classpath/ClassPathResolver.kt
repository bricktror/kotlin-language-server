package org.kotlinlsp.classpath

import java.nio.file.Path

/** A source for creating class paths */
interface ClassPathResolver {
    val classpath: Set<ClassPathEntry> // may throw exceptions

    val buildScriptClasspath: Set<ClassPathEntry>
        get() = emptySet<ClassPathEntry>()

    companion object {
        /** A default empty classpath implementation */
        val empty = object : ClassPathResolver {
            override val classpath = emptySet<ClassPathEntry>()
        }
    }
}

data class ClassPathEntry(
    val compiledJar: Path,
    val sourceJar: Path? = null
)

fun Sequence<ClassPathResolver>.flatten()
    = fold(ClassPathResolver.empty) { accum, next -> accum + next }

/** Combines two classpath resolvers. */
operator fun ClassPathResolver.plus(other: ClassPathResolver): ClassPathResolver
    = CompositeClassPathResolver(this, other) { a,b -> a + b }

/** Uses the left-hand classpath if not empty, otherwise uses the right. */
fun ClassPathResolver.or(other: ClassPathResolver): ClassPathResolver
    = CompositeClassPathResolver(this, other) { a,b -> a.takeIf{it.isNotEmpty()} ?: b}

class DecoratedClassPathResolver(
    val inner: ClassPathResolver,
    val op: (Set<ClassPathEntry>) -> Set<ClassPathEntry>
): ClassPathResolver {
    override val classpath get() = op(inner.classpath)
    override val buildScriptClasspath get() = op(inner.buildScriptClasspath)
}

class CompositeClassPathResolver(
    val lhs: ClassPathResolver,
    val rhs: ClassPathResolver,
    val join: (Set<ClassPathEntry>, Set<ClassPathEntry>) -> Set<ClassPathEntry>
): ClassPathResolver {
    override val classpath get() = join(lhs.classpath, rhs.classpath)
    override val buildScriptClasspath get() = join(lhs.buildScriptClasspath, rhs.buildScriptClasspath)
}
