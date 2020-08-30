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
package dorkbox.network.rmi.messages

import dorkbox.network.rmi.RmiUtils


/**
 * These use packed IDs, because both are REALLY shorts, but the JVM deals better with ints.
 *
 * @param callbackId (LEFT) to know which callback to use when the object is created
 * @param rmiId (RIGHT) the Kryo interface class ID to create
 */
data class ConnectionObjectCreateResponse(val packedIds: Int) : RmiMessage {
    override fun toString(): String {
        return "ConnectionObjectCreateResponse(callbackId:${RmiUtils.unpackLeft(packedIds)} rmiId:${RmiUtils.unpackUnsignedRight(packedIds)})"
    }
}
