/*
 * Copyright 2016 dorkbox, llc
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
package dorkbox.network.pipeline;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import com.esotericsoftware.kryo.util.IdentityMap;

import dorkbox.network.rmi.Rmi;

/**
 * Uses the "single writer principle" for fast access, but disregards 'single writer', because duplicates are OK
 */
class RmiFieldCache {
    private volatile IdentityMap<Class<?>, Field[]> fieldCache = new IdentityMap<Class<?>, Field[]>();

    private static final AtomicReferenceFieldUpdater<RmiFieldCache, IdentityMap> rmiFieldsREF =
                    AtomicReferenceFieldUpdater.newUpdater(RmiFieldCache.class,
                                                           IdentityMap.class,
                                                           "fieldCache");

    private static final RmiFieldCache INSTANCE = new RmiFieldCache();
    public static synchronized RmiFieldCache INSTANCE() {
        return INSTANCE;
    }

    private
    RmiFieldCache() {
    }

    Field[] get(final Class<?> clazz) {
        // duplicates are OK, because they will contain the same information, so we DO NOT care about single writers

        //noinspection unchecked
        final IdentityMap<Class<?>, Field[]> identityMap = rmiFieldsREF.get(this);


        Field[] rmiFields = identityMap.get(clazz);
        if (rmiFields != null) {
            return rmiFields;
        }

        final ArrayList<Field> fields = new ArrayList<Field>();

        for (Field field : clazz.getDeclaredFields()) {
            if (field.getAnnotation(Rmi.class) != null) {
                fields.add(field);
            }
        }


        rmiFields = new Field[fields.size()];
        fields.toArray(rmiFields);

        // save in cache
        fieldCache.put(clazz, rmiFields);
        return rmiFields;
    }
}
