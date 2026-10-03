/*
 * Copyright (c) 2022 - 2026, Origin Technology. All rights reserved.
 * Adapted for AnySound, 2026-10-03. See anysound-launcher/NOTICE.md.
 */

package tech.origin.launch;

import tech.origin.launch.transform.ClassTransformerManager;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static java.lang.Math.max;

public class ExternalClassLoader extends URLClassLoader {
    public ExternalClassLoader(String name, URL[] urls, ClassLoader parent) {
        super(name, urls, parent);
        ctm = null;
    }

    public ExternalClassLoader(URL[] urls, ClassLoader parent, ClassTransformerManager ctm) {
        super(urls, parent);
        this.ctm = ctm;
    }

    public ExternalClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
        ctm = null;
    }

    public ExternalClassLoader(ClassLoader parent) {
        super(new URL[0], parent);
        ctm = null;
    }

    public ExternalClassLoader() {
        super(new URL[0], ExternalClassLoader.class.getClassLoader());
        ctm = null;
    }

    private final ClassTransformerManager ctm;

    private final HashMap<String, byte[]> classesCache = new HashMap<>();
    @SuppressWarnings("CollectionContainsUrl") // It doesn't matter when URL as a value in map.
    private final Multimap<String, URL> resourceCache = new Multimap<>();
    private final HashMap<String, URL> classesURLs = new HashMap<>();
    private final Set<String> resourcePaths = new HashSet<>();
    private final String[] systemPaths = System.getProperty("java.library.path", "").split(java.util.regex.Pattern.quote(File.pathSeparator));
    private final String[] dummyPaths = {"C:\\Windows\\System\\", "C:\\Windows\\System32\\"};
    private volatile boolean isClosed = false;

    public void addURLs(URL... urls) {
        for (URL url : urls) {
            this.addURL(url);
        }
    }

    @Override
    public void addURL(URL url) {
        super.addURL(url);
    }

    public void addResourcePath(String path) {
        resourcePaths.add(path);
    }

    public void removeResourcePath(String path) {
        resourcePaths.remove(path);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        try {
            return super.findClass(name);
        } catch (Exception exception) {
            if (isClosed) {
                throw new IllegalStateException("Classloader is closed");
            }
            byte[] bytes = classesCache.getOrDefault(name, null);
            if (bytes != null) {
                byte[] bytes1 = ctm != null ? ctm.transform(name, bytes) : bytes;
                return defineClass(name, bytes1, 0, bytes1.length);
            } else throw new ClassNotFoundException("Can't find class " + name);
        }
    }

    @Override
    public void close() throws IOException {
        super.close();
        isClosed = true;
    }

    @Override
    public URL findResource(String name) {
        // System.out.println(name);
        // Find in resource caches
        URL resInCache = Optional.ofNullable(resourceCache.get(name)).flatMap(v -> v.stream().findFirst()).orElse(null);
        if (resInCache != null) return resInCache;

        // Find in this classLoader
        URL resInThis = super.findResource(name);
        if (resInThis != null) return resInThis;

        // Find in parent classLoader
        URL resInParent = getParent().getResource(name);
        if (resInParent != null) return resInParent;

        // Find external resources
        for (String path : resourcePaths) {
            URL res = findInPath(path, name);
            if (res != null) return res;
        }

        // Find in system
        for (String sysPath : systemPaths) {
            URL res = findInPath(sysPath, name);
            if (res != null) return res;
        }

        // Dummy stuff
        for (String dummy : dummyPaths) {
            URL res = findInPath(dummy, name);
            if (res != null) return res;
        }
        return null;
    }

    @SuppressWarnings("UrlHashCode")
    @Override
    public Enumeration<URL> findResources(String name) throws IOException {
        Set<URL> combined = new HashSet<>();

        Collection<URL> cached = resourceCache.get(name);
        if (cached != null) combined.addAll(resourceCache.get(name));
        Enumeration<URL> current = super.findResources(name);
        while (current.hasMoreElements()) {
            combined.add(current.nextElement());
        }

        Enumeration<URL> parent = getParent().getResources(name);
        while (parent.hasMoreElements()) {
            combined.add(parent.nextElement());
        }

        return Collections.enumeration(combined);
    }

    public URL loadResource(File file) throws IOException {
        URL url = file.toURI().toURL();
        resourceCache.put(file.toString().replace("\\", "/"), url);
        return url;
    }

    public void loadJarImmediately(String file) {
        try {
            loadJarImmediately(new File(file));
        } catch (Exception exception) {
            LaunchLogger.error("Failed to load jar " + file, exception);
        }
    }

    public void loadJarImmediately(File file) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(file.toPath()))) {
            while (true) {
                ZipEntry entry = zip.getNextEntry();
                if (entry == null) break;
                URL url = new URL("jar:" + file.toURI().toASCIIString() + "!/" + entry.getName());
                resourceCache.put(entry.getName(), url);
                if (entry.getName().toLowerCase().endsWith(".class")) {
                    classesCache.put(removeSuffix(entry.getName().replace("/", "."), ".class"), readBytes(zip));
                    classesURLs.put(entry.getName(), url);
                }
            }
        }
    }

    public byte[] getClassBytes(String name) {
        return classesCache.getOrDefault(name, null);
    }

    public URL getClassURL(String name) {
        return classesURLs.getOrDefault(name, null);
    }

    private static byte[] readBytes(InputStream input) throws IOException {
        int size = max(8 * 1024, input.available());
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(size);
        copyTo(input, buffer, size);
        return buffer.toByteArray();
    }

    private static void copyTo(InputStream in, OutputStream out, int bufferSize) throws IOException {
        byte[] buffer = new byte[bufferSize];
        int bytes = in.read(buffer);
        while (bytes >= 0) {
            out.write(buffer, 0, bytes);
            bytes = in.read(buffer);
        }
    }

    private static String removeSuffix(String value, String suffix) {
        if (value.endsWith(suffix)) {
            return value.substring(0, value.length() - suffix.length());
        } else return value;
    }

    private static URL findInPath(String path, String name) {
        String adjustedPath = removeSuffix(removeSuffix(path, "/"), "\\");
        File file = new File(adjustedPath + "/" + name);
        if (file.exists()) try {
            return file.toURI().toURL();
        } catch (MalformedURLException e) {
            return null;
        }
        else return null;
    }

    public void initKotlinObject(String name) throws Exception {
        invokeKotlinObjectField(loadClass(name));
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static void invokeKotlinObjectField(Class<?> clazz) throws Exception {
        Field[] fields = clazz.getDeclaredFields();
        for (Field field : fields) {
            if (Modifier.isStatic(field.getModifiers()) && field.getName().equals("INSTANCE")) {
                field.get(null);
                break;
            }
        }
    }

}
