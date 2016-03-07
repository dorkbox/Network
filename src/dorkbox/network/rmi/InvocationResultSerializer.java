/*
 * Copyright 2016 dorkbox, llc
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
package dorkbox.network.rmi;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.serializers.FieldSerializer;
import dorkbox.network.connection.KryoExtra;

public
class InvocationResultSerializer extends FieldSerializer<InvokeMethodResult> {
    public
    InvocationResultSerializer(final KryoExtra kryo) {
        super(kryo, InvokeMethodResult.class);
    }

    @Override
    public
    void write(Kryo kryo, Output output, InvokeMethodResult result) {
        super.write(kryo, output, result);
        output.writeInt(result.objectID, true);
    }

    @Override
    public
    InvokeMethodResult read(Kryo kryo, Input input, Class<InvokeMethodResult> type) {
        InvokeMethodResult result = super.read(kryo, input, type);
        result.objectID = input.readInt(true);
        return result;
    }
}
