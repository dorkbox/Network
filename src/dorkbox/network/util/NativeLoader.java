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
package dorkbox.network.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.rmi.server.ExportException;

import dorkbox.network.Server;
import dorkbox.util.OS;
import io.netty.util.internal.PlatformDependent;

/**
 * Loads the specified library, extracting it from the jar, if necessary
 */
public
class NativeLoader {

    public static
    void loadLibrary(final String sourceFileName, final String destinationPrefix, final Class<?> classLoaderClass) throws Exception {
        try {
            String suffix;
            if (OS.isLinux()) {
                suffix = ".so";
            }
            else if (OS.isWindows()) {
                suffix = ".dll";
            }
            else {
                suffix = ".dylib";
            }

            final String outputFileName = destinationPrefix + "." + Server.getVersion() + suffix;

            final File file = new File(OS.TEMP_DIR, outputFileName);
            if (!file.canRead()) {
                ClassLoader loader = PlatformDependent.getClassLoader(classLoaderClass);
                URL url = loader.getResource(sourceFileName);

                // now we copy it out
                final InputStream inputStream = url.openStream();

                OutputStream outStream = null;
                try {
                    outStream = new FileOutputStream(file);

                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = inputStream.read(buffer)) > 0) {
                        outStream.write(buffer, 0, read);
                    }

                    outStream.flush();
                    outStream.close();
                    outStream = null;
                } finally {
                    try {
                        inputStream.close();
                    } catch (Exception ignored) {
                    }
                    try {
                        if (outStream != null) {
                            outStream.close();
                        }
                    } catch (Exception ignored) {
                    }
                }
            }

            System.load(file.getAbsolutePath());
        } catch (Exception e) {
            throw new ExportException("Error extracting library: " + sourceFileName, e);
        }
    }

    private
    NativeLoader() {
    }
}
