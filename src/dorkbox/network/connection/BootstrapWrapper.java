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

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.EventLoopGroup;

public
class BootstrapWrapper {
    public final String type;
    public final Bootstrap bootstrap;
    public final String address;
    public final int port;

    public
    BootstrapWrapper(String type, String address, int port, Bootstrap bootstrap) {
        this.type = type;
        this.address = address;
        this.port = port;
        this.bootstrap = bootstrap;
    }

    public
    BootstrapWrapper clone(EventLoopGroup group) {
        return new BootstrapWrapper(type, address, port, bootstrap.clone(group));
    }

    @Override
    public
    String toString() {
        return "BootstrapWrapper{" + "type='" + type + '\'' + ", address='" + address + '\'' + ", port=" + port + '}';
    }
}
