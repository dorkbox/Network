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
import dorkbox.network.rmi.messages.RmiClientReverseSerializer
import dorkbox.util.collections.IdentityMap

internal abstract class ClassRegistration(val clazz: Class<*>, val serializer: Serializer<*>? = null, var id: Int = 0) {
    var info: String = ""

     fun register(kryo: KryoExtra, rmiIfaceToImpl: IdentityMap<Class<*>, Class<*>>) {
         // we have to check if this registration ALREADY exists for RMI. If so, we ignore it.
         // RMI kryo-registration is SPECIFICALLY for impl object ONLY DURING INITIAL REGISTRATION!
         // if the registration is modified, then the registration will be the iface
         if (clazz.isInterface) {
             val impl = rmiIfaceToImpl[clazz]
             if (impl != null && kryo.classResolver.getRegistration(impl)?.serializer is RmiClientReverseSerializer) {
                 // do nothing, because this is already registered for RMI
                 info = "Removed RMI conflict registration for class ${clazz.name}"
                 id = -1
                 return
             }

         } else {
             if (kryo.classResolver.getRegistration(clazz)?.serializer is RmiClientReverseSerializer) {
                 // do nothing, because this is already registered for RMI
                 info = "Removed RMI conflict registration for class ${clazz.name}"
                 id = -1
                 return
             }
         }

         // otherwise, we are OK to continue to register this
         register(kryo)
     }


    abstract fun register(kryo: KryoExtra)
    abstract fun getInfoArray(): Array<Any>
}
