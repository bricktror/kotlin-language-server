import org.gradle.kotlin.dsl.accessors.AccessorsClassPath
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.internal.DynamicObjectAware
import org.gradle.api.NamedDomainObjectCollection

// Log for info
println("gradle-version ${gradleVersion}")

allprojects {
    tasks.register("kotlin-lsp-deps") {
        doLast {
            val projectName = project.name
                .replace("\\", "\\\\") // Escape backslashes to enable to be able to..
                .replace(" ", "\\ ") // ..escape spaces
            fun report(key: String, value: String) =
                println("kotlin-lsp ${projectName} ${key} ${value}")

            val p = project.getProperties()
            (fileTree("${gradle.gradleHomeDir}/lib") { include("*.jar") }
                 + fileTree("${gradle.gradleUserHomeDir}/caches/${gradle.gradleVersion}/generated-gradle-jars") { include("*.jar") }
                 + fileTree("${gradle.gradleUserHomeDir}/caches/modules-2/files-2.1") { include("*.jar") }
                 + listOf(p.get("gradleKotlinDsl.projectAccessorsClassPath"),
                          p.get("gradleKotlinDsl.pluginAccessorsClassPath"))
                     .filterIsInstance<AccessorsClassPath>()
                     .flatMap { x -> x.bin.getAsFiles() })
                .map { x -> x.getAbsoluteFile() }
                .distinct()
                .forEach { report("build-dependency", it.toString()) }

            val sourceSets: SourceSetContainer? by project
            sourceSets
                ?.iterator()
                ?.asSequence()
                ?.flatMap { x -> x.compileClasspath }
                ?.forEach { report("dependency", it.toString()) }

            // handle kotlin multiplatform style dependencies if any
            /* val ext = project.getExtensions() */
            /*         .findByName("kotlin") */
            /*         ?.let { it as DynamicObjectAware } */
            /*         ?.asDynamicObject */
            /*         ?.getProperty("targets") */
            /* if(kotlinExtension && kotlinExtension.hasProperty("targets")) { */
            /*     val kotlinSourceSets = kotlinExtension.sourceSets */

            /*     // Print the list of all dependencies jar files. */
            /*     kotlinExtension.targets.names.each { */
            /*         def classpath = configurations["${it}CompileClasspath"] */
            /*         classpath.files.each { */
            /*             report("dependency", it) */
            /*         } */
            /*     } */
            /* } */
        }
    }
}
