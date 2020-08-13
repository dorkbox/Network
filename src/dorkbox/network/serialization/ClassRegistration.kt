/*
 * Copyright 2019 dorkbox, llc
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
import dorkbox.network.connection.KryoExtra
import org.slf4j.Logger

internal open class ClassRegistration(var clazz: Class<*>) {
    var id = 0
    var serializer: Serializer<*>? = null

    open fun register(kryo: KryoExtra) {
        val registration = kryo.register(clazz)
        id = registration.id
    }

    open fun log(logger: Logger) {
        logger.trace("Registered {} -> {}", id, clazz.name)
    }

    fun getInfoArray(): Array<Any> {
        return if (serializer != null) {
            arrayOf(id, clazz::class.java.name, serializer!!::class.java.name)
        } else {
            arrayOf(id, clazz::class.java.name, "")
        }
    }
}