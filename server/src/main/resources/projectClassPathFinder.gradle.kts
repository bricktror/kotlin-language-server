import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer

allprojects {
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
            /* val kotlinExtension = project.extensions.findByName("kotlin") */
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
