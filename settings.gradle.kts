rootProject.name = "CoCache"

include(":cocache-bom")
include(":cocache-dependencies")
include(":cocache-core")
include(":cocache-spring-redis")
include(":cocache-spring-boot-starter")
include(":cocache-test")
include(":cocache-example")

include(":code-coverage-report")

pluginManagement {
    plugins {
        id("io.gitlab.arturbosch.detekt") version "1.22.0" apply false
        kotlin("jvm") version "1.8.20" apply false
        kotlin("plugin.spring") version "1.8.20" apply false
        id("org.jetbrains.dokka") version "1.8.10" apply false
        id("io.github.gradle-nexus.publish-plugin") version "1.3.0" apply false
    }
}
