package dorkbox.network.util.udt;

import java.io.File;
import java.lang.reflect.Field;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.barchart.udt.ResourceUDT;
import com.barchart.udt.lib.LibraryLoader;
import com.barchart.udt.lib.LibraryLoaderUDT;

public class UdtJniLoader implements LibraryLoader {

    protected static final Logger logger = LoggerFactory.getLogger(UdtJniLoader.class);

    @Override
    public void load(String location) throws Exception {
        boolean isNativeDeployed = false;
        try {
            Class<?> exitClass = Class.forName("dorkbox.launcher.Exit");

            if (exitClass != null) {
                Field nativeField = exitClass.getDeclaredField("isNative");
                isNativeDeployed = nativeField.getBoolean(exitClass);
            }
        } catch (Throwable t) {
        }

        // we only want to load ourselves if we are NOT deployed, since our DEPLOYMENT handles loading libraries on it's own.
        if (!isNativeDeployed) {
            File tempFile = File.createTempFile("temp", null).getAbsoluteFile();
            location = tempFile.getParent();
            tempFile.delete();

            logger.debug("Adjusted UDT JNI library location: {}", location);

            LibraryLoaderUDT loader = new LibraryLoaderUDT();
            loader.load(location);
        } else {
            // specify that we want to use our temp dir as the extraction location.
            String tmpDir = System.getProperty("java.io.tmpdir");
            ResourceUDT.setLibraryExtractLocation(tmpDir);
        }
    }
}
