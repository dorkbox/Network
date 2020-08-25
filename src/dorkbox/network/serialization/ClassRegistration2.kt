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

internal class ClassRegistration2(clazz: Class<*>, serializer: Serializer<*>, id: Int) : ClassRegistration(clazz) {
    init {
        this.serializer = serializer
        this.id = id
    }

    override fun register(kryo: KryoExtra) {
        kryo.register(clazz, serializer, id)
    }

    override fun info(): String {
        return "Registered $id -> (specified) ${clazz.name} using ${serializer?.javaClass?.name}"
    }
}
