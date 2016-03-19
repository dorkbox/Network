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

/**
 * The contents of this method are used to rewrite (via javassist, via the BuildDependencyJars build) the NativeLibraryLoader.load method.
 *
 * This is necessary so that we don't continuously recreate the native library every time we start.
 *
 * Also, this file is not included in the build process.
 */
public
class NativeLinuxEpoll {

    public static
    void load(String name, ClassLoader loader) {
//        try {
//            String var0 = $1;
//            ClassLoader var1 = $2;
//            if (var0.equals("netty-transport-native-epoll")) {
//                String sourceFileName = "META-INF/native/lib" + var0 + ".so";
//                String suffix = ".so";
//
//                String version = dorkbox.network.Server.getVersion();
//
//                final String outputFileName = "lib" + var0 + "." + version + suffix;
//                final String tempDir = System.getProperty("java.io.tmpdir");
//
//                final java.io.File file = new java.io.File(tempDir, outputFileName);
//                if (!file.canRead()) {
//                    java.net.URL url = var1.getResource(sourceFileName);
//
//                    System.err.println("Loading: " + file.getAbsolutePath());
//
//                    // now we copy it out
//                    final java.io.InputStream inputStream = url.openStream();
//
//                    java.io.OutputStream outStream = null;
//                    try {
//                        outStream = new java.io.FileOutputStream(file);
//
//                        byte[] buffer = new byte[4096];
//                        int read;
//                        while ((read = inputStream.read(buffer)) > 0) {
//                            outStream.write(buffer, 0, read);
//                        }
//
//                        outStream.flush();
//                        outStream.close();
//                        outStream = null;
//                    } finally {
//                        try {
//                            inputStream.close();
//                        } catch (Exception ignored) {
//                        }
//                        try {
//                            if (outStream != null) {
//                                outStream.close();
//                            }
//                        } catch (Exception ignored) {
//                        }
//                    }
//                }
//
//                System.load(file.getAbsolutePath());
//            }
//            else {
//                // default file operation
//
//                String libname = System.mapLibraryName(var0);
//                String path = io.netty.util.internal.NativeLibraryLoader.NATIVE_RESOURCE_HOME + libname;
//
//                java.net.URL url = var1.getResource(path);
//                if (url == null && io.netty.util.internal.NativeLibraryLoader.isOSX()) {
//                    if (path.endsWith(".jnilib")) {
//                        url = var1.getResource(io.netty.util.internal.NativeLibraryLoader.NATIVE_RESOURCE_HOME + "lib" + var0 + ".dynlib");
//                    }
//                    else {
//                        url = var1.getResource(io.netty.util.internal.NativeLibraryLoader.NATIVE_RESOURCE_HOME + "lib" + var0 + ".jnilib");
//                    }
//                }
//
//                if (url == null) {
//                    // Fall back to normal loading of JNI stuff
//                    System.loadLibrary(var0);
//                    return;
//                }
//
//                int index = libname.lastIndexOf('.');
//                String prefix = libname.substring(0, index);
//                String suffix = libname.substring(index, libname.length());
//                java.io.InputStream in = null;
//                java.io.OutputStream out = null;
//                java.io.File tmpFile = null;
//                boolean loaded = false;
//                try {
//                    tmpFile = java.io.File.createTempFile(prefix, suffix, io.netty.util.internal.NativeLibraryLoader.WORKDIR);
//                    in = url.openStream();
//                    out = new java.io.FileOutputStream(tmpFile);
//
//                    byte[] buffer = new byte[8192];
//                    int length;
//                    while ((length = in.read(buffer)) > 0) {
//                        out.write(buffer, 0, length);
//                    }
//                    out.flush();
//                    out.close();
//                    out = null;
//
//                    System.load(tmpFile.getPath());
//                    loaded = true;
//                } catch (Exception e) {
//                    throw (UnsatisfiedLinkError) new UnsatisfiedLinkError("could not load a native library: " + var0).initCause(e);
//                } finally {
//                    if (in != null) {
//                        try {
//                            in.close();
//                        } catch (java.io.IOException ignore) {
//                            // ignore
//                        }
//                    }
//                    if (out != null) {
//                        try {
//                            out.close();
//                        } catch (java.io.IOException ignore) {
//                            // ignore
//                        }
//                    }
//                    if (tmpFile != null) {
//                        if (loaded) {
//                            tmpFile.deleteOnExit();
//                        }
//                        else {
//                            if (!tmpFile.delete()) {
//                                tmpFile.deleteOnExit();
//                            }
//                        }
//                    }
//                }
//            }
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
    }

    private
    NativeLinuxEpoll() {
    }
}
