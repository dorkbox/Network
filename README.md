Network
=======

###### [![Dorkbox](https://badge.dorkbox.com/dorkbox.svg "Dorkbox")](https://git.dorkbox.com/dorkbox/Network) [![Github](https://badge.dorkbox.com/github.svg "Github")](https://github.com/dorkbox/Network) [![Gitlab](https://badge.dorkbox.com/gitlab.svg "Gitlab")](https://gitlab.com/dorkbox/Network)


The Network project is an ~~encrypted~~, high-performance, event-driven/reactive Network stack with DNS and RMI, using Aeron, Kryo, KryoNet RMI, ~~encryption and LZ4 via UDP.~~ 

These are the main features:

~~* The connection between endpoints is AES256-GCM / EC curve25519. (WIP, this was updated for use with Aeron, which changes this)~~
~~* The connection data is LZ4 compressed and byte-packed for small payload sizes. (WIP, this was updated for use with Aeron, which 
  changes this)~~
### The connection supports:
 - Sending object (via the Kryo serialization framework)
 - Sending arbitrarily large objects
 - Remote Method Invocation
   - Blocking
   - Non-Blocking
   - Void returns
   - Exceptions can be returned
   - Kotlin coroutine suspend functions
 - ~~Sending data when Idle~~
 - "Pinging" the remote end (for measuring round-trip time)
 - Firewall connections by IP+CIDR
 - ~~Specify the connection type (nothing, compress, compress+encrypt)~~
 
- The available transports is UDP

- This is for cross-platform use, specifically - linux 32/64, mac 64, and windows 32/64. Java 1.11+
- This library is designed to be used with kotlin, specifically the use of coroutines.
    
``` java
val configurationServer = ServerConfiguration()
configurationServer.settingsStore = Storage.Memory() // don't want to persist anything on disk!
configurationServer.port = 2000
configurationServer.enableIPv4 = true

val server: Server<Connection> = Server(configurationServer)

server.onMessage<String> { message ->
    logger.error("Received message '$message'")
}

server.bind()



val configurationClient = ClientConfiguration()
configurationClient.settingsStore = Storage.Memory() // don't want to persist anything on disk!
configurationClient.port = 2000

val client: Client<Connection> = Client(configurationClient)

client.onConnect {
    send("client test message")
}

client.connect()

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
      <version>6.8</version>
    </dependency>
</dependencies>
```

Gradle Info
---------
```
dependencies {
    ...
    implementation("com.dorkbox:Network:6.8")
}
```

License
---------
This project is © 2023 dorkbox llc, and is distributed under the terms of the Apache v2.0 License. See file "LICENSE" for further 
references.
