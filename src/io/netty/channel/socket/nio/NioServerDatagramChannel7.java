/*
 * Copyright 2018 dorkbox, llc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package io.netty.channel.socket.nio;

import java.io.IOException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.spi.SelectorProvider;

import io.netty.channel.socket.InternetProtocolFamily;

/**
 * For Java7+ only!
 */
public
class NioServerDatagramChannel7 {
    // NOTE: this is suppressed because we compile this for java7, and everything else for java6, and this is only called if we are java7+
    @SuppressWarnings("Since15")
    public static
    DatagramChannel newSocket(final SelectorProvider provider, final InternetProtocolFamily ipFamily) throws IOException {
        return provider.openDatagramChannel(ProtocolFamilyConverter.convert(ipFamily));
    }
}
