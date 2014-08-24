
package dorkbox.network;


import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.EndPoint;
import dorkbox.network.connection.Listener;
import dorkbox.network.util.exceptions.InitializationException;
import dorkbox.network.util.exceptions.SecurityException;

public class UnregisteredClassTest extends BaseTest {
    private String fail;
    private int tries = 10000;

    private AtomicInteger receivedTCP = new AtomicInteger();
    private AtomicInteger receivedUDP = new AtomicInteger();

    @Test
    public void unregisteredClasses() throws IOException, InitializationException, SecurityException {
        int origSize = EndPoint.udpMaxSize;
        EndPoint.udpMaxSize = 2048;

        ConnectionOptions connectionOptions = new ConnectionOptions();
        connectionOptions.tcpPort = tcpPort;
        connectionOptions.udpPort = udpPort;
        connectionOptions.host = host;

        System.err.println("Running test " + tries + " times, please wait for it to finish.");

        final Data dataTCP = new Data();
        populateData(dataTCP, true);
        final Data dataUDP = new Data();
        populateData(dataUDP, false);

        Server server = new Server(connectionOptions);
        server.getSerialization().setRegistrationRequired(false);
        addEndPoint(server);
        server.bind(false);
        server.listeners().add(new Listener<Connection, Data>() {
            @Override
            public void error(Connection connection, Throwable throwable) {
                fail = "Error during processing. " + throwable;
            }

            @Override
            public void received (Connection connection, Data data) {
                if (data.isTCP) {
                    if (!data.equals(dataTCP)) {
                        fail = "TCP data is not equal on server.";
                        throw new RuntimeException("Fail! " + fail);
                    }
                    connection.send().TCP(data);
                    receivedTCP.incrementAndGet();
                } else {
                    if (!data.equals(dataUDP)) {
                        fail = "UDP data is not equal on server.";
                        throw new RuntimeException("Fail! " + fail);
                    }
                    connection.send().UDP(data);
                    receivedUDP.incrementAndGet();
                }
            }
        });

        // ----

        Client client = new Client(connectionOptions);
        client.getSerialization().setRegistrationRequired(false);
        addEndPoint(client);
        client.listeners().add(new Listener<Connection, Data>() {
            AtomicInteger checkTCP = new AtomicInteger(0);
            AtomicInteger checkUDP = new AtomicInteger(0);
            AtomicBoolean doneTCP = new AtomicBoolean(false);
            AtomicBoolean doneUDP = new AtomicBoolean(false);

            @Override
            public void connected (Connection connection) {
                fail = null;
                connection.send().TCP(dataTCP);
                connection.send().UDP(dataUDP); // UDP ping pong stops if a UDP packet is lost.
            }

            @Override
            public void error(Connection connection, Throwable throwable) {
                fail = "Error during processing. " + throwable;
                System.err.println(fail);
            }

            @Override
            public void received (Connection connection, Data data) {
                if (data.isTCP) {
                    if (!data.equals(dataTCP)) {
                        fail = "TCP data is not equal on client.";
                        throw new RuntimeException("Fail! " + fail);
                    }
                    if (checkTCP.getAndIncrement() <= tries) {
                        connection.send().TCP(data);
                        receivedTCP.incrementAndGet();
                    } else {
                        System.err.println("TCP done.");
                        doneTCP.set(true);
                    }
                } else {
                    if (!data.equals(dataUDP)) {
                        fail = "UDP data is not equal on client.";
                        throw new RuntimeException("Fail! " + fail);
                    }
                    if (checkUDP.getAndIncrement() <= tries) {
                        connection.send().UDP(data);
                        receivedUDP.incrementAndGet();
                    } else {
                        System.err.println("UDP done.");
                        doneUDP.set(true);
                    }
                }

                if (doneTCP.get() && doneUDP.get()) {
                    System.err.println("Ran TCP & UDP " + tries + " times each");
                    stopEndPoints();
                }
            }
        });

        client.connect(5000);
        waitForThreads();

        if (fail != null) {
            fail(fail);
        }

        EndPoint.udpMaxSize = origSize;
    }

    private void populateData (Data data, boolean isTCP) {
        data.isTCP = isTCP;

        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            buffer.append('a');
        }
        data.string = buffer.toString();

