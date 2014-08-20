package dorkbox.network.rmi;

import com.esotericsoftware.kryo.Kryo;

public interface RmiRegisterClassesCallback {
    public void registerForClasses(Kryo kryo);
}
