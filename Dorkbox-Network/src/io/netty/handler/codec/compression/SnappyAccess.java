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
package io.netty.handler.codec.compression;

import io.netty.buffer.ByteBuf;

public
class SnappyAccess {

    public static
    int calculateChecksum(ByteBuf data, int offset, int length) {
        return Snappy.calculateChecksum(data, offset, length);
    }

    public static
    int calculateChecksum(ByteBuf slice) {
        return Snappy.calculateChecksum(slice);
    }
    // oh well. At least we can still get to it.
    private final Snappy snappy = new Snappy();

    public
    SnappyAccess() {
    }

    public
    void encode(ByteBuf slice, ByteBuf outputBuffer, short maxValue) {
        snappy.encode(slice, outputBuffer, maxValue);
    }

    public
    void encode(ByteBuf slice, ByteBuf outputBuffer, int dataLength) {
        snappy.encode(slice, outputBuffer, dataLength);
    }

    public
    void decode(ByteBuf inputBuffer, ByteBuf outputBuffer) {
        snappy.decode(inputBuffer, outputBuffer);
    }

    public
    void reset() {
        snappy.reset();
    }
}
