import org.gradle.internal.classpath.ClassPath
import org.gradle.kotlin.dsl.accessors.AccessorsClassPath
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.internal.DynamicObjectAware
import org.gradle.api.NamedDomainObjectCollection
/* import org.gradle.api.artifacts.ConfigurationContainer.compileClasspath */


allprojects {
    /* apply(plugin ="application") */
/* apply(plugin="org.jetbrains.kotlin.jvm") */
/* project.getRepositories()?.let{it::class.java.getMethods()}?.forEach{println(it)} */
/* println(project.getRepositories().getAsMap()) */
/* project::class.java.getMethods().forEach{println(it)} */
        /* project.getPluginManager().apply("application") */
    tasks.register("print-env") {
        doLast {
            val p = project.getProperties()
            val x= listOf(p.get("gradleKotlinDsl.projectAccessorsClassPath"),
                          p.get("gradleKotlinDsl.pluginAccessorsClassPath"))
                     .filterIsInstance<AccessorsClassPath>()
                     .flatMap { x -> x.bin.getAsFiles() }
            val searchPaths= listOf(
                "${gradle.gradleHomeDir}/lib",
                "${gradle.gradleUserHomeDir}/caches/${gradle.gradleVersion}/generated-gradle-jars",
                "${gradle.gradleUserHomeDir}/caches/modules-2/files-2.1") + x
            searchPaths.forEach{println(it)}
            /* val config=mutableMapOf( */
            /*     "gradle-home" to gradle.gradleHomeDir, */
            /*     "gradle-user-home" to gradle.gradleUserHomeDir, */
            /*     /1* "gradle-version" to gradle.gradleVersion, *1/ */
            /*     "x" to x, */
            /*     /1* "gradleKotlinDsl.projectAccessorsClassPath" to p.get("gradleKotlinDsl.projectAccessorsClassPath"), *1/ */
            /*     /1* "gradleKotlinDsl.pluginAccessorsClassPath" to p.get("gradleKotlinDsl.pluginAccessorsClassPath"), *1/ */
            /* ) */
            /* config.forEach{ println(it)} */

/* gradle.pluginManager.apply("application") */
/* gradle.pluginManager.apply("kotlin-dsl") */
/* println(gradle.plugins) */
/* println(gradle.pluginManager) */
/* println(config) */
/* println(p["dependencies"]) */
/* println(p["extensions"]) */
/* println(p["sourceSets"]) */
/* p["kotlin"]?.let{it::class.java.getMethods()}?.forEach{println(it)} */

/* val dynKotlin = p["kotlin"] */
/*     ?.let{it as DynamicObjectAware} */
/*     ?.asDynamicObject */
/* /1* println(dynKotlin) *1/ */
/* val sourceSet= dynKotlin?.getProperty("sourceSets") as NamedDomainObjectCollection<Any?>? */
/* sourceSet */
/*     ?.let{it.getAsMap()} */
/*     ?.let{it["main"]} */
/*     ?.let{it::class.java} */
/*     ?.let{it.getMethods()} */
/*     /1* ?.first() *1/ */
/*     /1* ?.let{it.javaType} *1/ */
/*     ?.forEach{println(it)} */

/* println(dynKotlin?.tryGetProperty("targets")?.let{it::class.java}) */

            /*     kotlinExtension.targets.names.each { */
            /*         def classpath = configurations["${it}CompileClasspath"] */
            /*         classpath.files.each { */
            /*             System.out.println("kotlin-lsp-gradle $it") */
            /*         } */
            /*     } */

/* println(p["application"]) */
/* println(p["properties"]) */
/* val test = p["asDynamicObject"] as org.gradle.internal.extensibility.ExtensibleDynamicObject */
/* test?.also{ it::class.java.getMethods().forEach { println(it) } } */
/* test.getProperties().forEach { println("$it".take(80)) } */

            /* val ext = project.getExtensions() */
            /*         .findByName("kotlin") */
/* project::class.java.getMethods().forEach{println(it)} */

/* project.getPlugins().forEach{println(it)} */
/* var extt=project.getExtensions() as org.gradle.internal.extensibility.DefaultConvention */
/* println(extt.getAsMap()) */


        }
    }

    tasks.register("kotlinLSPKotlinDSLDeps") {
        doLast {

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
                .forEach { println("kotlin-lsp-gradle $it") }
        }
    }

    tasks.register("kotlinLSPProjectDeps") {
        doLast {
            println("")
            println("gradle-version ${gradleVersion}")
            println("kotlin-lsp-project ${project.name}")

            /* if (project.hasProperty("android")) { */
            /*     project.android.getBootClasspath().each { */
            /*         System.out.println "kotlin-lsp-gradle $it" */
            /*     } */

            /*     def variants = [] */

            /*     if (project.android.hasProperty("applicationVariants")) { */
            /*         variants += project.android.applicationVariants */
            /*     } */

            /*     if (project.android.hasProperty("libraryVariants")) { */
            /*         variants += project.android.libraryVariants */
            /*     } */

            /*     variants.each { variant -> */
            /*         def variantBase = variant.baseName.replaceAll("-", File.separator) */

            /*         def buildClasses = project.getBuildDir().absolutePath + */
            /*             File.separator + "intermediates" + */
            /*             File.separator + variantBase + */
            /*             File.separator + "classes" */

            /*         System.out.println "kotlin-lsp-gradle $buildClasses" */

            /*         def userClasses = project.getBuildDir().absolutePath + */
            /*             File.separator + "intermediates" + */
            /*             File.separator + "javac" + */
            /*             File.separator + variantBase + */
            /*             File.separator + "compile" + variantBase.capitalize() + "JavaWithJavac" + File.separator + "classes" */

            /*         System.out.println "kotlin-lsp-gradle $userClasses" */

            /*         def userVariantClasses = project.getBuildDir().absolutePath + */
            /*             File.separator + "intermediates" + */
            /*             File.separator + "javac" + */
            /*             File.separator + variantBase + */
            /*             File.separator + "classes" */

            /*         System.out.println "kotlin-lsp-gradle $userVariantClasses" */

            /*         variant.getCompileClasspath().each { */
            /*             System.out.println "kotlin-lsp-gradle $it" */
            /*         } */
            /*     } */
            /* } else */

            val sourceSets: SourceSetContainer? by project
            sourceSets
                ?.iterator()
                ?.asSequence()
                ?.flatMap { x -> x.compileClasspath }
                ?.forEach { println("kotlin-lsp-gradle ${it}") }


            // handle kotlin multiplatform style dependencies if any
            val ext = project.getExtensions()
                    .findByName("kotlin")
                    /* ?.let { if (it is org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension_Decorated) it else null } */
                    /* ?.let { if (it is org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension) it else null } */
                    /* ?.let { it.sourceSets } */
                    ?.also {
                        println(it)
                        println("${it::class.java}")
it::class.java.getMethods().forEach{ println(it)}
                    }
            /* if(kotlinExtension && kotlinExtension.hasProperty("targets")) { */
            /*     val kotlinSourceSets = kotlinExtension.sourceSets */

            /*     // Print the list of all dependencies jar files. */
            /*     kotlinExtension.targets.names.each { */
            /*         def classpath = configurations["${it}CompileClasspath"] */
            /*         classpath.files.each { */
            /*             System.out.println("kotlin-lsp-gradle $it") */
            /*         } */
            /*     } */
            /* } */
        }
    }

    tasks.register("kotlinLSPAllGradleDeps") {
        doLast {
            fileTree("${gradle.gradleHomeDir}/lib") { include("*.jar") }
                .forEach { println("kotlin-lsp-gradle $it") }
        }
    }
}
