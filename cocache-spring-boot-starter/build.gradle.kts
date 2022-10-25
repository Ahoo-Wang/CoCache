plugins {
    kotlin("plugin.spring") version "1.7.20"
    kotlin("kapt")
}
dependencies {
    api(project(":cocache-spring-redis"))
    api("org.springframework.boot:spring-boot-starter")
    kapt("org.springframework.boot:spring-boot-configuration-processor:${rootProject.ext.get("springBootVersion")}")
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
