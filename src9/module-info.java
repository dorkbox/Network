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
    requires dorkbox.objectpool;

    requires chronicle.map;
    requires net.jodah.typetools;
    requires com.esotericsoftware.kryo;
    requires com.esotericsoftware.reflectasm;
    requires org.objenesis;

    requires lmdbjava;

    requires io.aeron.driver;
    requires io.aeron.client;
    requires org.agrona.core;


    requires atomicfu.jvm;
    requires kotlin.logging.jvm;

    requires kotlin.stdlib;
    requires kotlin.stdlib.jdk7;
    requires kotlinx.coroutines.core.jvm;

    requires java.base;
}
