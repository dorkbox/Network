/*
 * Copyright 2020 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import dorkbox.gradle.kotlin
import java.time.Instant

///////////////////////////////
//////    PUBLISH TO SONATYPE / MAVEN CENTRAL
////// TESTING : (to local maven repo) <'publish and release' - 'publishToMavenLocal'>
////// RELEASE : (to sonatype/maven central), <'publish and release' - 'publishToSonatypeAndRelease'>
///////////////////////////////

gradle.startParameter.showStacktrace = ShowStacktrace.ALWAYS   // always show the stacktrace!
gradle.startParameter.warningMode = WarningMode.All

plugins {
    java

    id("com.dorkbox.GradleUtils") version "1.12"
    id("com.dorkbox.Licensing") version "2.5.1"
    id("com.dorkbox.VersionUpdate") version "2.0"
    id("com.dorkbox.GradlePublish") version "1.8"
    id("com.dorkbox.GradleModuleInfo") version "1.1"

    kotlin("jvm") version "1.4.10"
}

object Extras {
    // set for the project
    const val description = "Encrypted, high-performance, and event-driven/reactive network stack for Java 11+"
    const val group = "com.dorkbox"
    const val version = "5.0-beta7"

    // set as project.ext
    const val name = "Network"
    const val id = "Network"
    const val vendor = "Dorkbox LLC"
    const val vendorUrl = "https://dorkbox.com"
    const val url = "https://git.dorkbox.com/dorkbox/Network"

    val buildDate = Instant.now().toString()
}

///////////////////////////////
/////  assign 'Extras'
///////////////////////////////
GradleUtils.load("$projectDir/../../gradle.properties", Extras)
GradleUtils.fixIntellijPaths()
GradleUtils.defaultResolutionStrategy()
GradleUtils.compileConfiguration(JavaVersion.VERSION_11) { kotlinOptions ->
    // see: https://kotlinlang.org/docs/reference/using-gradle.html
    kotlinOptions.apply {
        // enable the use of inline classes. see https://kotlinlang.org/docs/reference/inline-classes.html
        freeCompilerArgs += "-Xinline-classes"
    }
}

// ratelimiter, "other" package
// ping, rest of unit tests
// getConnectionUpgradeType

// java 14 is faster with aeron!
// NOTE: now using aeron instead of netty
// todo: remove BC! use conscrypt instead, or native java? (if possible. we are java 11 now, instead of 1.6)
// using netty IP filters for connections
// /*
// * Copyright 2014 The Netty Project
// *
// * The Netty Project licenses this file to you under the Apache License,
// * version 2.0 (the "License"); you may not use this file except in compliance
// * with the License. You may obtain a copy of the License at:
// *
// *   http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// * License for the specific language governing permissions and limitations
// * under the License.
// */
//package dorkbox.network.ipFilter;


// also, NOT using bouncastle, but instead the google one
// better SSL library
// implementation("org.conscrypt:conscrypt-openjdk-uber:2.2.1")
//    init {
//            try {
//                Security.insertProviderAt(Conscrypt.newProvider(), 1);
//            }
//            catch (e: Throwable) {
//                e.printStackTrace();
//            }
//        }


licensing {
    license(License.APACHE_2) {
        description(Extras.description)
        url(Extras.url)
        author(Extras.vendor)

        extra("KryoNet RMI", License.BSD_3) {
            it.copyright(2008)
            it.author("Nathan Sweet")
            it.url("https://github.com/EsotericSoftware/kryonet")
        }
        extra("LAN HostDiscovery from Apache Commons JCS", License.APACHE_2) {
            it.copyright(2014)
            it.author("The Apache Software Foundation")
            it.url("https://issues.apache.org/jira/browse/JCS-40")
        }
        extra("MathUtils, IntArray, IntMap", License.APACHE_2) {
            it.copyright(2013)
            it.author("Mario Zechner <badlogicgames@gmail.com>")
            it.author("Nathan Sweet <nathan.sweet@gmail.com>")
            it.url("http://github.com/libgdx/libgdx")
        }
        extra("Netty (Various network + platform utilities)", License.APACHE_2) {
            it.copyright(2014)
            it.description("An event-driven asynchronous network application framework")
            it.author("The Netty Project")
            it.author("Contributors. See source NOTICE")
            it.url("https://netty.io")
        }
    }
}

