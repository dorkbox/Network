package dorkbox.network;

import java.lang.reflect.Field;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sun.misc.Unsafe;

/**
 *
 */
public
class AeronServer {
    private static final Logger LOG = LoggerFactory.getLogger(AeronServer.class);

    static {
        Field theUnsafe = null;
        try {
            theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Unsafe u = (Unsafe) theUnsafe.get(null);

            Class cls = Class.forName("jdk.internal.module.IllegalAccessLogger");
            Field logger = cls.getDeclaredField("logger");
            u.putObjectVolatile(cls, u.staticFieldOffset(logger), null);
        } catch (NoSuchFieldException | IllegalAccessException | ClassNotFoundException e) {
            e.printStackTrace();
        }
    }

    public static
    void main(final String[] args) throws Exception {
        ServerConfiguration configuration = new ServerConfiguration();
        configuration.listenIpAddress = "loopback";
        configuration.controlPort = 2000;
        configuration.port = 2001;

        configuration.clientStartPort = 2500;
        configuration.maxClientCount = 5;
        configuration.maxConnectionsPerIpAddress = 5;


        Server server = new Server(configuration);
        server.bind();
    }
}
