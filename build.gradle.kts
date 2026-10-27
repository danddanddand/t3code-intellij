import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("java")
    alias(libs.plugins.kotlin)
    alias(libs.plugins.intelliJPlatform)
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))
        bundledPlugins(
            providers.gradleProperty("platformBundledPlugins")
                .map { it.split(',').map(String::trim).filter(String::isNotEmpty) }
        )
    }
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"
            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n")
            }
        }

        changeNotes = providers.fileContents(layout.projectDirectory.file("CHANGELOG.md")).asText.map { text ->
            // Latest released section of CHANGELOG.md, as HTML paragraphs/list items.
            val section = text.split(Regex("^## ", RegexOption.MULTILINE)).drop(1)
                .firstOrNull { !it.startsWith("[Unreleased]") }.orEmpty()
            section.lines().drop(1).filter { it.isNotBlank() }.joinToString("\n") { line ->
                when {
                    line.startsWith("### ") -> "<p><b>${line.removePrefix("### ")}</b></p>"
                    line.startsWith("- ") -> "<li>${line.removePrefix("- ")}</li>"
                    else -> line
                }
            }.let { "<ul>$it</ul>" }
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }

    // ./gradlew publishPlugin with PUBLISH_TOKEN set (JetBrains Marketplace, My Tokens).
    // The first version of a plugin must be uploaded through the Marketplace website; later ones can use this.
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // Tag releases as 0.1.0-beta to publish to the beta channel instead of stable.
        channels = providers.gradleProperty("pluginVersion").map {
            listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2026.2")
        }
    }
}

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }
}

// Sandbox IDE for sandbox/run-sandbox.sh: JCEF diagnostics forced on, tool window auto-opened, isolated T3 server.
intellijPlatformTesting {
    runIde {
        register("runSandboxIde") {
            task {
                val outDir = layout.projectDirectory.dir("sandbox/out").asFile
                val t3Url = providers.gradleProperty("t3SandboxUrl").getOrElse("http://127.0.0.1:3799/")
                // -Pt3SandboxInProcess=true runs JCEF inside the IDE process instead of cef_server (JBR-10027 workaround).
                val inProcess = providers.gradleProperty("t3SandboxInProcess").getOrElse("false").toBoolean()
                args(layout.projectDirectory.dir("sandbox/project").asFile.absolutePath)
                jvmArgumentProviders += CommandLineArgumentProvider {
                    listOf(
                        "-Djcef.log.level=verbose",
                        "-Djcef.log.path=${outDir.resolve("jcef.log")}",
                        "-Djcef_debug=true",
                        "-DCEF_SERVER_LOG_LEVEL=5",
                        "-Dt3code.sandbox.autoOpen=true",
                        "-Dt3code.sandbox.url=$t3Url",
                        "-Dide.mac.message.dialogs.as.sheets=false",
                        "-Djb.privacy.policy.text=<!--999.999-->",
                        "-Djb.consents.confirmation.enabled=false",
                        "-Dide.show.tips.on.startup.default.value=false",
                        "-Didea.trust.all.projects=true",
                    ) + if (inProcess) listOf("-Djcef.remote.enabled=false") else emptyList()
                }
            }
        }
    }
}