sourceSets {
    main {
        java {
            setSrcDirs(listOf("src"))

            // want to include java files for the source. 'setSrcDirs' resets includes...
            include("**/*.java")
        }

        kotlin {
            setSrcDirs(listOf("src"))

            // want to include java files for the source. 'setSrcDirs' resets includes...
            include("**/*.java", "**/*.kt")
        }
    }

    test {
        java {
            setSrcDirs(listOf("test"))

            // want to include java files for the source. 'setSrcDirs' resets includes...
            include("**/*.java")
        }

        kotlin {
            setSrcDirs(listOf("test"))

            // want to include java files for the source. 'setSrcDirs' resets includes...
            include("**/*.java", "**/*.kt")
        }
    }
}

repositories {
    mavenLocal() // this must be first!
    jcenter()
}

tasks.jar.get().apply {
    manifest {
        // https://docs.oracle.com/javase/tutorial/deployment/jar/packageman.html
        attributes["Name"] = Extras.name

        attributes["Specification-Title"] = Extras.name
        attributes["Specification-Version"] = Extras.version
        attributes["Specification-Vendor"] = Extras.vendor

        attributes["Implementation-Title"] = "${Extras.group}.${Extras.id}"
        attributes["Implementation-Version"] = Extras.buildDate
        attributes["Implementation-Vendor"] = Extras.vendor

        attributes["Automatic-Module-Name"] = Extras.id
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:atomicfu:0.14.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.9")


    // https://github.com/real-logic/aeron
    val aeronVer = "1.30.0"
    // REMOVE UdpChannel when ISSUE https://github.com/real-logic/aeron/issues/1057 is resolved! (hopefully in 1.30.0)
    implementation("io.aeron:aeron-client:$aeronVer")
    implementation("io.aeron:aeron-driver:$aeronVer")

    // https://github.com/EsotericSoftware/kryo
    implementation("com.esotericsoftware:kryo:5.0.0-RC8")

    // https://github.com/magro/kryo-serializers
    implementation("de.javakaffee:kryo-serializers:0.45")

    // https://github.com/jpountz/lz4-java
    implementation("net.jpountz.lz4:lz4:1.3.0")

    // this is NOT the same thing as LMAX disruptor.
    // This is just a really fast queue (where LMAX is a fast queue + other things w/ a difficult DSL)
    // https://github.com/conversant/disruptor_benchmark
    // https://www.youtube.com/watch?v=jVMOgQgYzWU
    implementation("com.conversantmedia:disruptor:1.2.17")

    // https://github.com/jhalterman/typetools
    implementation("net.jodah:typetools:0.6.2")

    // https://github.com/dorkbox
    implementation("com.dorkbox:Annotations:3.1")
    implementation("com.dorkbox:MinLog-SLF4J:2.0")
    implementation("com.dorkbox:Utilities:1.8.2")
    implementation("com.dorkbox:NetworkUtils:2.0")

    // really fast storage
    // https://github.com/lmdbjava/lmdbjava
    implementation("org.lmdbjava:lmdbjava:0.8.1")
    // https://github.com/OpenHFT/Chronicle-Map
    implementation("net.openhft:chronicle-map:3.20.3")


    // Caffeine High-throughput Timeout Cache
    // https://github.com/ben-manes/caffeine
    implementation("com.github.ben-manes.caffeine:caffeine:2.8.5") {
        exclude("org.checkerframework", "checker-qual")
        exclude("com.google.errorprone", "error_prone_annotations")
    }

    // https://github.com/MicroUtils/kotlin-logging
    implementation("io.github.microutils:kotlin-logging:2.0.3")
    implementation("org.slf4j:slf4j-api:1.7.30")



    testImplementation("junit:junit:4.13")
    testImplementation("ch.qos.logback:logback-classic:1.2.3")
}

publishToSonatype {
    groupId = Extras.group
    artifactId = Extras.id
    version = Extras.version

    name = Extras.name
    description = Extras.description
    url = Extras.url

    vendor = Extras.vendor
    vendorUrl = Extras.vendorUrl

    issueManagement {
        url = "${Extras.url}/issues"
        nickname = "Gitea Issues"
    }

    developer {
        id = "dorkbox"
        name = Extras.vendor
        email = "email@dorkbox.com"
    }
}
