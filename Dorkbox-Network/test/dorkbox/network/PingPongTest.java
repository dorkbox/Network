
package dorkbox.network;


import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.EndPoint;
import dorkbox.network.connection.Listener;
import dorkbox.network.util.SerializationManager;
import dorkbox.network.util.exceptions.InitializationException;
import dorkbox.network.util.exceptions.SecurityException;

public class PingPongTest extends BaseTest {
    private volatile String fail;

    int tries = 1000;

    enum TYPE {
        TCP, UDP, UDT
    }

    @Test
    public void pingPong () throws InitializationException, SecurityException {
        // UDP data is kinda big. Make sure it fits into one packet.
        int origSize = EndPoint.udpMaxSize;
        EndPoint.udpMaxSize = 2048;

        this.fail = "Data not received.";

        ConnectionOptions connectionOptions = new ConnectionOptions();
        connectionOptions.tcpPort = tcpPort;
        connectionOptions.udpPort = udpPort;
        connectionOptions.udtPort = udtPort;
        connectionOptions.host = host;

        final Data dataTCP = new Data();
        populateData(dataTCP, TYPE.TCP);
        final Data dataUDP = new Data();
        populateData(dataUDP, TYPE.UDP);
        final Data dataUDT = new Data();
        populateData(dataUDT, TYPE.UDT);

        Server server = new Server(connectionOptions);
        server.disableRemoteKeyValidation();
        addEndPoint(server);
        register(server.getSerialization());
        server.bind(false);
        server.listeners().add(new Listener<Data>() {
            @Override
            public void error(Connection connection, Throwable throwable) {
                PingPongTest.this.fail = "Error during processing. " + throwable;
            }

            @Override
            public void received (Connection connection, Data data) {
                if (data.type == TYPE.TCP) {
                    if (!data.equals(dataTCP)) {
                        PingPongTest.this.fail = "TCP data is not equal on server.";
                        throw new RuntimeException("Fail! " + PingPongTest.this.fail);
                    }
                    connection.send().TCP(data);
                }
                else if (data.type == TYPE.UDP) {
                    if (!data.equals(dataUDP)) {
                        PingPongTest.this.fail = "UDP data is not equal on server.";
                        throw new RuntimeException("Fail! " + PingPongTest.this.fail);
                    }
                    connection.send().UDP(data);
                }
                else if (data.type == TYPE.UDT) {
                    if (!data.equals(dataUDT)) {
                        PingPongTest.this.fail = "UDT data is not equal on server.";
                        throw new RuntimeException("Fail! " + PingPongTest.this.fail);
                    }
                    connection.send().UDT(data);
                }
                else {
                    PingPongTest.this.fail = "Unknown data type on server.";
                    throw new RuntimeException("Fail! " + PingPongTest.this.fail);
                }
            }
        });

        // ----

        Client client = new Client(connectionOptions);
        client.disableRemoteKeyValidation();
        addEndPoint(client);
        register(client.getSerialization());
        client.listeners().add(new Listener<Data>() {
            AtomicInteger checkTCP = new AtomicInteger(0);
            AtomicInteger checkUDP = new AtomicInteger(0);
            AtomicInteger checkUDT = new AtomicInteger(0);
            AtomicBoolean doneTCP = new AtomicBoolean(false);
            AtomicBoolean doneUDP = new AtomicBoolean(false);
            AtomicBoolean doneUDT = new AtomicBoolean(false);

            @Override
            public void connected (Connection connection) {
                PingPongTest.this.fail = null;
                connection.send().TCP(dataTCP);
                connection.send().UDP(dataUDP); // UDP ping pong stops if a UDP packet is lost.
                connection.send().UDT(dataUDT);
            }

            @Override
            public void error(Connection connection, Throwable throwable) {
                PingPongTest.this.fail = "Error during processing. " + throwable;
                throwable.printStackTrace();
            }

            @Override
            public void received (Connection connection, Data data) {
                if (data.type == TYPE.TCP) {
                    if (!data.equals(dataTCP)) {
                        PingPongTest.this.fail = "TCP data is not equal on client.";
                        throw new RuntimeException("Fail! " + PingPongTest.this.fail);
                    }
                    if (this.checkTCP.getAndIncrement() <= PingPongTest.this.tries) {
                        connection.send().TCP(data);
                    } else {
                        System.err.println("TCP done.");
                        this.doneTCP.set(true);
                    }
                } else if (data.type == TYPE.UDP) {
                    if (!data.equals(dataUDP)) {
                        PingPongTest.this.fail = "UDP data is not equal on client.";
                        throw new RuntimeException("Fail! " + PingPongTest.this.fail);
                    }
                    if (this.checkUDP.getAndIncrement() <= PingPongTest.this.tries) {
                        connection.send().UDP(data);
                    } else {
                        System.err.println("UDP done.");
                        this.doneUDP.set(true);
                    }
                } else if (data.type == TYPE.UDT) {
                    if (!data.equals(dataUDT)) {
                        PingPongTest.this.fail = "UDT data is not equal on client.";
                        throw new RuntimeException("Fail! " + PingPongTest.this.fail);
                    }
                    if (this.checkUDT.getAndIncrement() <= PingPongTest.this.tries) {
                        connection.send().UDT(data);
                    } else {
                        System.err.println("UDT done.");
                        this.doneUDT.set(true);
                    }
                } else {
                    PingPongTest.this.fail = "Unknown data type on client.";
                    throw new RuntimeException("Fail! " + PingPongTest.this.fail);
                }

                if (this.doneTCP.get() && this.doneUDP.get() && this.doneUDT.get()) {
                    System.err.println("Ran TCP, UDP, UDT " + PingPongTest.this.tries + " times each");
                    stopEndPoints();
                }
            }
        });

        client.connect(5000);

        waitForThreads();

        if (this.fail != null) {
            fail(this.fail);
        }

        EndPoint.udpMaxSize = origSize;
    }

