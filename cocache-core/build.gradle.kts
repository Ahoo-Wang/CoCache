dependencies {
    api("me.ahoo.cosid:cosid-core")
    implementation(kotlin("reflect"))
    implementation("com.google.guava:guava")
    implementation("org.springframework:spring-expression")
    testImplementation(project(":cocache-test"))
}
