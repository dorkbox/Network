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

package dorkbox.network.connection.session

/**
 * A session will persist across stop/reconnect/etc, as long as the client/server ID remains the same.
 *
 * If the client/server are closed for longer than XXX seconds, then the connection will be considered closed, and the session will be cleared.
 *
 * The sessionId is the public key of the remote endpoint.
 */
class Session(val sessionId: ByteArray) {



}
