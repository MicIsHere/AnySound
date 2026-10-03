/*
 * Copyright (c) 2022 - 2026, Origin Technology. All rights reserved.
 * Adapted for AnySound, 2026-10-03. See anysound-launcher/NOTICE.md.
 */

package tech.origin.launch;

import javax.swing.JOptionPane;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;

public class Main {
    public static String[] initArgs = new String[0];

    public static void main(String[] args) {
        try {
            LaunchLogger.info("Initializing AnySound launch wrapper...");
            LaunchLogger.info("Running on platform: " + Platform.getPlatform().getPlatformName());
            String resources = System.getProperty("compose.application.resources.dir");
            Path directory = resources == null
                    ? Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent()
                    : Path.of(resources);
            Path mainJar = directory.resolve("anysound-main.jar");
            Path libraries = directory.resolve("libs");
            if (!Files.isRegularFile(mainJar) || !Files.isDirectory(libraries)) {
                throw new IllegalStateException("缺少 anysound-main.jar 或 libs 目录，请完整解压 AnySound 程序包");
            }

            LaunchClassLoader loader = LaunchClassLoader.INSTANCE;
            // Keep the loader open until JVM shutdown, including asynchronous UI work.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { loader.close(); } catch (Exception ignored) { }
                LaunchLogger.close();
            }, "anysound-launcher-shutdown"));
            loader.addURL(mainJar.toUri().toURL());
            try (var jars = Files.walk(libraries)) {
                for (Path jar : jars.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                        .sorted().toList()) {
                    loader.addURL(jar.toUri().toURL());
                }
            }
            initArgs = args.clone();
            System.setProperty("jna.nosys", "true");
            Thread.currentThread().setContextClassLoader(loader);
            verifySkikoResources(loader, Platform.getPlatform());
            var entry = loader.loadClass("io.anysound.MainKt").getMethod("main", String[].class);
            if (args.length == 1 && args[0].equals("--check")) {
                LaunchLogger.info("AnySound launcher check passed");
                return;
            }
            LaunchLogger.info("Starting AnySound...");
            entry.invoke(null, (Object) args);
        } catch (Throwable error) {
            Throwable cause = error instanceof InvocationTargetException && error.getCause() != null ? error.getCause() : error;
            LaunchLogger.error("AnySound 启动失败", cause);
            if (!GraphicsEnvironment.isHeadless()) {
                try {
                    JOptionPane.showMessageDialog(null,
                            "AnySound 启动失败（" + cause.getClass().getSimpleName() + "）。\n"
                                    + (cause instanceof IllegalStateException ? cause.getMessage() : "请确认已完整解压程序包。")
                                    + "\n启动日志：" + LaunchLogger.logDirectory(),
                            "AnySound", JOptionPane.ERROR_MESSAGE);
                } catch (Throwable ignored) { }
            }
            LaunchLogger.close();
            System.exit(1);
        }
    }

    static void verifySkikoResources(ClassLoader loader, Platform.Platforms platform) {
        String binary = switch (platform) {
            case Windows64 -> "skiko-windows-x64.dll";
            case WindowsARM64 -> "skiko-windows-arm64.dll";
            case Mac -> "libskiko-macos-x64.dylib";
            case MacARM64 -> "libskiko-macos-arm64.dylib";
            case Linux -> "libskiko-linux-x64.so";
            case LinuxARM64 -> "libskiko-linux-arm64.so";
            default -> throw new IllegalStateException("AnySound 需要 64 位 Java 21，Windows 发行目标为 x64");
        };
        boolean windows = platform.getOS() == Platform.OS.Windows;
        String nativePath = System.getProperty("skiko.library.path");
        if (nativePath != null && Files.isRegularFile(Path.of(nativePath, binary))
                && (!windows || Files.isRegularFile(Path.of(nativePath, "icudtl.dat")))) {
            return; // Preserve a valid extracted/custom native directory.
        }
        List<String> required = new ArrayList<>(List.of(binary, binary + ".sha256"));
        if (windows) required.add("icudtl.dat");
        List<String> missing = required.stream().filter(name -> loader.getResource(name) == null).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("程序包缺少 " + platform.getPlatformName() + " 的界面原生库：\n"
                    + String.join(", ", missing) + "\n请复制完整的、匹配目标系统的 Launcher 目录。\n"
                    + (windows ? "在 Mac 上构建 Windows 版本：./gradlew prepareLauncher -PtargetPlatform=windows-x64"
                    : "请在当前系统运行 ./gradlew prepareLauncher 重新生成程序包。"));
        }
        // Compose's jpackage default may refer to a directory without extracted
        // binaries. Only then fall back to Skiko's own extraction from intact JARs.
        System.clearProperty("skiko.library.path");
    }
}
