Network
=======

###### [![Dorkbox](https://badge.dorkbox.com/dorkbox.svg "Dorkbox")](https://git.dorkbox.com/dorkbox/Network) [![Github](https://badge.dorkbox.com/github.svg "Github")](https://github.com/dorkbox/Network) [![Gitlab](https://badge.dorkbox.com/gitlab.svg "Gitlab")](https://gitlab.com/dorkbox/Network) [![Bitbucket](https://badge.dorkbox.com/bitbucket.svg "Bitbucket")](https://bitbucket.org/dorkbox/Network)


The Network project is an encrypted, high-performance, event-driven/reactive Network stack with DNS and RMI, using Netty, Kryo, KryoNet RMI, and LZ4 via TCP/UDP. 

These are the main features:
* The connection between endpoints is AES256-GCM / EC curve25519. (WIP, this was updated for use with Aeron, which changes this)
* The connection data is LZ4 compressed and byte-packed for small payload sizes. (WIP, this was updated for use with Aeron, which 
  changes this)
- The connection supports:
 - Remote Method Invocation
   - Blocking
   - Non-Blocking
   - Void returns
   - Exceptions can be returned
   - Kotlin coroutine suspend functions
 - Sending data when Idle
 - "Pinging" the remote end (for measuring round-trip time)
 - Firewall connections by IP+CIDR
 - Specify the connection type (nothing, compress, compress+encrypt)
 

- The available transports are TCP and UDP
- There are simple wrapper classes for:
  - Server
  - Client
  * MultiCast Broadcast client and server discovery (WIP, this was updated for use with Aeron, which changes this)
  

- Note: There is a maximum packet size for UDP, 508 bytes *to guarantee it's unfragmented*

- This is for cross-platform use, specifically - linux 32/64, mac 64, and windows 32/64. Java 1.8+
    
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

&nbsp; 
&nbsp; 

Maven Info
---------
```
<dependencies>
    ...
    <dependency>
      <groupId>com.dorkbox</groupId>
      <artifactId>Network</artifactId>
      <version>5.17</version>
    </dependency>
</dependencies>
```

Gradle Info
---------
```
dependencies {
    ...
    implementation("com.dorkbox:Network:5.17")
}
```

License
---------
This project is © 2021 dorkbox llc, and is distributed under the terms of the Apache v2.0 License. See file "LICENSE" for further 
references.
