/*
 * Copyright 2014 dorkbox, llc
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
package dorkbox.network.storage

import dorkbox.util.storage.StorageKey

// must have empty constructor
class DB_Server {
    companion object {
        /**
         * The storage key used to save all server connections
         */
        val STORAGE_KEY = StorageKey("servers")

        /**
         * Address 0.0.0.0/32 may be used as a source address for this host on this network.
         */
        const val IP_SELF = 0
    }


    // salt + IP address is used for equals!
    var ipAddress: ByteArray? = null
    var salt: ByteArray? = null

    var privateKey: ByteArray? = null
    var publicKey: ByteArray? = null

    override fun hashCode(): Int {
        val prime = 31
        var result = 1
        result = prime * result + (ipAddress?.contentHashCode() ?: 0)
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other == null) {
            return false
        }
        if (javaClass != other.javaClass) {
            return false
        }
        val other1 = other as DB_Server

        if (ipAddress == null) {
            if (other1.ipAddress != null) {
                return false
            }
        } else if (other1.ipAddress == null) {
            return false
        } else if (!ipAddress!!.contentEquals(other1.ipAddress!!)) {
            return false
        }

        if (salt == null) {
            if (other1.salt != null) {
                return false
            }
        } else if (other1.salt == null) {
            return false
        }

        return salt!!.contentEquals(other1.salt!!)
    }

    override fun toString(): String {
        val bytes = ipAddress
        return if (bytes != null) {
            "DB_Server " + bytes.contentToString()
        } else "DB_Server [no-ip-set]"
    }
}
