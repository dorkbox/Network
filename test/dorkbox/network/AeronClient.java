package dorkbox.network;

import java.lang.reflect.Field;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sun.misc.Unsafe;

/**
 *
 */
public
class AeronClient {
    private static final Logger LOG = LoggerFactory.getLogger(AeronClient.class);

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

    /**
     * Command-line entry point.
     *
     * @param args Command-line arguments
     *
     * @throws Exception On any error
     */
    public static
    void main(final String[] args) throws Exception {
        ClientConfiguration configuration = new ClientConfiguration();
        configuration.remoteAddress = "loopback";
        // configuration.remoteAddress = "ipc";
        configuration.controlPort = 2000;
        configuration.port = 2001;

        Client client = new Client(configuration);
        client.connect();


        // different ones needed
        // send - reliable
        // send - unreliable
        // send - priority (0-255 -- 255 is MAX priority) when sending, max is always sent immediately, then lower priority is sent if there is no backpressure from the MediaDriver.
        // send - IPC/local
        // client.send("ASDASD");


        // connection needs to know
        // is UDP or IPC
        // host address

        // RMI
        // client.get(5) -> gets from the server connection, if exists, then global.
        //                  on server, a connection local RMI object "uses" an id for global, so there will never be a conflict
        //                  using some tricks, we can make it so that it DOESN'T matter the order in which objects are created,
        //                  and can specify, if we want, the object created.
        //                  Once created though, as NEW ONE with the same ID cannot be created until the old one is removed!


        client.close();
    }
}
