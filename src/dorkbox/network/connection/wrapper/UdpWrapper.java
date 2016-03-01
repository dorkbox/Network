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
package dorkbox.network.connection.wrapper;

import java.net.InetSocketAddress;

public
class UdpWrapper {

    private final Object object;
    private final InetSocketAddress remoteAddress;

    public
    UdpWrapper(Object object, InetSocketAddress remoteAddress2) {
        this.object = object;
        this.remoteAddress = remoteAddress2;
    }

    public
    Object object() {
        return this.object;
    }

    public
    InetSocketAddress remoteAddress() {
        return this.remoteAddress;
    }
}
