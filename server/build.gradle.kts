import Versions

plugins {
    id("bzet.kotlin-application-conventions")
    application
}

application {
    applicationName="kotlin-language-server"
    mainClass.set("org.kotlinlsp.MainKt")
    applicationDefaultJvmArgs = listOf("-DkotlinLanguageServer.version=${Versions.project}")
}

repositories {
    maven(url=uri("$projectDir/lib"))
    maven(url="https://jitpack.io")
}

dependencies {
    implementation("io.arrow-kt:arrow-core:1.1.3")
    implementation("org.slf4j:slf4j-api:${Versions.slf4j}")
    implementation("org.slf4j:slf4j-simple:${Versions.slf4j}")
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:${Versions.lsp4j}")
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:${Versions.lsp4j}")
    implementation("org.jetbrains.kotlin:kotlin-compiler:${Versions.kotlin}")
    implementation("org.jetbrains.kotlin:kotlin-scripting-compiler:${Versions.kotlin}")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host-unshaded:${Versions.kotlin}")
    implementation("org.jetbrains.kotlin:kotlin-sam-with-receiver-compiler-plugin:${Versions.kotlin}")
    implementation("org.jetbrains.kotlin:kotlin-reflect:${Versions.kotlin}")
    implementation("org.jetbrains.exposed:exposed-core:${Versions.exposed}")
    implementation("org.jetbrains.exposed:exposed-dao:${Versions.exposed}")
    implementation("org.jetbrains.exposed:exposed-jdbc:${Versions.exposed}")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${Versions.coroutines}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:${Versions.coroutines}")

    implementation("org.jetbrains:fernflower:1.0")
    implementation("com.h2database:h2:1.4.200")
    implementation("com.github.fwcd.ktfmt:ktfmt:b5d31d1")
    implementation("com.beust:jcommander:1.78")

    testImplementation("org.openjdk.jmh:jmh-core:1.20")

    compileOnly("org.jetbrains.kotlin:kotlin-scripting-jvm-host:${Versions.kotlin}")
    testCompileOnly("org.jetbrains.kotlin:kotlin-scripting-jvm-host:${Versions.kotlin}")

    annotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.20")
}

