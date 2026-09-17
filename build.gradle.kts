import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
    id("org.jetbrains.changelog") version "2.4.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        testFramework(TestFrameworkType.Platform)
    }

    implementation("org.xerial:sqlite-jdbc:3.50.3.0") {
        exclude(group = "org.slf4j")
    }
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())

    compilerOptions {
        // Keep the API level aligned with the Kotlin stdlib bundled in the IDE.
        apiVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3
        languageVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3
    }
}

java {
    sourceCompatibility = JavaVersion.toVersion(providers.gradleProperty("javaVersion").get())
    targetCompatibility = JavaVersion.toVersion(providers.gradleProperty("javaVersion").get())
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        // Feeds the "What's New" section of the plugin page with the entry of the released version.
        // `Changelog.Item.header` strips the brackets around the version (it becomes e.g.
        // "1.0.5 - 2026-09-17"), and the plugin exposes no separate `date` property, so the
        // "[version] - [date]" heading the plugin page should open with is built by hand: the date
        // is recovered from that same header text instead of relying on a dedicated accessor.
        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            with(changelog) {
                val item = getOrNull(pluginVersion) ?: getUnreleased()
                val date = Regex("\\d{4}-\\d{2}-\\d{2}").find(item.header)?.value
                val heading = "<h3>[${item.version}]${date?.let { " - $it" } ?: ""}</h3>"
                heading + renderItem(
                    item.withHeader(false).withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            recommended()
        }

        // The bridge to the GitHub Copilot plugin has to resolve that plugin's class loader, and
        // since 2026.2 every `PluginManager` accessor able to do so is annotated `@ApiStatus.Internal`
        // - there is no public replacement. Internal and deprecated API usages are therefore reported
        // but do not fail the build; they are warnings on the Marketplace and never block a release.
        // Everything that does affect users - real compatibility problems, a broken plugin structure,
        // missing dependencies, API scheduled for removal - still fails.
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
            VerifyPluginTask.FailureLevel.PLUGIN_STRUCTURE_WARNINGS,
            VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
            VerifyPluginTask.FailureLevel.SCHEDULED_FOR_REMOVAL_API_USAGES,
            VerifyPluginTask.FailureLevel.NON_EXTENDABLE_API_USAGES,
            VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
        )
    }

    // Marketplace credentials are never stored in the repository: both tasks read the environment,
    // and stay unconfigured (and therefore fail with an explicit message) when it is not set.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")

        // A pre-release version such as `1.1.0-beta.1` is published to the matching custom channel
        // (`beta`), so only the users who subscribed to it are offered the update.
        channels = providers.gradleProperty("pluginVersion").map { version ->
            listOf(version.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
    }
}

changelog {
    version = providers.gradleProperty("pluginVersion")
    groups = listOf("Added", "Changed", "Fixed", "Removed")
}

tasks {
    test {
        useJUnit()
    }
}
