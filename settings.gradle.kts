plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
rootProject.name = "CoCache"

include(":cocache-bom")
include(":cocache-dependencies")
include(":cocache-api")
include(":cocache-core")
include("cocache-spring")
include(":cocache-spring-redis")
include(":cocache-spring-boot-starter")
include(":cocache-test")
include(":cocache-example")
include(":code-coverage-report")
