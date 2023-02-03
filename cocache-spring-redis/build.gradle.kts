dependencies {
    api(project(":cocache-core"))
    api("com.fasterxml.jackson.core:jackson-databind")
    api("org.springframework.data:spring-data-redis")
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    testImplementation(project(":cocache-test"))
    testImplementation("org.springframework.boot:spring-boot-autoconfigure")
    testImplementation("io.lettuce:lettuce-core")
}
