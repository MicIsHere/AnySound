/*
 * Copyright (c) 2022 - 2026, Origin Technology. All rights reserved.
 * Adapted for AnySound, 2026-10-03. See anysound-launcher/NOTICE.md.
 */

package tech.origin.launch;

import tech.origin.launch.transform.ClassTransformerManager;

import java.io.File;
import java.net.URL;

public class LaunchClassLoader extends ExternalClassLoader {

    public final static ClassTransformerManager launchCTM = new ClassTransformerManager();
    public final static LaunchClassLoader INSTANCE = new LaunchClassLoader(launchCTM);

    public LaunchClassLoader(ClassTransformerManager ctm) {
        // jpackage also lists resource JARs on the system classpath. Keep application
        // classes in this loader so native libraries and service discovery agree.
        super(new URL[0], ClassLoader.getPlatformClassLoader(), ctm);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith("tech.origin.launch.")) return Main.class.getClassLoader().loadClass(name);
        return super.loadClass(name, resolve);
    }

    public void loadJarFile(String file) {
        loadJarFile(new File(file));
    }

    public void loadJarFile(File file) {
        try {
            INSTANCE.loadJarImmediately(file);
        } catch (Exception exception) {
            LaunchLogger.error("Failed to load jar " + file, exception);
        }
    }

    public URL loadResourceFile(File file) {
        try {
            return INSTANCE.loadResource(file);
        } catch (Exception exception) {
            LaunchLogger.error("Failed to resource file " + file, exception);
        }
        return null;
    }
}
