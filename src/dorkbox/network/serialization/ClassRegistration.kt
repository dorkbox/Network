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
package dorkbox.network.serialization

import com.esotericsoftware.kryo.Serializer
import dorkbox.network.rmi.messages.RmiServerSerializer

internal abstract class ClassRegistration(val clazz: Class<*>, val serializer: Serializer<*>? = null, var id: Int = 0) {
    companion object {
        const val IGNORE_REGISTRATION = -1
    }

    var info: String = ""

    /**
     * we have to check if this registration ALREADY exists for RMI.
     *
     * If so, we ignore it - any IFACE or IMPL that already has been assigned to an RMI serializer, *MUST* remain an RMI serializer
     * If this class registration will EVENTUALLY be for RMI, then [ClassRegistrationForRmi] will reassign the serializer
     */
    open fun register(kryo: KryoExtra, rmi: RmiHolder) {
        // ClassRegistrationForRmi overrides this method

        if (id != 0) {
            // our ID will always be > 0
            // this means that this registration was PREVIOUSLY registered on a different kryo. Shortcut the logic.

            if (serializer != null) {
                kryo.register(clazz, serializer, id)
            } else {
                kryo.register(clazz, id)
            }

            return
        }



        val savedKryoId: Int? = rmi.implToId[clazz] // ALL registrations MUST BE IMPL!
        var overriddenSerializer: Serializer<Any>? = null

        // did we already process this class?  We permit overwriting serializers, etc!
        if (savedKryoId != null) {
            overriddenSerializer = kryo.classResolver.getRegistration(savedKryoId)?.serializer
            when (overriddenSerializer) {
                is RmiServerSerializer -> {
                    // do nothing, because this is ALREADY registered for RMI
                    info = if (serializer == null) {
                        "CONFLICTED $savedKryoId -> (RMI) Ignored duplicate registration for ${clazz.name}"
                    } else {
                        "CONFLICTED $savedKryoId -> (RMI) Ignored duplicate registration for ${clazz.name} (${serializer.javaClass.name})"
                    }

                    // mark this for later, so we don't try to do something with it
                    id = IGNORE_REGISTRATION
                    return
                }
                else -> {
                    // We didn't do anything.
                }
            }
        }

        // otherwise, we are OK to continue to register this
        register(kryo)

        if (serializer != null && overriddenSerializer != null && overriddenSerializer != serializer) {
            info = "$info (Replaced $overriddenSerializer)"
        }

        // now, we want to save the relationship between classes and kryoId
        // ALL REGISTRATIONS MUST BE IMPL! (only RMI can have IFACE)
        rmi.idToImpl[id] = clazz
        rmi.implToId[clazz] = id
    }

    open fun register(kryo: KryoExtra) {}

    abstract fun getInfoArray(): Array<Any>
}
