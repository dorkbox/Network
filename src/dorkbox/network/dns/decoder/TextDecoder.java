/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package dorkbox.network.dns.decoder;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.util.CharsetUtil;

import java.util.ArrayList;
import java.util.List;

public
class TextDecoder implements RecordDecoder<List<String>> {

    @Override
    public
    List<String> decode(final DnsRecord record, final ByteBuf response) {
        List<String> list = new ArrayList<String>();

        int index = response.readerIndex();
        while (index < response.writerIndex()) {
            int len = response.getUnsignedByte(index++);
            list.add(response.toString(index, len, CharsetUtil.UTF_8));
            index += len;
        }

        return list;
    }
}
