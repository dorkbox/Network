/*
 * Copyright 2023 dorkbox, llc
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

///////////////////////////////
//////    PUBLISH TO SONATYPE / MAVEN CENTRAL
////// TESTING : (to local maven repo) <'publish and release' - 'publishToMavenLocal'>
////// RELEASE : (to sonatype/maven central), <'publish and release' - 'publishToSonatypeAndRelease'>
///////////////////////////////

gradle.startParameter.showStacktrace = ShowStacktrace.ALWAYS   // always show the stacktrace!

plugins {
    id("com.dorkbox.GradleUtils") version "3.17"
    id("com.dorkbox.Licensing") version "2.25"
    id("com.dorkbox.VersionUpdate") version "2.8"
    id("com.dorkbox.GradlePublish") version "1.18"

    id("com.github.johnrengelman.shadow") version "7.1.2"

    kotlin("jvm") version "1.8.0"
}

object Extras {
    // set for the project
    const val description = "High-performance, event-driven/reactive network stack for Java 11+"
    const val group = "com.dorkbox"
    const val version = "6.6"

    // set as project.ext
    const val name = "Network"
    const val id = "Network"
    const val vendor = "Dorkbox LLC"
    const val vendorUrl = "https://dorkbox.com"
    const val url = "https://git.dorkbox.com/dorkbox/Network"
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
GradleUtils.jpms(JavaVersion.VERSION_11) // this is a bit of a hack to workaround JPMS support with a kotlin-only build.


//val kotlin = project.extensions.getByType(org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension::class.java).sourceSets.getByName("main").kotlin
//kotlin.apply {
//    setSrcDirs(project.files("src"))
//    include("**/*.kt") // want to include kotlin files for the source. 'setSrcDirs' resets includes...
//}


// if we are sending a SMALL byte array, then we SEND IT DIRECTLY in a more optimized manner (because we can count size info!)
//   other side has to be able to parse/know that this was sent directly as bytes. It could be game state data, or voice data, etc.
// another idea is to be able to "send" a stream of bytes (this would also get chunked/etc!). if chunked, these are fixed byte sizes!
// -- the first byte manage: byte/message/stream/etc, no-crypt, crypt, crypt+compress
// - connection.inputStream() --> behaves as an input stream to remote endpoint --> connection.outputStream()
//   -- open/close/flush/etc commands also go through
//   -- this can be used to stream files/audio/etc VERY easily
//   -- have a createInputStream(), which will cause the outputStream() on the remote end to be created.
//     --- this remote outputStream is a file, raw??? this is setup by createInputStream() on the remote end
// - state-machine for kryo class registrations?

// ratelimiter, "other" package, send-on-idle
// rest of unit tests
// getConnectionUpgradeType
// ability to send with a function callback (using RMI waiter type stuff for callbacks)

// java 14 is faster with aeron!




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
        attributes["Implementation-Version"] = GradleUtils.now()
        attributes["Implementation-Vendor"] = Extras.vendor
    }
}


val shadowJar: com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar by tasks
shadowJar.apply {
    manifest.inheritFrom(tasks.jar.get().manifest)

    manifest.attributes.apply {
        put("Main-Class", "dorkboxTest.network.app.AeronRmiClientServer")
    }

    mergeServiceFiles()

    duplicatesStrategy = DuplicatesStrategy.INCLUDE

    from(sourceSets.test.get().output)
    configurations = listOf(project.configurations.testRuntimeClasspath.get())

    archiveBaseName.set(project.name + "-all")
}


dependencies {
    api("org.jetbrains.kotlinx:atomicfu:0.21.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")

    // https://github.com/dorkbox
    api("com.dorkbox:ByteUtilities:1.12")
    api("com.dorkbox:ClassUtils:1.1")
    api("com.dorkbox:Collections:2.2")
    api("com.dorkbox:JNA:1.0")
    api("com.dorkbox:MinLog:2.5")
    api("com.dorkbox:NetworkDNS:2.10")
    api("com.dorkbox:NetworkUtils:2.22")
    api("com.dorkbox:OS:1.6")
    api("com.dorkbox:Serializers:2.9")
    api("com.dorkbox:Storage:1.5")
    api("com.dorkbox:Updates:1.1")
    api("com.dorkbox:Utilities:1.44")


    // how we bypass using reflection/jpms to access fields for java17+
    api("org.javassist:javassist:3.29.2-GA")


    val jnaVersion = "5.12.1"
    api("net.java.dev.jna:jna-jpms:${jnaVersion}")
    api("net.java.dev.jna:jna-platform-jpms:${jnaVersion}")


    // https://github.com/real-logic/aeron
    val aeronVer = "1.41.4"
    api("io.aeron:aeron-driver:$aeronVer")
    // ALL of aeron, in case we need to debug aeron behavior
//    api("io.aeron:aeron-all:$aeronVer")
//    api("org.agrona:agrona:1.18.2") // sources for this aren't included in aeron-all!

    // https://github.com/EsotericSoftware/kryo
    api("com.esotericsoftware:kryo:5.5.0") {
        exclude("com.esotericsoftware", "minlog") // we use our own minlog, that logs to SLF4j instead
    }

    // https://github.com/lz4/lz4-java
    api("org.lz4:lz4-java:1.8.0")

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
    api("org.slf4j:slf4j-api:2.0.7")




    testImplementation("junit:junit:4.13.2")
    testImplementation("ch.qos.logback:logback-classic:1.4.5")
    testImplementation("io.aeron:aeron-all:$aeronVer")

    testImplementation("com.dorkbox:Config:2.0")
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