    private void populateData (Data data, TYPE type) {
        data.type = type;

        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            buffer.append('a');
        }
        data.string = buffer.toString();

        data.strings = new String[] {"abcdefghijklmnopqrstuvwxyz0123456789", "", null, "!@#$", "�����"};
        data.ints = new int[] {-1234567, 1234567, -1, 0, 1, Integer.MAX_VALUE, Integer.MIN_VALUE};
        data.shorts = new short[] {-12345, 12345, -1, 0, 1, Short.MAX_VALUE, Short.MIN_VALUE};
        data.floats = new float[] {0, -0, 1, -1, 123456, -123456, 0.1f, 0.2f, -0.3f, (float)Math.PI, Float.MAX_VALUE, Float.MIN_VALUE};

        data.doubles = new double[] {0, -0, 1, -1, 123456, -123456, 0.1d, 0.2d, -0.3d, Math.PI, Double.MAX_VALUE, Double.MIN_VALUE};
        data.longs = new long[] {0, -0, 1, -1, 123456, -123456, 99999999999l, -99999999999l, Long.MAX_VALUE, Long.MIN_VALUE};
        data.bytes = new byte[] {-123, 123, -1, 0, 1, Byte.MAX_VALUE, Byte.MIN_VALUE};
        data.chars = new char[] {32345, 12345, 0, 1, 63, Character.MAX_VALUE, Character.MIN_VALUE};

