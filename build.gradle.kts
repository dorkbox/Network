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
import java.nio.file.Paths
import java.time.Instant
import java.util.Properties
import kotlin.reflect.full.declaredMemberProperties
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import org.jetbrains.kotlin.backend.common.onlyIf
import java.io.*
import java.net.*
import java.nio.charset.StandardCharsets.UTF_8
import kotlin.reflect.KMutableProperty

plugins {
    java
    signing
    `maven-publish`

    // close and release on sonatype
    id("io.codearte.nexus-staging") version "0.20.0"

    id("com.dorkbox.CrossCompile") version "1.0.1"
    id("com.dorkbox.Licensing") version "1.4"
    id("com.dorkbox.VersionUpdate") version "1.4.1"

    // setup checking for the latest version of a plugin or dependency
    id("com.github.ben-manes.versions") version "0.20.0"

    kotlin("jvm") version "1.3.11"
}


println("\tGradle ${project.gradle.gradleVersion}")

object Extras {
    // set for the project
    const val description = "Encrypted, high-performance, and event-driven/reactive network stack for Java 6+"
    const val group = "com.dorkbox"
    const val version = "2.13"

    // set as project.ext
    const val name = "Network"
    const val id = "Network"
    const val vendor = "Dorkbox LLC"
    const val url = "https://git.dorkbox.com/dorkbox/Network"
    val buildDate = Instant.now().toString()

    val JAVA_VERSION = JavaVersion.VERSION_1_6.toString()

    const val bcVersion = "1.60"

    var sonatypeUserName = ""
    var sonatypePassword = ""
}

///////////////////////////////
/////  assign 'Extras' to project or project.ext
///////////////////////////////
description = Extras.description
group = Extras.group
version = Extras.version


// load properties from custom location
val propsFile = File("$projectDir/../../gradle.properties").normalize()
if (propsFile.canRead()) {
    println("\tLoading custom property data from: [$propsFile]")

    val props = Properties()
    props.load(propsFile.inputStream())

    val extraProperties = Extras::class.declaredMemberProperties.filterIsInstance<KMutableProperty<String>>()
    props.forEach { (k, v) -> run {
        val key = k as String
        val value = v as String

        val member = extraProperties.find { it.name == key }
        if (member != null) {
            member.setter.call(Extras::class.objectInstance, value)
        }
        else {
            project.extra.set(k, v)
        }
    } }
}

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
    jcenter()
}

dependencies {
    implementation(project("Utilities")) {
        // don't include any of the project dependencies for anything
        isTransitive = false
    }

    // our main dependencies are ALSO the same as the limited utilities (they are not automatically pulled in from other sourceSets)
    // needed by the utilities (custom since we don't want to include everything). IntelliJ includes everything, but our builds do not

    api("io.netty:netty-all:4.1.32.Final")
    api("com.esotericsoftware:kryo:4.0.2")
    api("net.jpountz.lz4:lz4:1.3.0")

    api("org.bouncycastle:bcprov-jdk15on:$bcVersion")
    api("org.bouncycastle:bcpg-jdk15on:$bcVersion")
    api("org.bouncycastle:bcmail-jdk15on:$bcVersion")
    api("org.bouncycastle:bctls-jdk15on:$bcVersion")


    api("com.dorkbox:ObjectPool:2.11")

    implementation ("org.slf4j:slf4j-api:1.7.25")

    testCompile("junit:junit:4.12")
    testCompile("ch.qos.logback:logback-classic:1.2.3")
}

project("Utilities") {
    tasks.withType<Test> {
        // want to remove utilities project from unit tests. It's unnecessary to run unit tests for the entire Utilities project
        exclude("**/*")
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.isIncremental = true
    }
}


///////////////////////////////
//////    Task defaults
///////////////////////////////
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"

    sourceCompatibility = Extras.JAVA_VERSION
    targetCompatibility = Extras.JAVA_VERSION
}

tasks.withType<Jar> {
    duplicatesStrategy = DuplicatesStrategy.FAIL
}


///////////////////////////////
//////    UTILITIES COMPILE (for inclusion into jars)
///////////////////////////////
fun javaFile(vararg fileNames: String): Iterable<String> {
    val fileList = ArrayList<String>()

    fileNames.forEach { name ->
        fileList.add(name.replace('.', '/') + ".java")
    }

    return fileList
}

