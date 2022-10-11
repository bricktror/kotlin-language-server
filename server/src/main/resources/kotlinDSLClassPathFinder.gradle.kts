import org.gradle.internal.classpath.ClassPath
import org.gradle.kotlin.dsl.accessors.AccessorsClassPath

allprojects {
    tasks.register("kotlinLSPKotlinDSLDeps") {
        doLast {
            val p = project.getProperties()
            (fileTree("${gradle.gradleHomeDir}/lib") { include("*.jar") }
                 + fileTree("${gradle.gradleUserHomeDir}/caches/$gradle.gradleVersion/generated-gradle-jars") { include("*.jar") }
                 + fileTree("${gradle.gradleUserHomeDir}/caches/modules-2/files-2.1") { include("*.jar") }
                 + listOf(p.get("gradleKotlinDsl.projectAccessorsClassPath"),
                          p.get("gradleKotlinDsl.pluginAccessorsClassPath"))
                     .filterIsInstance<AccessorsClassPath>()
                     .flatMap { x -> x.bin.getAsFiles() })
                .map { x -> x.getAbsoluteFile() }
                .distinct()
                .forEach { println("kotlin-lsp-gradle $it") }
        }
    }
}
