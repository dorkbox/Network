/*
 * Copyright 2010 dorkbox, llc
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
package dorkbox.network.util;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public abstract class ConcurrentHashMapFactory<K, V> extends ConcurrentHashMap<K, V> implements ConcurrentMap<K, V> {

    private static final long serialVersionUID = -1796263935845885270L;

    public ConcurrentHashMapFactory() {
    }

    public abstract V createNewObject(Object... args);


    /** Thread safe method to get the value in the map. If the value doesn't exist,
     * it will create a new one (and put the new one in the map)
     */
    public final V getOrCreate(K key, Object... args) {
        V orig = get(key);

        if (orig == null) {
            // It's OK to construct a new object that ends up not being used
            orig = createNewObject(args);
            V putByOtherThreadJustNow = putIfAbsent(key, orig);
            if (putByOtherThreadJustNow != null) {
                // Some other thread "won"
                orig = putByOtherThreadJustNow;
            } else {
                // This thread was the winner
            }
        }
        return orig;
    }
}
