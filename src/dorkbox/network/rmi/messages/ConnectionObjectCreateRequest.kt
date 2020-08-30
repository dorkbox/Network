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
 * @param interfaceClassId (RIGHT) the Kryo interface class ID to create
 * @param objectParameters the constructor parameters to create the object with
 */
data class ConnectionObjectCreateRequest(val packedIds: Int, val objectParameters: Array<Any?>?) : RmiMessage {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ConnectionObjectCreateRequest

        if (packedIds != other.packedIds) return false

        return true
    }

    override fun hashCode(): Int {
        return packedIds
    }

    override fun toString(): String {
        return "ConnectionObjectCreateRequest(callback:${RmiUtils.unpackLeft(packedIds)} iface:${RmiUtils.unpackRight(packedIds)})"
    }
}
