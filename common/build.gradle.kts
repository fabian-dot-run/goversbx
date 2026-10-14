plugins {
    `java-library`
}

dependencies {
    api(libs.jackson.databind)
    api(libs.jackson.datatype.jsr310)

    testImplementation(libs.junit.jupiter)
}
