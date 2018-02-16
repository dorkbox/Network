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
package dorkbox.network.connection;

import io.netty.util.concurrent.Promise;

public
interface ConnectionPoint {

    /**
     * @return true if the channel is writable. Useful when sending large amounts of data at once.
     */
    boolean isWritable();

    /**
     * Writes data to the pipe. <b>DOES NOT FLUSH</b> the pipe to the wire!
     */
    void write(Object object) throws Exception;

    /**
     * Creates a new promise associated with this connection type
     */
    <V> Promise<V> newPromise();
}
