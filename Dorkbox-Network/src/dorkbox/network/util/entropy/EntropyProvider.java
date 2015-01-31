package dorkbox.network.util.entropy;


public interface EntropyProvider {

    public byte[] get(String messageForUser) throws Exception;
}
