module dorkbox.network {
    exports dorkbox.network;
    exports dorkbox.network.aeron;
    exports dorkbox.network.connection;
    exports dorkbox.network.connection.streaming;
    exports dorkbox.network.connectionType;
    exports dorkbox.network.coroutines;
    exports dorkbox.network.exceptions;
    exports dorkbox.network.handshake;
    exports dorkbox.network.ipFilter;
    exports dorkbox.network.ping;
    exports dorkbox.network.rmi;
    exports dorkbox.network.serialization;

    requires transitive dorkbox.bytes;
    requires transitive dorkbox.classUtils;
    requires transitive dorkbox.collections;
    requires transitive dorkbox.dns;
    requires transitive dorkbox.updates;
    requires transitive dorkbox.utilities;
    requires transitive dorkbox.netutil;
    requires transitive dorkbox.minlog;
    requires transitive dorkbox.serializers;
    requires transitive dorkbox.storage;
    requires transitive dorkbox.objectpool;
    requires transitive dorkbox.os;

    // requires transitive expiringmap;
    requires transitive com.esotericsoftware.kryo;
    requires transitive com.esotericsoftware.reflectasm;
    requires transitive org.objenesis;

    requires transitive io.aeron.all;
    // requires io.aeron.driver;
    // requires io.aeron.client;
    // requires org.agrona.core;

    requires transitive org.slf4j;
    requires transitive io.github.microutils.kotlinlogging;

    requires transitive kotlinx.atomicfu;

    requires kotlin.stdlib;
    requires kotlin.stdlib.jdk8;
    requires kotlinx.coroutines.core;

    // requires kotlinx.coroutines.core.jvm;

    // compile-only libraries
//    requires static chronicle.map;
//    requires static chronicle.core;
//    requires static chronicle.analytics;
//    requires static chronicle.values;
//    requires static chronicle.threads;
//    requires static affinity;
//    requires static com.sun.jna;
//    requires static com.sun.jna.platform;
//    requires static chronicle.wire;
//    requires static chronicle.bytes;
//    requires static chronicle.algorithms;
//    requires static backport.util.concurrent;
//    requires static xstream;
//    requires static xmlpull;
//    requires static xpp3.min;
//    requires static jettison;
//    requires static pax.url.aether;
//    requires static pax.url.aether.support;
//    requires static org.apache.maven.resolver.impl;
//    requires static org.apache.maven.resolver.spi;
//    requires static org.apache.maven.resolver.util;
//    requires static jcl.over.slf4j;

//    requires static compiler;


//    requires static lmdbjava;
//    requires static jnr.constants;
//    requires static jnr.ffi;


    // requires java.base;
}
