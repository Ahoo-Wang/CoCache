dependencies {
    api(project(":cocache-core"))
    api("com.fasterxml.jackson.core:jackson-databind")
    api("org.springframework.data:spring-data-redis")
    testImplementation("org.springframework.boot:spring-boot-autoconfigure")
    testImplementation("io.lettuce:lettuce-core")
    testImplementation("me.ahoo.cosid:cosid-core")
    testImplementation("me.ahoo.cosid:cosid-test")
}
