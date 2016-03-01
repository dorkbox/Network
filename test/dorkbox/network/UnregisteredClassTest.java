package dorkbox.network;


import dorkbox.network.connection.Connection;
import dorkbox.network.connection.EndPoint;
import dorkbox.network.connection.KryoCryptoSerializationManager;
import dorkbox.network.connection.Listener;
import dorkbox.util.exceptions.InitializationException;
import dorkbox.util.exceptions.SecurityException;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.fail;

public
class UnregisteredClassTest extends BaseTest {
    private String fail;
    private int tries = 10000;

    private AtomicInteger receivedTCP = new AtomicInteger();
    private AtomicInteger receivedUDP = new AtomicInteger();

    @Test
    public
    void unregisteredClasses() throws InitializationException, SecurityException, IOException, InterruptedException {
        KryoCryptoSerializationManager.DEFAULT = KryoCryptoSerializationManager.DEFAULT(false, false);

        int origSize = EndPoint.udpMaxSize;
        EndPoint.udpMaxSize = 2048;

        Configuration configuration = new Configuration();
        configuration.tcpPort = tcpPort;
        configuration.udpPort = udpPort;
        configuration.host = host;

        System.err.println("Running test " + this.tries + " times, please wait for it to finish.");

        final Data dataTCP = new Data();
        populateData(dataTCP, true);
        final Data dataUDP = new Data();
        populateData(dataUDP, false);

        Server server = new Server(configuration);
        server.disableRemoteKeyValidation();
        addEndPoint(server);
        server.bind(false);
        server.listeners()
              .add(new Listener<Data>() {
                  @Override
                  public
                  void error(Connection connection, Throwable throwable) {
                      UnregisteredClassTest.this.fail = "Error during processing. " + throwable;
                  }

                  @Override
                  public
                  void received(Connection connection, Data data) {
                      if (data.isTCP) {
                          if (!data.equals(dataTCP)) {
                              UnregisteredClassTest.this.fail = "TCP data is not equal on server.";
                              throw new RuntimeException("Fail! " + UnregisteredClassTest.this.fail);
                          }
                          connection.send()
                                    .TCP(data);
                          UnregisteredClassTest.this.receivedTCP.incrementAndGet();
                      }
                      else {
                          if (!data.equals(dataUDP)) {
                              UnregisteredClassTest.this.fail = "UDP data is not equal on server.";
                              throw new RuntimeException("Fail! " + UnregisteredClassTest.this.fail);
                          }
                          connection.send()
                                    .UDP(data);
                          UnregisteredClassTest.this.receivedUDP.incrementAndGet();
                      }
                  }
              });

        // ----

        Client client = new Client(configuration);
        client.disableRemoteKeyValidation();
        addEndPoint(client);
        client.listeners()
              .add(new Listener<Data>() {
                  AtomicInteger checkTCP = new AtomicInteger(0);
                  AtomicInteger checkUDP = new AtomicInteger(0);
                  AtomicBoolean doneTCP = new AtomicBoolean(false);
                  AtomicBoolean doneUDP = new AtomicBoolean(false);

                  @Override
                  public
                  void connected(Connection connection) {
                      UnregisteredClassTest.this.fail = null;
                      connection.send()
                                .TCP(dataTCP);
                      connection.send()
                                .UDP(dataUDP); // UDP ping pong stops if a UDP packet is lost.
                  }

                  @Override
                  public
                  void error(Connection connection, Throwable throwable) {
                      UnregisteredClassTest.this.fail = "Error during processing. " + throwable;
                      System.err.println(UnregisteredClassTest.this.fail);
                  }

                  @Override
                  public
                  void received(Connection connection, Data data) {
                      if (data.isTCP) {
                          if (!data.equals(dataTCP)) {
                              UnregisteredClassTest.this.fail = "TCP data is not equal on client.";
                              throw new RuntimeException("Fail! " + UnregisteredClassTest.this.fail);
                          }
                          if (this.checkTCP.getAndIncrement() <= UnregisteredClassTest.this.tries) {
                              connection.send()
                                        .TCP(data);
                              UnregisteredClassTest.this.receivedTCP.incrementAndGet();
                          }
                          else {
                              System.err.println("TCP done.");
                              this.doneTCP.set(true);
                          }
                      }
                      else {
                          if (!data.equals(dataUDP)) {
                              UnregisteredClassTest.this.fail = "UDP data is not equal on client.";
                              throw new RuntimeException("Fail! " + UnregisteredClassTest.this.fail);
                          }
                          if (this.checkUDP.getAndIncrement() <= UnregisteredClassTest.this.tries) {
                              connection.send()
                                        .UDP(data);
                              UnregisteredClassTest.this.receivedUDP.incrementAndGet();
                          }
                          else {
                              System.err.println("UDP done.");
                              this.doneUDP.set(true);
                          }
                      }

                      if (this.doneTCP.get() && this.doneUDP.get()) {
                          System.err.println("Ran TCP & UDP " + UnregisteredClassTest.this.tries + " times each");
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

    private
    void populateData(Data data, boolean isTCP) {
        data.isTCP = isTCP;

        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            buffer.append('a');
        }
        data.string = buffer.toString();

        data.strings = new String[] {"ab012", "", null, "!@#$", "�����"};
        data.ints = new int[] {-1234567, 1234567, -1, 0, 1, Integer.MAX_VALUE, Integer.MIN_VALUE};
        data.shorts = new short[] {-12345, 12345, -1, 0, 1, Short.MAX_VALUE, Short.MIN_VALUE};
        data.floats = new float[] {0, -0, 1, -1, 123456, -123456, 0.1f, 0.2f, -0.3f, (float) Math.PI, Float.MAX_VALUE, Float.MIN_VALUE};
        data.doubles = new double[] {0, -0, 1, -1, 123456, -123456, 0.1d, 0.2d, -0.3d, Math.PI, Double.MAX_VALUE, Double.MIN_VALUE};
        data.longs = new long[] {0, -0, 1, -1, 123456, -123456, 99999999999l, -99999999999l, Long.MAX_VALUE, Long.MIN_VALUE};
        data.bytes = new byte[] {-123, 123, -1, 0, 1, Byte.MAX_VALUE, Byte.MIN_VALUE};
        data.chars = new char[] {32345, 12345, 0, 1, 63, Character.MAX_VALUE, Character.MIN_VALUE};
        data.booleans = new boolean[] {true, false};
        data.Ints = new Integer[] {-1234567, 1234567, -1, 0, 1, Integer.MAX_VALUE, Integer.MIN_VALUE};
        data.Shorts = new Short[] {-12345, 12345, -1, 0, 1, Short.MAX_VALUE, Short.MIN_VALUE};
        data.Floats = new Float[] {0f, -0f, 1f, -1f, 123456f, -123456f, 0.1f, 0.2f, -0.3f, (float) Math.PI, Float.MAX_VALUE,
                                   Float.MIN_VALUE};
        data.Doubles = new Double[] {0d, -0d, 1d, -1d, 123456d, -123456d, 0.1d, 0.2d, -0.3d, Math.PI, Double.MAX_VALUE, Double.MIN_VALUE};
        data.Longs = new Long[] {0l, -0l, 1l, -1l, 123456l, -123456l, 99999999999l, -99999999999l, Long.MAX_VALUE, Long.MIN_VALUE};
        data.Bytes = new Byte[] {-123, 123, -1, 0, 1, Byte.MAX_VALUE, Byte.MIN_VALUE};
        data.Chars = new Character[] {32345, 12345, 0, 1, 63, Character.MAX_VALUE, Character.MIN_VALUE};
        data.Booleans = new Boolean[] {true, false};
    }

    static public
    class Data {
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
        public
        int hashCode() {
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
            result = prime * result + (this.isTCP ? 1231 : 1237);
            result = prime * result + Arrays.hashCode(this.longs);
            result = prime * result + Arrays.hashCode(this.shorts);
            result = prime * result + (this.string == null ? 0 : this.string.hashCode());
            result = prime * result + Arrays.hashCode(this.strings);
            return result;
        }

        @Override
        public
        boolean equals(Object obj) {
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
            if (this.isTCP != other.isTCP) {
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
            }
            else if (!this.string.equals(other.string)) {
                return false;
            }
            if (!Arrays.equals(this.strings, other.strings)) {
                return false;
            }
            return true;
        }

        @Override
        public
        String toString() {
            return "Data";
        }
    }
}
