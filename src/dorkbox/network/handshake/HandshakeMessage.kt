/*
 * Copyright 2023 dorkbox, llc
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

import dorkbox.bytes.LittleEndian
import java.util.*

/**
 * Internal message to handle the connection registration process
 */
internal class HandshakeMessage private constructor() {

    // the public key is used to encrypt the data in the handshake
    var publicKey: ByteArray? = null


    // used to keep track and associate UDP/IPC handshakes between client/server
    // The connection info (session ID, etc) necessary to make a connection to the server are encrypted with the clients public key.
    // so EVEN IF you can guess someone's connectKey, you must also know their private key in order to connect as them.
    var connectKey = 0L

    // -1 means there is an error
    var state = INVALID

    var errorMessage: String? = null

    var port = 0
    var streamId = 0
    var sessionId = 0


    // the client sends its registration data to the server to make sure that the registered classes are the same between the client/server
    var registrationData: ByteArray? = null

    companion object {
        const val INVALID = -2
        const val RETRY = -1
        const val HELLO = 0
        const val HELLO_ACK = 1
        const val HELLO_ACK_IPC = 2
        const val DONE = 3
        const val DONE_ACK = 4

        private val uuidWriter: (UUID) -> ByteArray = { uuid ->
            val bytes = byteArrayOf(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0) // 16 elements
            LittleEndian.Long_.toBytes(uuid.mostSignificantBits, bytes, 0)
            LittleEndian.Long_.toBytes(uuid.leastSignificantBits, bytes, 8)
            bytes
        }

        internal val uuidReader: (ByteArray) -> UUID = { bytes ->
            UUID(LittleEndian.Long_.from(bytes, 0, 8),
                 LittleEndian.Long_.from(bytes, 8, 8))
        }

        fun helloFromClient(connectKey: Long, publicKey: ByteArray, streamIdSub: Int, portSub: Int, uuid: UUID): HandshakeMessage {
            val hello = HandshakeMessage()
            hello.state = HELLO
            hello.connectKey = connectKey // this is 'bounced back' by the server, so the client knows if it's the correct connection message
            hello.publicKey = publicKey
            hello.sessionId = 0 // not used by the server, since it connects in a different way!
            hello.streamId = streamIdSub
            hello.port = portSub
            hello.registrationData = uuidWriter(uuid)
            return hello
        }

        fun helloAckToClient(connectKey: Long): HandshakeMessage {
            val hello = HandshakeMessage()
            hello.state = HELLO_ACK
            hello.connectKey = connectKey // THIS MUST NEVER CHANGE! (the server/client expect this)
            return hello
        }

        fun helloAckIpcToClient(connectKey: Long): HandshakeMessage {
            val hello = HandshakeMessage()
            hello.state = HELLO_ACK_IPC
            hello.connectKey = connectKey // THIS MUST NEVER CHANGE! (the server/client expect this)
            return hello
        }

        fun doneFromClient(connectKey: Long, sessionIdSub: Int, streamIdSub: Int, portSub: Int): HandshakeMessage {
            val hello = HandshakeMessage()
            hello.state = DONE
            hello.connectKey = connectKey // THIS MUST NEVER CHANGE! (the server/client expect this)
            hello.sessionId = sessionIdSub
            hello.streamId = streamIdSub
            hello.port = portSub
            return hello
        }

        fun doneToClient(connectKey: Long): HandshakeMessage {
            val hello = HandshakeMessage()
            hello.state = DONE_ACK
            hello.connectKey = connectKey // THIS MUST NEVER CHANGE! (the server/client expect this)
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

        return "HandshakeMessage($stateStr$errorMsg sessionId=$sessionId, streamId=$streamId, port=$port)"
    }
}
