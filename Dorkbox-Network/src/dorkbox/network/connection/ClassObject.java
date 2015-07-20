package dorkbox.network.connection;

class ClassObject {
    final Class<?> clazz;
    final Object object;

    public ClassObject(final Class<?> implementationClass, final Object remotePrimaryObject) {
        clazz = implementationClass;
        object = remotePrimaryObject;
    }
}
