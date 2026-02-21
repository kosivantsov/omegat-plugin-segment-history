plugins {
    java
    distribution
    id("org.omegat.gradle") version "1.5.7"
}

group = "org.truetranslation"
version = "0.1.0"

repositories {
    mavenCentral()
}

omegat {
    version = "6.0.0"
    pluginClass = "org.truetranslation.omegat.plugin.SegmentHistoryPlugin"
}

dependencies {
    implementation("org.omegat:omegat:6.0.0")
}

distributions {
    main {
        contents {
            from(tasks["jar"])
            from("README.md")
            from("COPYING")
            into("src/main/resources") {
                from("src/main/resources")
            }
        }
    }
}
