rootProject.name = "CoCache"

include(":cocache-bom")
include(":cocache-dependencies")
include(":cocache-core")
include(":cocache-spring-redis")
include(":cocache-spring-boot-starter")
include(":cocache-example")

buildscript {
    repositories {
        gradlePluginPortal()
    }
    dependencies {
        classpath("me.champeau.jmh:jmh-gradle-plugin:0.6.6")
        classpath("io.github.gradle-nexus:publish-plugin:1.1.0")
        classpath("com.github.spotbugs.snom:spotbugs-gradle-plugin:5.0.9")
    }
}

