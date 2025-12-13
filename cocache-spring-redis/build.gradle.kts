dependencies {
    api(project(":cocache-core"))
    api(project(":cocache-spring"))
    api("tools.jackson.core:jackson-databind")
    api("tools.jackson.module:jackson-module-kotlin")
    api("org.springframework.data:spring-data-redis")
    testImplementation(project(":cocache-test"))
    testImplementation("org.springframework.boot:spring-boot-autoconfigure")
    testImplementation("io.lettuce:lettuce-core")
}
