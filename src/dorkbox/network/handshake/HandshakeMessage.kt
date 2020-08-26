/*
 * Copyright 2020 dorkbox, llc
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
package dorkbox.network.handshake

/**
 * Internal message to handle the connection registration process
 */
internal class HandshakeMessage private constructor() {

    // the public key is used to encrypt the data in the handshake
    var publicKey: ByteArray? = null


    // used to keep track and associate UDP/etc sessions. This is always defined by the server
    // a sessionId if '0', means we are still figuring it out.
    var oneTimePad = 0

    // -1 means there is an error
    var state = INVALID

    var errorMessage: String? = null

    var publicationPort = 0
    var subscriptionPort = 0
    var sessionId = 0
    var streamId = 0



    // by default, this will be a reliable connection. When the client connects to the server, the client will specify if the new connection
    // is a reliable/unreliable connection when setting up the MediaDriverConnection
    val isReliable = true


    // the client sends it's registration data to the server to make sure that the registered classes are the same between the client/server
    var registrationData: ByteArray? = null
    var registrationRmiIdData: IntArray? = null

    companion object {
        const val INVALID = -1
        const val HELLO = 0
        const val HELLO_ACK = 1
        const val DONE = 2
        const val DONE_ACK = 3

        fun helloFromClient(oneTimePad: Int, publicKey: ByteArray, registrationData: ByteArray, registrationRmiIdData: IntArray): HandshakeMessage {
            val hello = HandshakeMessage()
            hello.state = HELLO
            hello.oneTimePad = oneTimePad
            hello.publicKey = publicKey
            hello.registrationData = registrationData
            hello.registrationRmiIdData = registrationRmiIdData
            return hello
        }

        fun helloAckToClient(sessionId: Int): HandshakeMessage {
            val hello = HandshakeMessage()
            hello.state = HELLO_ACK
            hello.sessionId = sessionId // has to be the same as before (the client expects this)
            return hello
        }

        fun doneFromClient(): HandshakeMessage {
            val hello = HandshakeMessage()
            hello.state = DONE
            return hello
        }

        fun doneToClient(sessionId: Int): HandshakeMessage {
            val hello = HandshakeMessage()
            hello.state = DONE_ACK
            hello.sessionId = sessionId
            return hello
        }

        fun error(errorMessage: String): HandshakeMessage {
            val error = HandshakeMessage()
            error.state = INVALID
            error.errorMessage = errorMessage
            return error
        }
    }

    override fun toString(): String {
        val stateStr = when(state) {
            INVALID -> "INVALID"
            HELLO -> "HELLO"
            HELLO_ACK -> "HELLO_ACK"
            DONE -> "DONE"
            DONE_ACK -> "DONE_ACK"
            else -> "ERROR. THIS SHOULD NEVER HAPPEN FOR STATE!"
        }

        val errorMsg = if (errorMessage == null) {
            ""
        } else {
            ", Error: $errorMessage"
        }


        return "HandshakeMessage(oneTimePad=$oneTimePad, sid= $sessionId $stateStr$errorMsg)"
    }
}
