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