task<JavaCompile>("compileUtils") {
    // we don't want the default include of **/*.java
    includes.clear()

    source = fileTree("../Utilities/src")
    include(javaFile(
            "dorkbox.util.OS",
            "dorkbox.util.OSType",
            "dorkbox.util.Property",
            "dorkbox.util.NamedThreadFactory",
            "dorkbox.util.DelayTimer",
            "dorkbox.util.IO",
            "dorkbox.util.FileUtil",
            "dorkbox.util.Base64Fast",
            "dorkbox.util.RandomUtil",
            "dorkbox.util.Sys",
            "dorkbox.util.HashUtil",
            "dorkbox.util.NativeLoader",

            "dorkbox.util.FastThreadLocal",
            "dorkbox.util.LocationResolver",
            "dorkbox.util.MathUtil",
            "dorkbox.util.MersenneTwisterFast",
            "dorkbox.util.NativeLoader",
            "dorkbox.util.NativeLoader",

            "dorkbox.util.generics.TypeResolver",
            // "dorkbox.util.generics.DefaultMethodHelper",  // this class must be compiled java 8+
            "dorkbox.util.generics.ClassHelper",

            "dorkbox.util.bytes.BigEndian",
            "dorkbox.util.bytes.UByte",
            "dorkbox.util.bytes.UInteger",
            "dorkbox.util.bytes.ULong",
            "dorkbox.util.bytes.Unsigned",
            "dorkbox.util.bytes.UNumber",
            "dorkbox.util.bytes.UShort",
            "dorkbox.util.bytes.ByteArrayWrapper",
            "dorkbox.util.bytes.OptimizeUtilsByteArray",
            "dorkbox.util.bytes.OptimizeUtilsByteBuf",

            "dorkbox.util.exceptions.SecurityException",
            "dorkbox.util.exceptions.InitializationException",


            "dorkbox.util.collections.ObjectIntMap",
            "dorkbox.util.collections.IntMap",
            "dorkbox.util.collections.IntArray",
            "dorkbox.util.collections.ConcurrentIterator",
            "dorkbox.util.collections.ConcurrentEntry",
            "dorkbox.util.collections.LockFreeHashMap",
            "dorkbox.util.collections.LockFreeIntMap",
            "dorkbox.util.collections.LockFreeIntBiMap",
            "dorkbox.util.collections.LockFreeObjectIntBiMap",

            "dorkbox.util.crypto.CryptoECC",
            "dorkbox.util.crypto.CryptoAES"))

    // entire packages/directories
    include("dorkbox/util/serialization/**/*.java")
    include("dorkbox/util/entropy/**/*.java")
    include("dorkbox/util/storage/**/*.java")


    classpath = sourceSets["main"].compileClasspath
    destinationDir = file("$rootDir/build/classes_utilities")
}

/////////////////////////////////
////////    Compile some of the files to java 8
/////////////////////////////////
task<JavaCompile>("compileUtilsJava8") {
    // we don't want the default include of **/*.java
    includes.clear()

    source = fileTree("../Utilities/src")

    // this class must be compiled java 8+
    include(javaFile("dorkbox.util.generics.DefaultMethodHelper"))

    sourceCompatibility = JavaVersion.VERSION_1_8.toString()
    targetCompatibility = JavaVersion.VERSION_1_8.toString()

    classpath = sourceSets["main"].compileClasspath
    destinationDir = file("$rootDir/build/classes_utilities")

    println("\tCompiling $includes to Java $sourceCompatibility")
}

tasks.compileJava.get().apply {
    println("\tCompiling classes to Java $sourceCompatibility")
}


///////////////////////////////
//////    Jar Tasks
///////////////////////////////
tasks.jar.get().apply {
    dependsOn("compileUtils", "compileUtilsJava8")

    // include applicable class files from subset of Utilities project
    from((tasks["compileUtils"] as JavaCompile).destinationDir)

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

///////////////////////////////
//////    Maven Publishing + Release
///////////////////////////////
val sourceJar = task<Jar>("sourceJar") {
    description = "Creates a JAR that contains the source code."

    from(sourceSets["main"].java)

    archiveClassifier.set("sources")
}

val javaDocJar = task<Jar>("javaDocJar") {
    description = "Creates a JAR that contains the javadocs."

    archiveClassifier.set("javadoc")
}


///////////////////////////////
// functions to make changing the maven POM easier
@Suppress("UNCHECKED_CAST")
fun org.gradle.api.publish.maven.MavenPom.removeDependenciesByArtifactId(vararg names: String) {
    withXml {
        (asNode().get("dependencies") as List<groovy.util.Node>).forEach {deps ->
            (deps.children() as List<groovy.util.Node>).filter { it ->
                val text = (it.get("artifactId") as List<groovy.util.Node>).firstOrNull()?.text()
                if (text == null) {
                    false
                } else {
                names.contains(text)
                }
            }.forEach { node->
                node.parent().remove(node)
            }
        }
    }
}

///////////////////////////////
//////    PUBLISH TO SONATYPE / MAVEN CENTRAL
//////
////// TESTING : local maven repo <PUBLISHING - publishToMavenLocal>
//////
////// RELEASE : sonatype / maven central, <PUBLISHING - publish> then <RELEASE - CloseAndReleaseRepository>
///////////////////////////////
publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = Extras.group
            artifactId = Extras.id
            version = Extras.version

            from(components["java"])

            artifact(sourceJar)
            artifact(javaDocJar)

            pom {
                name.set(Extras.name)
                description.set(Extras.description)
                url.set(Extras.url)

                removeDependenciesByArtifactId("Utilities")

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


    repositories {
        maven {
            setUrl("https://oss.sonatype.org/service/local/staging/deploy/maven2")
            credentials {
                username = Extras.sonatypeUserName
                password = Extras.sonatypePassword
            }
        }
    }


    tasks.withType<PublishToMavenRepository> {
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
}

nexusStaging {
    username = Extras.sonatypeUserName
    password = Extras.sonatypePassword
}

signing {
    sign(publishing.publications["maven"])
}

///////////////////////////////
/////   Prevent anything other than a release from showing version updates
////  https://github.com/ben-manes/gradle-versions-plugin/blob/master/README.md
///////////////////////////////
tasks.named<DependencyUpdatesTask>("dependencyUpdates") {
    resolutionStrategy {
        componentSelection {
            all {
                val rejected = listOf("alpha", "beta", "rc", "cr", "m", "preview")
                    .map { qualifier -> Regex("(?i).*[.-]$qualifier[.\\d-]*") }
                    .any { it.matches(candidate.version) }
                if (rejected) {
                    reject("Release candidate")
                }
            }
        }
    }

    // optional parameters
    checkForGradleUpdate = true
}

///////////////////////////////
//////    Gradle Wrapper Configuration.
/////  Run this task, then refresh the gradle project
///////////////////////////////
val wrapperUpdate by tasks.creating(Wrapper::class) {
    gradleVersion = "5.1.1"
    distributionUrl = distributionUrl.replace("bin", "all")
}