        data.booleans = new boolean[] {true, false};
        data.Ints = new Integer[] {-1234567, 1234567, -1, 0, 1, Integer.MAX_VALUE, Integer.MIN_VALUE};
        data.Shorts = new Short[] {-12345, 12345, -1, 0, 1, Short.MAX_VALUE, Short.MIN_VALUE};
        data.Floats = new Float[] {0f, -0f, 1f, -1f, 123456f, -123456f, 0.1f, 0.2f, -0.3f, (float)Math.PI, Float.MAX_VALUE, Float.MIN_VALUE};
        data.Doubles = new Double[] {0d, -0d, 1d, -1d, 123456d, -123456d, 0.1d, 0.2d, -0.3d, Math.PI, Double.MAX_VALUE, Double.MIN_VALUE};
        data.Longs = new Long[] {0l, -0l, 1l, -1l, 123456l, -123456l, 99999999999l, -99999999999l, Long.MAX_VALUE, Long.MIN_VALUE};
        data.Bytes = new Byte[] {-123, 123, -1, 0, 1, Byte.MAX_VALUE, Byte.MIN_VALUE};
        data.Chars = new Character[] {32345, 12345, 0, 1, 63, Character.MAX_VALUE, Character.MIN_VALUE};
        data.Booleans = new Boolean[] {true, false};
    }

    private void register (SerializationManager kryoMT) {
        kryoMT.register(int[].class);
        kryoMT.register(short[].class);
        kryoMT.register(float[].class);
        kryoMT.register(double[].class);
        kryoMT.register(long[].class);
        kryoMT.register(byte[].class);
        kryoMT.register(char[].class);
        kryoMT.register(boolean[].class);
        kryoMT.register(String[].class);
        kryoMT.register(Integer[].class);
        kryoMT.register(Short[].class);
        kryoMT.register(Float[].class);
        kryoMT.register(Double[].class);
        kryoMT.register(Long[].class);
        kryoMT.register(Byte[].class);
        kryoMT.register(Character[].class);
        kryoMT.register(Boolean[].class);
        kryoMT.register(Data.class);
        kryoMT.register(TYPE.class);
    }

    static public class Data {
        public TYPE type;
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


        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + Arrays.hashCode(this.Booleans);
            result = prime * result + Arrays.hashCode(this.Bytes);
            result = prime * result + Arrays.hashCode(this.Chars);
            result = prime * result + Arrays.hashCode(this.Doubles);
            result = prime * result + Arrays.hashCode(this.Floats);
            result = prime * result + Arrays.hashCode(this.Ints);
            result = prime * result + Arrays.hashCode(this.Longs);
            result = prime * result + Arrays.hashCode(this.Shorts);
            result = prime * result + Arrays.hashCode(this.booleans);
            result = prime * result + Arrays.hashCode(this.bytes);
            result = prime * result + Arrays.hashCode(this.chars);
            result = prime * result + Arrays.hashCode(this.doubles);
            result = prime * result + Arrays.hashCode(this.floats);
            result = prime * result + Arrays.hashCode(this.ints);
            result = prime * result + Arrays.hashCode(this.longs);
            result = prime * result + Arrays.hashCode(this.shorts);
            result = prime * result + (this.string == null ? 0 : this.string.hashCode());
            result = prime * result + Arrays.hashCode(this.strings);
            result = prime * result + (this.type == null ? 0 : this.type.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            Data other = (Data) obj;
            if (!Arrays.equals(this.Booleans, other.Booleans)) {
                return false;
            }
            if (!Arrays.equals(this.Bytes, other.Bytes)) {
                return false;
            }
            if (!Arrays.equals(this.Chars, other.Chars)) {
                return false;
            }
            if (!Arrays.equals(this.Doubles, other.Doubles)) {
                return false;
            }
            if (!Arrays.equals(this.Floats, other.Floats)) {
                return false;
            }
            if (!Arrays.equals(this.Ints, other.Ints)) {
                return false;
            }
            if (!Arrays.equals(this.Longs, other.Longs)) {
                return false;
            }
            if (!Arrays.equals(this.Shorts, other.Shorts)) {
                return false;
            }
            if (!Arrays.equals(this.booleans, other.booleans)) {
                return false;
            }
            if (!Arrays.equals(this.bytes, other.bytes)) {
                return false;
            }
            if (!Arrays.equals(this.chars, other.chars)) {
                return false;
            }
            if (!Arrays.equals(this.doubles, other.doubles)) {
                return false;
            }
            if (!Arrays.equals(this.floats, other.floats)) {
                return false;
            }
            if (!Arrays.equals(this.ints, other.ints)) {
                return false;
            }
            if (!Arrays.equals(this.longs, other.longs)) {
                return false;
            }
            if (!Arrays.equals(this.shorts, other.shorts)) {
                return false;
            }
            if (this.string == null) {
                if (other.string != null) {
                    return false;
                }
            } else if (!this.string.equals(other.string)) {
                return false;
            }
            if (!Arrays.equals(this.strings, other.strings)) {
                return false;
            }
            if (this.type != other.type) {
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
