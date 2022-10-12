plugins {
    id("bzet.kotlin-application-conventions")
    application
}

val kotlinVersion: String by extra
val lsp4jVersion: String by extra
val exposedVersion: String by extra
val projectVersion: String by extra
val slf4jVersion: String by extra

application {
    applicationName="kotlin-language-server"
    mainClass.set("org.javacs.kt.MainKt")
    applicationDefaultJvmArgs = listOf("-DkotlinLanguageServer.version=$projectVersion")
}

repositories {
    maven(url=uri("$projectDir/lib"))
    maven(url="https://jitpack.io")
}

dependencies {
    implementation("org.slf4j:slf4j-api:$slf4jVersion")
    implementation("org.slf4j:slf4j-simple:$slf4jVersion")
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:$lsp4jVersion")
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:$lsp4jVersion")
    implementation("org.jetbrains.kotlin:kotlin-compiler:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-scripting-compiler:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host-unshaded:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-sam-with-receiver-compiler-plugin:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")

    implementation("org.jetbrains:fernflower:1.0")
    implementation("com.h2database:h2:1.4.200")
    implementation("com.github.fwcd.ktfmt:ktfmt:b5d31d1")
    implementation("com.beust:jcommander:1.78")

    testImplementation("org.hamcrest:hamcrest-all:1.3")
    testImplementation("junit:junit:4.11")
    testImplementation("org.openjdk.jmh:jmh-core:1.20")

    compileOnly("org.jetbrains.kotlin:kotlin-scripting-jvm-host:$kotlinVersion")
    testCompileOnly("org.jetbrains.kotlin:kotlin-scripting-jvm-host:$kotlinVersion")

    annotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.20")
}
