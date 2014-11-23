package dorkbox.network.rmi;

import java.lang.reflect.InvocationTargetException;

import com.esotericsoftware.reflectasm.MethodAccess;

class AsmCachedMethod extends CachedMethod {
    MethodAccess methodAccess;
    int methodAccessIndex = -1;

    @Override
    public Object invoke(Object target, Object[] args) throws IllegalAccessException, InvocationTargetException {
        try {
            return this.methodAccess.invoke(target, this.methodAccessIndex, args);
        } catch (Exception ex) {
            throw new InvocationTargetException(ex);
        }
    }
}