        data.strings = new String[] {"ab012", "", null, "!@#$", "�����"};
        data.ints = new int[] {-1234567, 1234567, -1, 0, 1, Integer.MAX_VALUE, Integer.MIN_VALUE};
        data.shorts = new short[] {-12345, 12345, -1, 0, 1, Short.MAX_VALUE, Short.MIN_VALUE};
        data.floats = new float[] {0, -0, 1, -1, 123456, -123456, 0.1f, 0.2f, -0.3f, (float)Math.PI, Float.MAX_VALUE,
            Float.MIN_VALUE};
        data.doubles = new double[] {0, -0, 1, -1, 123456, -123456, 0.1d, 0.2d, -0.3d, Math.PI, Double.MAX_VALUE, Double.MIN_VALUE};
        data.longs = new long[] {0, -0, 1, -1, 123456, -123456, 99999999999l, -99999999999l, Long.MAX_VALUE, Long.MIN_VALUE};
        data.bytes = new byte[] {-123, 123, -1, 0, 1, Byte.MAX_VALUE, Byte.MIN_VALUE};
        data.chars = new char[] {32345, 12345, 0, 1, 63, Character.MAX_VALUE, Character.MIN_VALUE};
        data.booleans = new boolean[] {true, false};
        data.Ints = new Integer[] {-1234567, 1234567, -1, 0, 1, Integer.MAX_VALUE, Integer.MIN_VALUE};
        data.Shorts = new Short[] {-12345, 12345, -1, 0, 1, Short.MAX_VALUE, Short.MIN_VALUE};
        data.Floats = new Float[] {0f, -0f, 1f, -1f, 123456f, -123456f, 0.1f, 0.2f, -0.3f, (float)Math.PI, Float.MAX_VALUE,
            Float.MIN_VALUE};
        data.Doubles = new Double[] {0d, -0d, 1d, -1d, 123456d, -123456d, 0.1d, 0.2d, -0.3d, Math.PI, Double.MAX_VALUE,
            Double.MIN_VALUE};
        data.Longs = new Long[] {0l, -0l, 1l, -1l, 123456l, -123456l, 99999999999l, -99999999999l, Long.MAX_VALUE, Long.MIN_VALUE};
        data.Bytes = new Byte[] {-123, 123, -1, 0, 1, Byte.MAX_VALUE, Byte.MIN_VALUE};
        data.Chars = new Character[] {32345, 12345, 0, 1, 63, Character.MAX_VALUE, Character.MIN_VALUE};
        data.Booleans = new Boolean[] {true, false};
    }

    static public class Data {
        public String string;
        public String[] strings;
        public int[] ints;
        public short[] shorts;
        public float[] floats;
        public double[] doubles;
        public long[] longs;
        public byte[] bytes;
        public char[] chars;
        public boolean[] booleans;
        public Integer[] Ints;
        public Short[] Shorts;
        public Float[] Floats;
        public Double[] Doubles;
        public Long[] Longs;
        public Byte[] Bytes;
        public Character[] Chars;
        public Boolean[] Booleans;
        public boolean isTCP;

        @Override
        public int hashCode () {
            final int prime = 31;
            int result = 1;
            result = prime * result + Arrays.hashCode(Booleans);
            result = prime * result + Arrays.hashCode(Bytes);
            result = prime * result + Arrays.hashCode(Chars);
            result = prime * result + Arrays.hashCode(Doubles);
            result = prime * result + Arrays.hashCode(Floats);
            result = prime * result + Arrays.hashCode(Ints);
            result = prime * result + Arrays.hashCode(Longs);
            result = prime * result + Arrays.hashCode(Shorts);
            result = prime * result + Arrays.hashCode(booleans);
            result = prime * result + Arrays.hashCode(bytes);
            result = prime * result + Arrays.hashCode(chars);
            result = prime * result + Arrays.hashCode(doubles);
            result = prime * result + Arrays.hashCode(floats);
            result = prime * result + Arrays.hashCode(ints);
            result = prime * result + (isTCP ? 1231 : 1237);
            result = prime * result + Arrays.hashCode(longs);
            result = prime * result + Arrays.hashCode(shorts);
            result = prime * result + (string == null ? 0 : string.hashCode());
            result = prime * result + Arrays.hashCode(strings);
            return result;
        }

        @Override
        public boolean equals (Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            Data other = (Data)obj;
            if (!Arrays.equals(Booleans, other.Booleans)) {
                return false;
            }
            if (!Arrays.equals(Bytes, other.Bytes)) {
                return false;
            }
            if (!Arrays.equals(Chars, other.Chars)) {
                return false;
            }
            if (!Arrays.equals(Doubles, other.Doubles)) {
                return false;
            }
            if (!Arrays.equals(Floats, other.Floats)) {
                return false;
            }
            if (!Arrays.equals(Ints, other.Ints)) {
                return false;
            }
            if (!Arrays.equals(Longs, other.Longs)) {
                return false;
            }
            if (!Arrays.equals(Shorts, other.Shorts)) {
                return false;
            }
            if (!Arrays.equals(booleans, other.booleans)) {
                return false;
            }
            if (!Arrays.equals(bytes, other.bytes)) {
                return false;
            }
            if (!Arrays.equals(chars, other.chars)) {
                return false;
            }
            if (!Arrays.equals(doubles, other.doubles)) {
                return false;
            }
            if (!Arrays.equals(floats, other.floats)) {
                return false;
            }
            if (!Arrays.equals(ints, other.ints)) {
                return false;
            }
            if (isTCP != other.isTCP) {
                return false;
            }
            if (!Arrays.equals(longs, other.longs)) {
                return false;
            }
            if (!Arrays.equals(shorts, other.shorts)) {
                return false;
            }
            if (string == null) {
                if (other.string != null) {
                    return false;
                }
            } else if (!string.equals(other.string)) {
                return false;
            }
            if (!Arrays.equals(strings, other.strings)) {
                return false;
            }
            return true;
        }

        @Override
        public String toString () {
            return "Data";
        }
    }
}
