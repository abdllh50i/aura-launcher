// BRouter 1.7.10 routing engine (MIT, github.com/abrensch/brouter): the util, codec, expressions, mapaccess and core
// modules, unmodified, as one plain Java library. AMRI Maps uses it to route without internet (see README.md here).
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:-deprecation")
}
