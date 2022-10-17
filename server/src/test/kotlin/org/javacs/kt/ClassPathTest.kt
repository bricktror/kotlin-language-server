package org.javacs.kt

import org.hamcrest.Matchers.*
import org.hamcrest.MatcherAssert.assertThat
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

import java.nio.file.Files

import org.javacs.kt.classpath.*

@Disabled
class ClassPathTest {

    @Test fun `find gradle classpath`() {
        val workspaceRoot = testResourcesRoot().resolve("additionalWorkspace")
        val buildFile = workspaceRoot.resolve("build.gradle")

        assertTrue(Files.exists(buildFile))

        val resolvers = defaultClassPathResolver(listOf(workspaceRoot))
        val classPath = resolvers.classpath.map { it.toString() }

        assertThat(classPath, hasItem(containsString("junit")))
    }

    @Test fun `find maven classpath`() {
        val workspaceRoot = testResourcesRoot().resolve("mavenWorkspace")
        val buildFile = workspaceRoot.resolve("pom.xml")

        assertTrue(Files.exists(buildFile))

        val resolvers = defaultClassPathResolver(listOf(workspaceRoot))
        val classPath = resolvers.classpath.map { it.toString() }

        assertThat(classPath, hasItem(containsString("junit")))
    }

    @Test fun `find kotlin stdlib`() {
        assertNotNull(findKotlinStdlib())
    }
}
