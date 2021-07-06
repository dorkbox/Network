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

import com.esotericsoftware.kryo.Kryo
import dorkbox.network.connection.Connection

internal class ClassRegistration1<CONNECTION: Connection>(clazz: Class<*>, id: Int) : ClassRegistration<CONNECTION>(clazz, null, id) {
    override fun register(kryo: Kryo) {
        kryo.register(clazz, id)
        info = "Registered $id -> (specified) ${clazz.name}"
    }

    override fun getInfoArray(): Array<Any> {
        return arrayOf(1, id, clazz.name, "")
    }
}
