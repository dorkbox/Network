Network
=======

The Network project is an encrypted, high-performance, event-driven/reactive Network stack with DNS and RMI, using Netty, Kryo, KryoNet RMI, and LZ4 via TCP/UDP/UDT. 

These are the main features:
- The connection between endpoints is AES256-GCM / EC curve25519.
- The connection data is LZ4 compressed and byte-packed for small payload sizes.
- The connection supports:
 - Remote Method Invocation
   - Blocking
   - Non-Blocking
   - Void returns
   - Exceptions can be returned
 - Sending data when Idle
 - "Pinging" the remote end (for measuring round-trip time)
 

- The available transports are TCP, UDP, and UDT
- There are simple wrapper classes for:
  - Server
  - Client
  - DNS Client (for querying DNS servers)
  - MultiCast Broadcast client and server discovery
  

- Note: There is a maximum packet size for UDP, 508 bytes *to guarantee it's unfragmented*

- This is for cross-platform use, specifically - linux 32/64, mac 32/64, and windows 32/64. Java 6+
    - Please note that Java6 runtimes have issues with their classloader loading classes recursively (you will get a StackOverflow exception). We have taken precautions to mitigate this, but be aware that it is a very real possibility. We recommend using Java7+ to prevent this issue.

``` java
public static
class AMessage {
    public
    AMessage() {
    }
}

KryoCryptoSerializationManager.DEFAULT.register(AMessage.class);

Configuration configuration = new Configuration();
configuration.tcpPort = tcpPort;
configuration.host = host;

final Server server = new Server(configuration);
addEndPoint(server);
server.bind(false);

server.listeners()
      .add(new Listener<AMessage>() {
          @Override
          public
          void received(Connection connection, AMessage object) {
              System.err.println("Server received message from client. Bouncing back.");
              connection.send()
                        .TCP(object);
          }
      });

Client client = new Client(configuration);
client.disableRemoteKeyValidation();
addEndPoint(client);
client.connect(5000);

client.listeners()
      .add(new Listener<AMessage>() {
          @Override
          public
          void received(Connection connection, AMessage object) {
              ClientSendTest.this.checkPassed.set(true);
              System.err.println("Tada! It's been bounced back.");
              server.stop();
          }
      });

client.send()
      .TCP(new AMessage());

```



<h4>(We now release to maven!</h4> 

There is a hard dependency in the POM file for the utilities library, which is an extremely small subset of a much larger library; including only what is *necessary* for this particular project to function.

This project is **kept in sync** with the utilities library, so "jar hell" is not an issue. Please note that the util library (in it's entirety) is not added since there are **many** dependencies that are not *necessary* for this project. No reason to require a massive amount of dependencies for one or two classes/methods. 
```
<dependency>
  <groupId>com.dorkbox</groupId>
  <artifactId>Network</artifactId>
  <version>1.17</version>
</dependency>
```

Or if you don't want to use Maven, you can access the files directly here:  
https://oss.sonatype.org/content/repositories/releases/com/dorkbox/Network/  
https://oss.sonatype.org/content/repositories/releases/com/dorkbox/Network-Dorkbox-Util/  


https://oss.sonatype.org/content/repositories/releases/com/dorkbox/ObjectPool/  
https://oss.sonatype.org/content/repositories/releases/com/dorkbox/MinLog-SLF4J/  

https://repo1.maven.org/maven2/org/slf4j/slf4j-api/  
https://repo1.maven.org/maven2/io/netty/netty-all/  (latest 4.1)  
https://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk15on/  
https://repo1.maven.org/maven2/org/bouncycastle/bcpkix-jdk15on/  
https://repo1.maven.org/maven2/asm/asm/  
https://repo1.maven.org/maven2/org/javassist/javassist/  
https://repo1.maven.org/maven2/com/esotericsoftware/jsonbeans/   


https://repo1.maven.org/maven2/com/esotericsoftware/reflectasm/  
https://repo1.maven.org/maven2/net/jpountz/lz4/lz4/    
https://repo1.maven.org/maven2/org/objenesis/objenesis/  
https://repo1.maven.org/maven2/com/esotericsoftware/kryo/  

<h2>License</h2>

This project is distributed under the terms of the Apache v2.0 License. See file "LICENSE" for further references.

