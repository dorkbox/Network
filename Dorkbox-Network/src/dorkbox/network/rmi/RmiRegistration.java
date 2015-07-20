package dorkbox.network.rmi;

/**
 * Message specifically to register a class implementation for RMI
 */
public
class RmiRegistration {
    public Object remoteObject;
    public String remoteImplementationClass;
    public boolean hasError;

    public
    RmiRegistration() {
        hasError = true;
    }

    public
    RmiRegistration(final String remoteImplementationClass) {
        this.remoteImplementationClass = remoteImplementationClass;
        hasError = false;
    }

    public
    RmiRegistration(final Object remoteObject) {
        this.remoteObject = remoteObject;
        hasError = false;
    }
}
