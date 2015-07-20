package dorkbox.network.rmi;

import com.esotericsoftware.reflectasm.MethodAccess;

import java.lang.reflect.InvocationTargetException;

public
class AsmCachedMethod extends CachedMethod {
    public MethodAccess methodAccess;
    public int methodAccessIndex = -1;

    @Override
    public Object invoke(Object target, Object[] args) throws IllegalAccessException, InvocationTargetException {
        try {
            return this.methodAccess.invoke(target, this.methodAccessIndex, args);
        } catch (Exception ex) {
            throw new InvocationTargetException(ex);
        }
    }
}
