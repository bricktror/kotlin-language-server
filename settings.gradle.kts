pluginManagement {
    repositories {
        gradlePluginPortal()
        maven(url="https://cache-redirector.jetbrains.com/kotlin.bintray.com/kotlin-plugin")
    }
}

rootProject.name = "kotlin-language-server"

include(
    "server",
    /* "grammars" */
)
