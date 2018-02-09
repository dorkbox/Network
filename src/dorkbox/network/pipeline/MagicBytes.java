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
package dorkbox.network.pipeline;

import io.netty.buffer.ByteBuf;

/**
 *
 */
public
class MagicBytes {
    /**
     * bit masks
     * <p>
     * 0 means it's not encrypted or anything....
     */
    public static final byte crypto = (byte) (1 << 1);

    /**
     * Determines if this buffer is encrypted or not.
     */
    public static
    boolean isEncrypted(final ByteBuf buffer) {
        // read off the magic byte
        byte magicByte = buffer.getByte(buffer.readerIndex());
        return (magicByte & crypto) == crypto;
    }
}
