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
package dorkbox.network.connection.idle;

import dorkbox.network.connection.Connection;
import dorkbox.util.exceptions.NetException;

import java.io.IOException;
import java.io.InputStream;

public abstract
class InputStreamSender<C extends Connection> extends IdleSender<C, byte[]> {

    private final InputStream input;
    private final byte[] chunk;

    public
    InputStreamSender(final IdleListener<C, byte[]> idleListener, InputStream input, int chunkSize) {
        super(idleListener);
        this.input = input;
        this.chunk = new byte[chunkSize];
    }

    @Override
    protected final
    byte[] next() {
        try {
            int total = 0;
            while (total < this.chunk.length) {
                int count = this.input.read(this.chunk, total, this.chunk.length - total);
                if (count < 0) {
                    if (total == 0) {
                        return null;
                    }
                    byte[] partial = new byte[total];
                    System.arraycopy(this.chunk, 0, partial, 0, total);
                    return onNext(partial);
                }
                total += count;
            }
        } catch (IOException ex) {
            throw new NetException(ex);
        }
        return onNext(this.chunk);
    }

    protected abstract
    byte[] onNext(byte[] chunk);
}
