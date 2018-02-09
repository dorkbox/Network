/*
 * Copyright 2018 dorkbox, llc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package dorkbox.network;

import java.io.File;
import java.lang.reflect.Field;

import dorkbox.util.NativeLoader;
import dorkbox.util.OS;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.kqueue.KQueue;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.SystemPropertyUtil;

/**
 *
 */
public
class NativeLibrary {

    /**
     * Tries to extract the native transport libraries for Linux/MacOsX into a "semi-permanent" location. If this is unsuccessful for any
     * reason, netty will fall back to it's own logic.
     */
    static {
        if (OS.isLinux() || OS.isMacOsX()) {
            // try to load the native libraries for Linux/MacOsX...
            String originalLibraryPath = SystemPropertyUtil.get("java.library.path");
            File outputDirectory;

            String workdir = SystemPropertyUtil.get("io.netty.native.workdir");
            if (workdir != null) {
                File f = new File(workdir);
                if (!f.isDirectory()) {
                    f.mkdirs();
                }

                try {
                    f = f.getAbsoluteFile();
                } catch (Exception ignored) {
                    // Good to have an absolute path, but it's OK.
                }

                outputDirectory = f;
                // logger.debug("-Dio.netty.native.workdir: " + WORKDIR);
            }
            else {
                outputDirectory = PlatformDependent.tmpdir();
                // logger.debug("-Dio.netty.native.workdir: " + WORKDIR + " (io.netty.tmpdir)");
            }

            try {
                System.setProperty("java.library.path", originalLibraryPath + File.pathSeparator + outputDirectory.getAbsolutePath());

                // reset the classloader library path.
                Field fieldSysPath = ClassLoader.class.getDeclaredField("sys_paths");
                fieldSysPath.setAccessible(true);
                fieldSysPath.set(null, null);
            } catch (Exception ignored) {
            }


            String staticLibName;
            if (OS.isLinux()) {
                staticLibName = "netty_transport_native_epoll";
            }
            else {
                staticLibName = "netty_transport_native_kqueue";
            }

            staticLibName = "lib" + staticLibName + '_' + PlatformDependent.normalizedArch();



            String jarLibName = "META-INF/native/" + staticLibName;
            if (OS.isLinux()) {
                jarLibName += ".so";
            }
            else {
                jarLibName += ".jnilib";
            }


            try {
                NativeLoader.extractLibrary(jarLibName, outputDirectory.getAbsolutePath(), staticLibName, null);

                // we have to try to load the native library HERE, while the java.library.path has it
                if (OS.isLinux()) {
                    //noinspection ResultOfMethodCallIgnored
                    Epoll.isAvailable();
                }
                else if (OS.isMacOsX()) {
                    //noinspection ResultOfMethodCallIgnored
                    KQueue.isAvailable();
                }
            } catch (Exception ignored) {

            } finally {
                System.setProperty("java.library.path", originalLibraryPath);

                try {
                    // reset the classloader library path.
                    Field fieldSysPath = ClassLoader.class.getDeclaredField("sys_paths");
                    fieldSysPath.setAccessible(true);
                    fieldSysPath.set(null, null);
                } catch (Exception ignored) {
                }
            }
        }

        // either not Linux/MacOsX, or loading the library failed.
    }


    /**
     * @return true if the (possibly) required native libraries have been loaded
     */
    public static
    boolean isAvailable() {
        if (OS.isLinux()) {
            return Epoll.isAvailable();
        }
        else if (OS.isMacOsX()) {
            return KQueue.isAvailable();
        }

        // not Linux/MacOsX
        return true;
    }

}
