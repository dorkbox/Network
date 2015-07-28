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

import com.esotericsoftware.kryo.Kryo;
import dorkbox.network.pipeline.ByteBufInput;
import dorkbox.network.pipeline.ByteBufOutput;
import dorkbox.util.crypto.bouncycastle.GCMBlockCipher_ByteBuf;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.compression.SnappyAccess;
import org.bouncycastle.crypto.engines.AESFastEngine;

import java.util.zip.Deflater;
import java.util.zip.Inflater;

public
class KryoExtra extends Kryo {
    final ByteBufOutput output;
    final ByteBufInput input;

    final Inflater inflater;
    final Deflater deflater;
    final SnappyAccess snappy;

    final ByteBuf tmpBuffer1;
    final ByteBuf tmpBuffer2;
    final GCMBlockCipher_ByteBuf aesEngine;

    // not thread safe
    public ConnectionImpl connection;


    public
    KryoExtra() {
        this.snappy = new SnappyAccess();
        this.deflater = new Deflater(KryoCryptoSerializationManager.compressionLevel, true);
        this.inflater = new Inflater(true);

        this.input = new ByteBufInput();
        this.output = new ByteBufOutput();

        this.tmpBuffer1 = Unpooled.buffer(1024);
        this.tmpBuffer2 = Unpooled.buffer(1024);
        this.aesEngine = new GCMBlockCipher_ByteBuf(new AESFastEngine());
    }
}
