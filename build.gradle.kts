plugins {
    id("java")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "me.pompompopi"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("net.dv8tion:JDA:5.0.0-beta.20") {
        exclude(module = "opus-java")
    }
    implementation("ch.qos.logback:logback-classic:1.4.14")
    implementation("org.postgresql:postgresql:42.7.2")
}

tasks {
    assemble {
        dependsOn(shadowJar)
    }

    jar {
        manifest {
            attributes["Main-Class"] = "me.pompompopi.star2.Star2"
        }
    }
}
