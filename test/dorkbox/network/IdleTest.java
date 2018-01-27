/* Copyright (c) 2008, Nathan Sweet
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided with the distribution.
 * - Neither the name of Esoteric Software nor the names of its contributors may be used to endorse or promote products derived
 * from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,
 * BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT
 * SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package dorkbox.network;

import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

import org.junit.Test;

import dorkbox.network.PingPongTest.TYPE;
import dorkbox.network.connection.Connection;
import dorkbox.network.connection.Listener;
import dorkbox.network.connection.idle.*;
import dorkbox.network.serialization.Serialization;
import dorkbox.util.exceptions.SecurityException;
import dorkbox.util.serialization.SerializationManager;

@SuppressWarnings({"rawtypes"})
public
class IdleTest extends BaseTest {
    private volatile boolean success = false;


    enum ConnectionType {
        TCP,
        UDP
    }

    @Test
    public
    void InputStreamSender() throws SecurityException, IOException {
        final int largeDataSize = 12345;

        System.err.println("-- TCP");
        Configuration configuration = new Configuration();
        configuration.tcpPort = tcpPort;
        configuration.host = host;
        configuration.serialization = Serialization.DEFAULT(false, false, true, null);

        streamSpecificType(largeDataSize, configuration, ConnectionType.TCP);


        System.err.println("-- UDP");
        configuration = new Configuration();
        configuration.tcpPort = tcpPort;
        configuration.udpPort = udpPort;
        configuration.host = host;
        configuration.serialization = Serialization.DEFAULT(false, false, true, null);

        streamSpecificType(largeDataSize, configuration, ConnectionType.UDP);
    }



    // have to test sending objects
    @Test
    public
    void ObjectSender() throws SecurityException, IOException {
        final Data mainData = new Data();
        populateData(mainData);


        System.err.println("-- TCP");
        Configuration configuration = new Configuration();
        configuration.tcpPort = tcpPort;
        configuration.host = host;
        configuration.serialization = Serialization.DEFAULT();
        register(configuration.serialization);

        sendObject(mainData, configuration, ConnectionType.TCP);


        System.err.println("-- UDP");
        configuration = new Configuration();
        configuration.tcpPort = tcpPort;
        configuration.udpPort = udpPort;
        configuration.host = host;
        configuration.serialization = Serialization.DEFAULT();
        register(configuration.serialization);

        sendObject(mainData, configuration, ConnectionType.TCP);
    }



    private
    void sendObject(final Data mainData, Configuration configuration, final ConnectionType type)
                    throws SecurityException, IOException {

        Server server = new Server(configuration);
        addEndPoint(server);
        server.setIdleTimeout(100);
        server.bind(false);
        server.listeners()
              .add(new Listener.OnConnected<Connection>() {

                  @Override
                  public
                  void connected(Connection connection) {
                      IdleBridge sendOnIdle = connection.sendOnIdle(mainData);

                      switch (type) {
                          case TCP:
                              sendOnIdle.TCP();
                              break;
                          case UDP:
                              sendOnIdle.UDP();
                              break;
                      }
                  }
              });

        // ----

        Client client = new Client(configuration);
        addEndPoint(client);
        client.listeners()
              .add(new Listener.OnMessageReceived<Connection, Data>() {
                  @Override
                  public
                  void received(Connection connection, Data object) {
                      if (mainData.equals(object)) {
                          IdleTest.this.success = true;
                      }

                      System.err.println("finished!");
                      stopEndPoints();
                  }
              });

        client.connect(5000);

        waitForThreads();
        if (!this.success) {
            fail();
        }
    }



    private
    void streamSpecificType(final int largeDataSize, Configuration configuration, final ConnectionType type)
                    throws SecurityException, IOException {
        Server server = new Server(configuration);
        addEndPoint(server);
        server.setIdleTimeout(100);
        server.bind(false);
        server.listeners()
              .add(new Listener.OnConnected<Connection>() {
                  @Override
                  public
                  void connected(Connection connection) {
                      ByteArrayOutputStream output = new ByteArrayOutputStream(largeDataSize);
                      for (int i = 0; i < largeDataSize; i++) {
                          output.write(i);
                      }

                      ByteArrayInputStream input = new ByteArrayInputStream(output.toByteArray());

                      IdleListener<Connection, byte[]> listener = null;
                      switch (type) {
                          case TCP:
                              listener = new IdleListenerTCP<Connection, byte[]>();
                              break;
                          case UDP:
                              listener = new IdleListenerUDP<Connection, byte[]>();
                              break;
                      }


                      // Send data in 512 byte chunks.
                      IdleBridge sendOnIdle = connection.sendOnIdle(new InputStreamSender<Connection>(listener, input, 512) {
                          @Override
                          protected
                          void start() {
                              // Normally would send an object so the receiving side knows how to handle the chunks we are about to send.
                              System.err.println("starting");
                          }

                          @Override
                          protected
                          byte[] onNext(byte[] bytes) {
                              //System.out.println("sending " + bytes.length);
                              return bytes; // Normally would wrap the byte[] with an object so the receiving side knows how to handle it.
                          }
                      });

                      switch (type) {
                          case TCP:
                              sendOnIdle.TCP();
                              break;
                          case UDP:
                              sendOnIdle.UDP();
                              break;
                      }
                  }
              });

        // ----

        Client client = new Client(configuration);
        addEndPoint(client);
        client.listeners()
              .add(new Listener.OnMessageReceived<Connection, byte[]>() {
                  int total;

                  @Override
                  public
                  void received(Connection connection, byte[] object) {
                      int length = object.length;
                      //System.err.println("received " + length);
                      this.total += length;
                      if (this.total == largeDataSize) {
                          IdleTest.this.success = true;
                          System.err.println("finished!");
                          stopEndPoints();
                      }
                  }
              });

        client.connect(5000);

        waitForThreads();
        if (!this.success) {
            fail();
        }
    }


    private static
    void populateData(Data data) {
        StringBuilder buffer = new StringBuilder(3001);
        for (int i = 0; i < 3000; i++) {
            buffer.append('a');
        }
        data.string = buffer.toString();

        data.strings = new String[] {"abcdefghijklmnopqrstuvwxyz0123456789", "", null, "!@#$", "�����"};
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

    private static
    void register(SerializationManager manager) {
        manager.register(int[].class);
        manager.register(short[].class);
        manager.register(float[].class);
        manager.register(double[].class);
        manager.register(long[].class);
        manager.register(byte[].class);
        manager.register(char[].class);
        manager.register(boolean[].class);
        manager.register(String[].class);
        manager.register(Integer[].class);
        manager.register(Short[].class);
        manager.register(Float[].class);
        manager.register(Double[].class);
        manager.register(Long[].class);
        manager.register(Byte[].class);
        manager.register(Character[].class);
        manager.register(Boolean[].class);
        manager.register(Data.class);
        manager.register(TYPE.class);
    }

    public static
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
