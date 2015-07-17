package dorkbox.network.dns.decoder;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.util.CharsetUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Decodes TXT (text) resource records.
 */
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
