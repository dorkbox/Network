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

import java.time.Instant

///////////////////////////////
//////    PUBLISH TO SONATYPE / MAVEN CENTRAL
////// TESTING : (to local maven repo) <'publish and release' - 'publishToMavenLocal'>
////// RELEASE : (to sonatype/maven central), <'publish and release' - 'publishToSonatypeAndRelease'>
///////////////////////////////

gradle.startParameter.showStacktrace = ShowStacktrace.ALWAYS   // always show the stacktrace!

plugins {
    id("com.dorkbox.GradleUtils") version "2.9"
    id("com.dorkbox.Licensing") version "2.9.2"
    id("com.dorkbox.VersionUpdate") version "2.4"
    id("com.dorkbox.GradlePublish") version "1.11"

    kotlin("jvm") version "1.5.21"
}

object Extras {
    // set for the project
    const val description = "Encrypted, high-performance, and event-driven/reactive network stack for Java 8+"
    const val group = "com.dorkbox"
    const val version = "5.5"

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
GradleUtils.defaults()
// because of the api changes for stacktrace stuff, it's best for us to ONLY support 11+
GradleUtils.compileConfiguration(JavaVersion.VERSION_1_8) {
    // see: https://kotlinlang.org/docs/reference/using-gradle.html
    // enable the use of inline classes. see https://kotlinlang.org/docs/reference/inline-classes.html
    freeCompilerArgs = listOf("-Xinline-classes")
}
GradleUtils.jpms(JavaVersion.VERSION_1_9)


// ratelimiter, "other" package
// rest of unit tests
// getConnectionUpgradeType
// ability to send with a function callback (using RMI waiter type stuff for callbacks)
// use conscrypt?!

// java 14 is faster with aeron!
// NOTE: now using aeron instead of netty
// todo: remove BC! use conscrypt instead, or native java? (if possible. we are java 11 now, instead of 1.6)


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
            copyright(2008)
            author("Nathan Sweet")
            url("https://github.com/EsotericSoftware/kryonet")
        }
        extra("Kryo Serialization", License.BSD_3) {
            copyright(2020)
            author("Nathan Sweet")
            url("https://github.com/EsotericSoftware/kryo")
        }
        extra("LAN HostDiscovery from Apache Commons JCS", License.APACHE_2) {
            copyright(2014)
            author("The Apache Software Foundation")
            url("https://issues.apache.org/jira/browse/JCS-40")
        }
        extra("MathUtils, IntArray, IntMap", License.APACHE_2) {
            copyright(2013)
            author("Mario Zechner <badlogicgames@gmail.com>")
            author("Nathan Sweet <nathan.sweet@gmail.com>")
            url("http://github.com/libgdx/libgdx")
        }
        extra("Netty (Various network + platform utilities)", License.APACHE_2) {
            copyright(2014)
            description("An event-driven asynchronous network application framework")
            author("The Netty Project")
            author("Contributors. See source NOTICE")
            url("https://netty.io")
        }
    }
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
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:atomicfu:0.16.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.1")

    // https://github.com/dorkbox
    implementation("com.dorkbox:ByteUtilities:1.3")
    implementation("com.dorkbox:MinLog:2.4")
    implementation("com.dorkbox:NetworkUtils:2.8")
    implementation("com.dorkbox:ObjectPool:3.4")
    implementation("com.dorkbox:Serializers:2.5")
    implementation("com.dorkbox:Storage:1.0")
    implementation("com.dorkbox:Updates:1.1")
    implementation("com.dorkbox:Utilities:1.12")


    // https://github.com/real-logic/aeron
    val aeronVer = "1.35.0"
    // REMOVE UdpChannel when ISSUE https://github.com/real-logic/aeron/issues/1057 is resolved! (hopefully in 1.30.0)
    implementation("io.aeron:aeron-client:$aeronVer")
    implementation("io.aeron:aeron-driver:$aeronVer")

    // https://github.com/EsotericSoftware/kryo
    implementation("com.esotericsoftware:kryo:5.2.0") {
        exclude("com.esotericsoftware", "minlog") // we use our own minlog, that logs to SLF4j instead
    }

    // https://github.com/jpountz/lz4-java
//    implementation("net.jpountz.lz4:lz4:1.3.0")

    // this is NOT the same thing as LMAX disruptor.
    // This is just a really fast queue (where LMAX is a fast queue + other things w/ a difficult DSL)
    // https://github.com/conversant/disruptor_benchmark
    // https://www.youtube.com/watch?v=jVMOgQgYzWU
    implementation("com.conversantmedia:disruptor:1.2.19")

    // https://github.com/jhalterman/typetools
    implementation("net.jodah:typetools:0.6.3")

//    // really fast storage
//    // https://github.com/lmdbjava/lmdbjava
//    val lmdbJava = "org.lmdbjava:lmdbjava:0.8.1"
//    compileOnly(lmdbJava)
//
//    // https://github.com/OpenHFT/Chronicle-Map
//    val chronicleMap = "net.openhft:chronicle-map:3.20.84"
//    compileOnly(chronicleMap) {
//        exclude("com.intellij", "annotations") // not even available at runtime
//        // optional from chronicle-map. These cause JPMS to fail!
//        exclude("com.thoughtworks.xstream", "xstream")
//        exclude("org.ops4j.pax.url", "pax-url-aether")
//        exclude("org.codehaus.jettison", "jettison")
//    }
//

    // Jodah Expiring Map (A high performance thread-safe map that expires entries)
    // https://github.com/jhalterman/expiringmap
    implementation("net.jodah:expiringmap:0.5.10")



    // https://github.com/MicroUtils/kotlin-logging
    implementation("io.github.microutils:kotlin-logging:2.0.10")
    implementation("org.slf4j:slf4j-api:1.8.0-beta4")


//    testImplementation(lmdbJava)
//    testImplementation(chronicleMap)

    testImplementation("junit:junit:4.13.1")
    testImplementation("ch.qos.logback:logback-classic:1.3.0-alpha4")
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
