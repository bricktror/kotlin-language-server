package org.javacs.kt

import org.hamcrest.Matchers.*
import org.hamcrest.MatcherAssert.assertThat
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

import org.javacs.kt.classpath.MavenArtifact

class MavenArtifactParsingTest {
    @Test
    fun `parse maven artifacts`() {
        var asserts = listOf(
            "net.sf.json-lib:json-lib:jar:jdk15:2.4:compile"
            to MavenArtifact(
                group = "net.sf.json-lib",
                artifact = "json-lib",
                packaging = "jar",
                classifier = "jdk15",
                version = "2.4",
                scope = "compile",
                hasSource = false),

            "io.netty:netty-transport-native-epoll:jar:linux-x86_64:4.1.36.Final:compile"
            to MavenArtifact(
                group = "io.netty",
                artifact = "netty-transport-native-epoll",
                packaging = "jar",
                classifier = "linux-x86_64",
                version = "4.1.36.Final",
                scope = "compile",
                hasSource = false
            ),

            "org.codehaus.mojo:my-project:1.0"
            to MavenArtifact(
                group = "org.codehaus.mojo",
                artifact = "my-project",
                packaging = null,
                classifier = null,
                version = "1.0",
                scope = null,
                hasSource = false
            ),

            "io.vertx:vertx-sql-client:test-jar:tests:3.8.0-SNAPSHOT:compile"
            to MavenArtifact(
                group = "io.vertx",
                artifact = "vertx-sql-client",
                packaging = "test-jar",
                classifier = "tests",
                version = "3.8.0-SNAPSHOT",
                scope = "compile",
                hasSource = false
            ),
                )

        asserts.forEach { (raw, parsed) ->
            assertThat(MavenArtifact.fromMvnDependencyList(raw), equalTo(parsed))
        }
    }

    @Test
    fun `parse maven sources`() {
        assertThat(MavenArtifact.fromMvnDependencySources("org.springframework.boot:spring-boot-starter:jar:sources:2.4.5"), equalTo(MavenArtifact(
            group = "org.springframework.boot",
            artifact = "spring-boot-starter",
            packaging = "jar",
            classifier = null,
            version = "2.4.5",
            scope = null,
            hasSource = true
        )))
    }
}
