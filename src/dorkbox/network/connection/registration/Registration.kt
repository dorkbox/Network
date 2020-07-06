/*
 * Copyright 2010 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.network.connection.registration

/**
 * Internal message to handle the connection registration process
 */
class Registration private constructor() {
    // used to keep track and associate TCP/UDP/etc sessions. This is always defined by the server
    // a sessionId if '0', means we are still figuring it out.
    var oneTimePad = 0

    // -1 means there is an error
    var state = INVALID

    var errorMessage: String? = null
    var publicationPort = 0
    var subscriptionPort = 0
    var sessionId = 0
    var streamId = 0

    var publicKey: ByteArray? = null

    // by default, this will be a reliable connection. When the client connects to the server, the client will specify if the new connection
    // is a reliable/unreliable connection when setting up the MediaDriverConnection
    val isReliable = true


    // the client sends it's registration data to the server to make sure that the registered classes are the same between the client/server
    var registrationData: ByteArray? = null


    // NOTE: this is for ECDSA!
//    var eccParameters: IESParameters? = null

    // > 0 when we are ready to setup the connection (hasMore will always be false if this is >0). 0 when we are ready to connect
    // ALSO used if there are fragmented frames for registration data (since we have to split it up to fit inside a single UDP packet without fragmentation)
    var upgradeType = 0.toByte()

    // true when we are fully upgraded
    var upgraded = false

    companion object {
        const val INVALID = -1
        const val HELLO = 0
        const val HELLO_ACK = 1

        fun hello(oneTimePad: Int, publicKey: ByteArray, registrationData: ByteArray): Registration {
            val hello = Registration()
            hello.state = HELLO
            hello.oneTimePad = oneTimePad
            hello.publicKey = publicKey
            hello.registrationData = registrationData
            return hello
        }

        fun helloAck(oneTimePad: Int): Registration {
            val hello = Registration()
            hello.state = HELLO_ACK
            hello.oneTimePad = oneTimePad
            return hello
        }

        fun error(errorMessage: String?): Registration {
            val error = Registration()
            error.state = INVALID
            error.errorMessage = errorMessage
            return error
        }
    }
}
