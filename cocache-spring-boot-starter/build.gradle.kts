dependencies {
    api(project(":cocache-spring-redis"))
    api("org.springframework.boot:spring-boot-starter")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:${rootProject.ext.get("springBootVersion")}")
    annotationProcessor("org.springframework.boot:spring-boot-autoconfigure-processor:${rootProject.ext.get("springBootVersion")}")
}
