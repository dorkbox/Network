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

plugins {
    java

    id("com.dorkbox.CrossCompile") version "1.1"
    id("com.dorkbox.Licensing") version "1.4.2"
    id("com.dorkbox.VersionUpdate") version "1.6.1"
    id("com.dorkbox.GradlePublish") version "1.1"
    id("com.dorkbox.GradleModuleInfo") version "1.0"
    id("com.dorkbox.GradleUtils") version "1.6"

    kotlin("jvm") version "1.3.72"
}

object Extras {
    // set for the project
    const val description = "Encrypted, high-performance, and event-driven/reactive network stack for Java 11+"
    const val group = "com.dorkbox"
    const val version = "4.1"

    // set as project.ext
    const val name = "Network"
    const val id = "Network"
    const val vendor = "Dorkbox LLC"
    const val vendorUrl = "https://dorkbox.com"
    const val url = "https://git.dorkbox.com/dorkbox/Network"
    val buildDate = Instant.now().toString()

    val JAVA_VERSION = JavaVersion.VERSION_11

    const val bcVersion = "1.60"

    var sonatypeUserName = ""
    var sonatypePassword = ""
    var sonatypePrivateKeyFile = ""
    var sonatypePrivateKeyPassword = ""
}

///////////////////////////////
/////  assign 'Extras'
///////////////////////////////
GradleUtils.load("$projectDir/../../gradle.properties", Extras)
description = Extras.description
group = Extras.group
version = Extras.version

// NOTE: now using aeron instead of netty

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
        author(Extras.vendor)
        url(Extras.url)
        note(Extras.description)
    }

    license("Dorkbox Utils", License.APACHE_2) {
        author(Extras.vendor)
        url("https://git.dorkbox.com/dorkbox/Utilities")
    }

    license("Bennidi Iterator", License.MIT) {
        copyright(2012)
        author("Benjamin Diedrichsen")
        url("https://github.com/bennidi/mbassador")
        note("Fast iterators from the MBassador project")
    }

    license("BouncyCastle", License.MIT) {
        copyright(2009)
        author("The Legion Of The Bouncy Castle")
        url("http://www.bouncycastle.org")
    }

    license("ObjectPool", License.APACHE_2) {
        author("dorkbox, llc")
        url("https://git.dorkbox.com/dorkbox/ObjectPool")
    }

    license("FastThreadLocal", License.BSD_3) {
        copyright(2014)
        author("Lightweight Java Game Library Project")
        author("Riven")
        url("https://github.com/LWJGL/lwjgl3/blob/5819c9123222f6ce51f208e022cb907091dd8023/modules/core/src/main/java/org/lwjgl/system/FastThreadLocal.java")
    }

    license("Javassist", License.BSD_3) {
        copyright(1999)
        author("Shigeru Chiba")
        author("Bill Burke")
        author("Jason T. Greene")
        url("http://www.csg.is.titech.ac.jp/~chiba/java")
        note("Licensed under the MPL/LGPL/Apache triple license")
    }

    license("Kryo", License.BSD_3) {
        copyright(2008)
        author("Nathan Sweet")
        url("https://github.com/EsotericSoftware/kryo")
    }

    license("kryo-serializers", License.APACHE_2) {
        copyright(2010)
        author("Martin Grotzke")
        author("Rafael Winterhalter")
        url("https://github.com/magro/kryo-serializers")
    }

    license("KryoNet RMI", License.BSD_3) {
        copyright(2008)
        author("Nathan Sweet")
        url("https://github.com/EsotericSoftware/kryonet")
    }

    license("LAN HostDiscovery from Apache Commons JCS", License.APACHE_2) {
        copyright(2014)
        author("The Apache Software Foundation")
        url("https://issues.apache.org/jira/browse/JCS-40")
    }

    license("LZ4 and XXhash", License.APACHE_2) {
        copyright(2011)
        copyright(2012)
        author("Yann Collet")
        author("Adrien Grand")
        url("https://github.com/jpountz/lz4-java")
    }

    license("MathUtils, IntArray, IntMap", License.APACHE_2) {
        copyright(2013)
        author("Mario Zechner <badlogicgames@gmail.com>")
        author("Nathan Sweet <nathan.sweet@gmail.com>")
        url("http://github.com/libgdx/libgdx/")
    }

    license("MinLog-SLF4J", License.APACHE_2) {
        copyright(2008)
        author("dorkbox, llc")
        author("Nathan Sweet")
        author("Dan Brown")
        url("https://git.dorkbox.com/dorkbox/MinLog-SLF4J")
        url("https://github.com/EsotericSoftware/minlog")
        note("Drop-in replacement for MinLog to log through SLF4j.")
    }

    license("ReflectASM", License.BSD_3) {
        copyright(2008)
        author("Nathan Sweet")
        url("https://github.com/EsotericSoftware/reflectasm")
    }

    license("SLF4J", License.MIT) {
        copyright(2008)
        author("QOS.ch")
        url("http://www.slf4j.org")
    }

    license("TypeTools", License.APACHE_2) {
        copyright(2017)
        author("Jonathan Halterman")
        url("https://github.com/jhalterman/typetools/")
        note("Tools for resolving generic types")
    }
}

sourceSets {
    main {
        java {
            setSrcDirs(listOf("src"))

            // want to include java files for the source. 'setSrcDirs' resets includes...
            include("**/*.java")
        }
    }

    test {
        java {
            setSrcDirs(listOf("test"))

            // want to include java files for the source. 'setSrcDirs' resets includes...
            include("**/*.java")
        }
    }
}

repositories {
    mavenLocal() // this must be first!
    jcenter()
}

///////////////////////////////
//////    Task defaults
///////////////////////////////
java {
    sourceCompatibility = Extras.JAVA_VERSION
    targetCompatibility = Extras.JAVA_VERSION
}

tasks.compileJava.get().apply {
    println("\tCompiling classes to Java $sourceCompatibility")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    sourceCompatibility = Extras.JAVA_VERSION.toString()
    targetCompatibility = Extras.JAVA_VERSION.toString()
}

tasks.withType<Jar> {
    duplicatesStrategy = DuplicatesStrategy.FAIL
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
    implementation("io.netty:netty-all:4.1.49.Final")
    implementation("com.esotericsoftware:kryo:5.0.0-RC2")
    implementation("net.jpountz.lz4:lz4:1.3.0")

    implementation("org.bouncycastle:bcprov-jdk15on:${Extras.bcVersion}")
    implementation("org.bouncycastle:bcpg-jdk15on:${Extras.bcVersion}")
    implementation("org.bouncycastle:bcmail-jdk15on:${Extras.bcVersion}")
    implementation("org.bouncycastle:bctls-jdk15on:${Extras.bcVersion}")

    implementation("net.jodah:typetools:0.6.2")
    implementation("de.javakaffee:kryo-serializers:0.45")

    implementation("com.dorkbox:ObjectPool:2.12")
    implementation("com.dorkbox:Utilities:1.2")

    implementation("org.slf4j:slf4j-api:1.7.30")

    // https://github.com/real-logic/aeron
    implementation("io.aeron:aeron-all:1.28.2")

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

    sonatype {
        userName = Extras.sonatypeUserName
        password = Extras.sonatypePassword
    }

    privateKey {
        fileName = Extras.sonatypePrivateKeyFile
        password = Extras.sonatypePrivateKeyPassword
    }
}
