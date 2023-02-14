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
    id("com.dorkbox.GradleUtils") version "3.13"
    id("com.dorkbox.Licensing") version "2.22"
    id("com.dorkbox.VersionUpdate") version "2.7"
    id("com.dorkbox.GradlePublish") version "1.18"

    id("com.github.johnrengelman.shadow") version "7.1.2"

    kotlin("jvm") version "1.8.0"
}

object Extras {
    // set for the project
    const val description = "High-performance, event-driven/reactive network stack for Java 11+"
    const val group = "com.dorkbox"
    const val version = "6.4"

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
GradleUtils.compileConfiguration(JavaVersion.VERSION_11) {
    // see: https://kotlinlang.org/docs/reference/using-gradle.html
    // enable the use of inline classes. see https://kotlinlang.org/docs/reference/inline-classes.html
    freeCompilerArgs = listOf("-Xinline-classes")
}
//GradleUtils.jpms(JavaVersion.VERSION_11)
//NOTE: we do not support JPMS yet, as there are some libraries missing support for it still, notably kotlin!


// ratelimiter, "other" package
// rest of unit tests
// getConnectionUpgradeType
// ability to send with a function callback (using RMI waiter type stuff for callbacks)

// java 14 is faster with aeron!
// NOTE: now using aeron instead of netty
// todo: remove BC! use or native java? (if possible. we are java 11 now, instead of 1.6)


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


val shadowJar: com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar by tasks
shadowJar.apply {
    manifest.inheritFrom(tasks.jar.get().manifest)

    manifest.attributes.apply {
        put("Main-Class", "dorkboxTest.network.AeronClientServer")
    }

    mergeServiceFiles()

    duplicatesStrategy = DuplicatesStrategy.INCLUDE

    from(sourceSets.test.get().output)
    configurations = listOf(project.configurations.testRuntimeClasspath.get())

    archiveBaseName.set(project.name + "-all")
}


dependencies {
    api("org.jetbrains.kotlinx:atomicfu:0.19.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")

    // https://github.com/dorkbox
    api("com.dorkbox:ByteUtilities:1.8")
    api("com.dorkbox:Collections:1.4")
    api("com.dorkbox:MinLog:2.5")
    api("com.dorkbox:NetworkDNS:2.7.2")
    api("com.dorkbox:NetworkUtils:2.19.1")
//    api("com.dorkbox:ObjectPool:4.2")
    api("com.dorkbox:OS:1.6")
    api("com.dorkbox:Serializers:2.8")
    api("com.dorkbox:Storage:1.2")
    api("com.dorkbox:Updates:1.1")
    api("com.dorkbox:Utilities:1.40")


    // we include ALL of aeron, in case we need to debug aeron behavior
    // https://github.com/real-logic/aeron
    val aeronVer = "1.40.0"
    api("io.aeron:aeron-all:$aeronVer")
//    api("org.agrona:agrona:1.16.0") // sources for this isn't included in aeron-all!

    // https://github.com/EsotericSoftware/kryo
    api("com.esotericsoftware:kryo:5.4.0") {
        exclude("com.esotericsoftware", "minlog") // we use our own minlog, that logs to SLF4j instead
    }

    // https://github.com/jpountz/lz4-java
//    implementation("net.jpountz.lz4:lz4:1.3.0")

    // this is NOT the same thing as LMAX disruptor.
    // This is just a slightly faster queue than LMAX. (LMAX is a fast queue + other things w/ a difficult DSL)
    // https://github.com/conversant/disruptor_benchmark
    // https://www.youtube.com/watch?v=jVMOgQgYzWU
    //api("com.conversantmedia:disruptor:1.2.19")

    // https://github.com/jhalterman/typetools
    api("net.jodah:typetools:0.6.3")

    // Expiring Map (A high performance thread-safe map that expires entries)
    // https://github.com/jhalterman/expiringmap
    api("net.jodah:expiringmap:0.5.10")

    // https://github.com/MicroUtils/kotlin-logging
    api("io.github.microutils:kotlin-logging:3.0.5")
    api("org.slf4j:slf4j-api:2.0.6")




    testImplementation("junit:junit:4.13.2")
    testImplementation("ch.qos.logback:logback-classic:1.4.5")
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
