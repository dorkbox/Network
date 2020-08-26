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

import dorkbox.network.rmi.messages.RmiClientReverseSerializer

internal class ClassRegistrationIfaceAndImpl(val ifaceClass: Class<*>,
                                             val implClass: Class<*>,
                                             override val serializer: RmiClientReverseSerializer) : ClassRegistration {

    override var id: Int = 0
    override val clazz: Class<*> = ifaceClass // this has to match what is defined on the rmi client

    override fun register(kryo: KryoExtra) {
        // have to get the ID for the interface (if it exists)
        val registration = kryo.classResolver.getRegistration(ifaceClass)
        if (registration != null) {
            id = registration.id

            // override that registration
            kryo.register(implClass, serializer, id)
        } else {
            // now register the impl class
            id = kryo.register(implClass, serializer).id
        }
    }

    override fun info(): String {
        return "Registered $id -> (RMI) ${implClass.name}"
    }

    override fun getInfoArray(): Array<Any> {
        // the info array has to match for the INTERFACE (not the impl!)
        return arrayOf(id, ifaceClass.name, serializer::class.java.name)
    }
}
