plugins {
    kotlin("plugin.spring") version "1.8.10"
    kotlin("kapt")
}
dependencies {
    kapt(platform(project(":cocache-dependencies")))
    api(project(":cocache-spring-redis"))
    api("org.springframework.boot:spring-boot-starter")
    kapt("org.springframework.boot:spring-boot-configuration-processor")
    kapt("org.springframework.boot:spring-boot-autoconfigure-processor")
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
