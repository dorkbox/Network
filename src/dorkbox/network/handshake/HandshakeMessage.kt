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
    var oneTimeKey = 0

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
        const val INVALID = -2
        const val RETRY = -1
        const val HELLO = 0
        const val HELLO_ACK = 1
        const val HELLO_ACK_IPC = 2
        const val DONE = 3
        const val DONE_ACK = 4

        fun helloFromClient(oneTimeKey: Int, publicKey: ByteArray): HandshakeMessage {
            val hello = HandshakeMessage()
            hello.state = HELLO
            hello.oneTimeKey = oneTimeKey
            hello.publicKey = publicKey
            return hello
        }

        fun helloAckToClient(oneTimeKey: Int, sessionId: Int): HandshakeMessage {
            val hello = HandshakeMessage()
            hello.state = HELLO_ACK
            hello.oneTimeKey = oneTimeKey // has to be the same as before (the client expects this)
            hello.sessionId = sessionId
            return hello
        }

        fun helloAckIpcToClient(oneTimeKey: Int, sessionId: Int): HandshakeMessage {
            val hello = HandshakeMessage()
            hello.state = HELLO_ACK_IPC
            hello.oneTimeKey = oneTimeKey // has to be the same as before (the client expects this)
            hello.sessionId = sessionId
            return hello
        }

        fun doneFromClient(oneTimeKey: Int): HandshakeMessage {
            val hello = HandshakeMessage()
            hello.state = DONE
            hello.oneTimeKey = oneTimeKey
            return hello
        }

        fun doneToClient(oneTimeKey: Int, sessionId: Int): HandshakeMessage {
            val hello = HandshakeMessage()
            hello.state = DONE_ACK
            hello.oneTimeKey = oneTimeKey // has to be the same as before (the client expects this)
            hello.sessionId = sessionId
            return hello
        }

        fun error(errorMessage: String): HandshakeMessage {
            val error = HandshakeMessage()
            error.state = INVALID
            error.errorMessage = errorMessage
            return error
        }

        fun retry(errorMessage: String): HandshakeMessage {
            val error = HandshakeMessage()
            error.state = RETRY
            error.errorMessage = errorMessage
            return error
        }

        fun toStateString(state: Int) : String {
            return when(state) {
                INVALID -> "INVALID"
                RETRY -> "RETRY"
                HELLO -> "HELLO"
                HELLO_ACK -> "HELLO_ACK"
                HELLO_ACK_IPC -> "HELLO_ACK_IPC"
                DONE -> "DONE"
                DONE_ACK -> "DONE_ACK"
                else -> "ERROR. THIS SHOULD NEVER HAPPEN FOR STATE!"
            }
        }
    }

    override fun toString(): String {
        val stateStr = toStateString(state)

        val errorMsg = if (errorMessage == null) {
            ""
        } else {
            ", Error: $errorMessage"
        }


        return "HandshakeMessage($sessionId : oneTimePad=$oneTimeKey $stateStr$errorMsg)"
    }
}
