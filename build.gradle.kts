/*
 * Copyright 2018 dorkbox, llc
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

import Build_gradle.Extras.bcVersion
import java.time.Instant
import kotlin.collections.set

///////////////////////////////
//////    PUBLISH TO SONATYPE / MAVEN CENTRAL
//////
////// TESTING (local maven repo) -> PUBLISHING -> publishToMavenLocal
//////
////// RELEASE (sonatype / maven central) -> "PUBLISH AND RELEASE" -> publishAndRelease
///////////////////////////////

println("\tGradle ${project.gradle.gradleVersion} on Java ${JavaVersion.current()}")

plugins {
    java
    signing
    `maven-publish`

    // publish on sonatype
    id("de.marcphilipp.nexus-publish") version "0.2.0"
    // close and release on sonatype
    id("io.codearte.nexus-staging") version "0.21.0"

    id("com.dorkbox.CrossCompile") version "1.0.1"
    id("com.dorkbox.Licensing") version "1.4"
    id("com.dorkbox.VersionUpdate") version "1.4.1"
    id("com.dorkbox.GradleUtils") version "1.2"

    kotlin("jvm") version "1.3.31"
}

object Extras {
    // set for the project
    const val description = "Encrypted, high-performance, and event-driven/reactive network stack for Java 11+"
    const val group = "com.dorkbox"
    const val version = "4.0"

    // set as project.ext
    const val name = "Network"
    const val id = "Network"
    const val vendor = "Dorkbox LLC"
    const val url = "https://git.dorkbox.com/dorkbox/Network"
    val buildDate = Instant.now().toString()

    val JAVA_VERSION = JavaVersion.VERSION_11

    const val bcVersion = "1.60"

    var sonatypeUserName = ""
    var sonatypePassword = ""
}

///////////////////////////////
/////  assign 'Extras'
///////////////////////////////
GradleUtils.load("$projectDir/../../gradle.properties", Extras)
description = Extras.description
group = Extras.group
version = Extras.version


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
    implementation("io.netty:netty-all:4.1.34.Final")
    implementation("com.esotericsoftware:kryo:5.0.0-RC2")
    implementation("net.jpountz.lz4:lz4:1.3.0")

    implementation("org.bouncycastle:bcprov-jdk15on:$bcVersion")
    implementation("org.bouncycastle:bcpg-jdk15on:$bcVersion")
    implementation("org.bouncycastle:bcmail-jdk15on:$bcVersion")
    implementation("org.bouncycastle:bctls-jdk15on:$bcVersion")

    implementation("net.jodah:typetools:0.6.1")
    implementation("de.javakaffee:kryo-serializers:0.45")

    implementation("com.dorkbox:ObjectPool:2.12")
    implementation("com.dorkbox:Utilities:1.1")

    implementation("org.slf4j:slf4j-api:1.7.25")

    testCompile("junit:junit:4.12")
    testCompile("ch.qos.logback:logback-classic:1.2.3")
}

///////////////////////////////
//////    PUBLISH TO SONATYPE / MAVEN CENTRAL
//////
////// TESTING (local maven repo) -> PUBLISHING -> publishToMavenLocal
//////
////// RELEASE (sonatype / maven central) -> "PUBLISH AND RELEASE" -> publishAndRelease
///////////////////////////////
publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = Extras.group
            artifactId = Extras.id
            version = Extras.version

            from(components["java"])

            artifact(task<Jar>("sourceJar") {
                description = "Creates a JAR that contains the source code."

                from(sourceSets["main"].java)
                archiveClassifier.set("sources")
            })

            artifact(task<Jar>("javaDocJar") {
                description = "Creates a JAR that contains the javadocs."

                archiveClassifier.set("javadoc")
            })

            pom {
                name.set(Extras.name)
                description.set(Extras.description)
                url.set(Extras.url)

                issueManagement {
                    url.set("${Extras.url}/issues")
                    system.set("Gitea Issues")
                }
                organization {
                    name.set(Extras.vendor)
                    url.set("https://dorkbox.com")
                }
                developers {
                    developer {
                        id.set("dorkbox")
                        name.set(Extras.vendor)
                        email.set("email@dorkbox.com")
                    }
                }
                scm {
                    url.set(Extras.url)
                    connection.set("scm:${Extras.url}.git")
                }
            }

        }
    }

    tasks.withType<PublishToMavenRepository> {
        doFirst {
            println("\tPublishing '${publication.groupId}:${publication.artifactId}:${publication.version}' to ${repository.url}")
        }

        onlyIf {
            publication == publishing.publications["maven"] && repository == publishing.repositories["maven"]
        }
    }

    tasks.withType<PublishToMavenLocal> {
        onlyIf {
            publication == publishing.publications["maven"]
        }
    }

    // output the release URL in the console
    tasks["releaseRepository"].doLast {
        val url = "https://oss.sonatype.org/content/repositories/releases/"
        val projectName = Extras.group.replace('.', '/')
        val name = Extras.name
        val version = Extras.version

        println("Maven URL: $url$projectName/$name/$version/")
    }

    nexusStaging {
        username = Extras.sonatypeUserName
        password = Extras.sonatypePassword
    }

    nexusPublishing {
        packageGroup.set(Extras.group)
        repositoryName.set("maven")
        username.set(Extras.sonatypeUserName)
        password.set(Extras.sonatypePassword)
    }

    signing {
        sign(publishing.publications["maven"])
    }

    task<Task>("publishAndRelease") {
        group = "publish and release"

        // required to make sure the tasks run in the correct order
        tasks["closeAndReleaseRepository"].mustRunAfter(tasks["publishToNexus"])
        dependsOn("publishToNexus", "closeAndReleaseRepository")
    }
}
