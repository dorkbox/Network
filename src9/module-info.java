module dorkbox.network {
    exports dorkbox.network;
    exports dorkbox.network.aeron;
    exports dorkbox.network.connection;
    exports dorkbox.network.connectionType;
    exports dorkbox.network.coroutines;
    exports dorkbox.network.exceptions;
    exports dorkbox.network.handshake;
    exports dorkbox.network.ipFilter;
    exports dorkbox.network.ping;
    exports dorkbox.network.rmi;
    exports dorkbox.network.serialization;
    exports dorkbox.network.storage;
    exports dorkbox.network.storage.types;

    requires dorkbox.updates;
    requires dorkbox.utilities;
    requires dorkbox.netutil;
    requires dorkbox.minlog;
    requires dorkbox.serializers;
//    requires dorkbox.storage;
    requires dorkbox.objectpool;

    requires expiringmap;
    requires net.jodah.typetools;
    requires de.javakaffee.kryoserializers;
    requires com.esotericsoftware.kryo;
    requires com.esotericsoftware.reflectasm;
    requires org.objenesis;

    requires io.aeron.driver;
    requires io.aeron.client;
    requires org.agrona.core;

    requires atomicfu.jvm;
    requires kotlin.logging.jvm;

    requires kotlin.stdlib;
    requires kotlin.stdlib.jdk7;
    requires kotlinx.coroutines.core.jvm;

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


    requires java.base;
}
