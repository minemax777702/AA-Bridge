buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        // AGP's built-in Kotlin defaults to an older KGP. CVE-2026-53914
        // is fixed starting with 2.4.20-Beta1; use a stable patched release.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")

        // Align Netty's transitives without introducing Netty into the APK.
        classpath(platform("io.netty:netty-bom:4.1.141.Final"))

        // Minimum patched versions for transitive build-tool dependencies
        // reported by OSV. Constraints do not add otherwise-unused libraries.
        constraints {
            add("classpath", "com.google.protobuf:protobuf-java:3.25.8")
            add("classpath", "com.google.guava:guava:33.7.2-jre")
            add("classpath", "org.apache.commons:commons-compress:1.28.0")
            add("classpath", "commons-io:commons-io:2.21.0")
            add("classpath", "org.bouncycastle:bcprov-jdk18on:1.87")
            add("classpath", "org.bouncycastle:bcpkix-jdk18on:1.87")
            add("classpath", "org.bouncycastle:bcutil-jdk18on:1.87")
            add("classpath", "org.bitbucket.b_c:jose4j:0.9.6")
            add("classpath", "org.jdom:jdom2:2.0.6.1")
        }
    }
}

plugins {
    id("com.android.application") version "9.2.1" apply false
    id("com.android.library") version "9.2.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
