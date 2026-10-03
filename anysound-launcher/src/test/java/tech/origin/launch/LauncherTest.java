package tech.origin.launch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.*;
import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.*;

class LauncherTest {
    @TempDir Path temp;

    @Test void loadsNestedLibrariesAndLateResourcesFromStandaloneAndComposeLayouts() throws Exception {
        for (boolean packaged : List.of(false, true)) {
            Path base = Files.createDirectories(temp.resolve("中文 launcher " + packaged));
            Path payload = packaged ? Files.createDirectories(base.resolve("Compose app resources")) : base;
            fixture(base, payload);
            Path marker = base.resolve("结果.txt");
            Result result = launch(base, payload, packaged, marker.toString(), "你好 with spaces");
            assertEquals(0, result.exit, result.output);
            assertEquals("late dependency/你好 with spaces/resource 中文", Files.readString(marker));
            assertFalse(result.output.contains("你好 with spaces"), "Launcher 不记录传入参数");
            Result check = launch(base, payload, packaged, "--check");
            assertEquals(0, check.exit, check.output);
            assertTrue(check.output.contains("AnySound launcher check passed"));
        }
    }

    @Test void missingPayloadAndEntryFailureHaveNonzeroExitAndUsefulLogs() throws Exception {
        Path base = Files.createDirectories(temp.resolve("broken installation"));
        Files.copy(Path.of(System.getProperty("anysound.testLauncher")), base.resolve("anysound-launcher.jar"));
        Result missing = launch(base, base, false);
        assertEquals(1, missing.exit);
        assertTrue(missing.output.contains("缺少 anysound-main.jar 或 libs 目录"));
        assertFalse(Files.exists(base.resolve("libs")), "不能把缺失的 libs 创建为普通文件");
        fixture(base, base);
        Result failed = launch(base, base, false, "fail");
        assertEquals(1, failed.exit);
        assertTrue(failed.output.contains("IllegalStateException: expected startup failure"));
        assertFalse(failed.output.contains("InvocationTargetException"));
        try (var logs = Files.list(base.resolve("profile/logs/launch"))) {
            assertEquals(2, logs.count());
        }
    }

    @Test void checkFailsBeforeComposeWhenNativeJarIsMissing() throws Exception {
        Path base = Files.createDirectories(temp.resolve("missing native runtime"));
        fixture(base, base);
        Files.delete(base.resolve("libs/fixture-natives.jar"));
        Result result = launch(base, base, false, "--check");
        assertEquals(1, result.exit, result.output);
        assertTrue(result.output.contains("界面原生库"));
        assertTrue(result.output.contains(".sha256"));
        assertFalse(result.output.contains("AnySound launcher check passed"));
        assertFalse(result.output.contains("Starting AnySound..."));
    }

    @Test void windowsPreflightRejectsMacNativesAndHandlesExtractedNativePath() throws Exception {
        Path resources = Files.createDirectories(temp.resolve("native resources"));
        Files.writeString(resources.resolve("libskiko-macos-arm64.dylib"), "fixture");
        Files.writeString(resources.resolve("libskiko-macos-arm64.dylib.sha256"), "fixture");
        String previous = System.getProperty("skiko.library.path");
        System.clearProperty("skiko.library.path");
        try (var loader = new URLClassLoader(new java.net.URL[] {resources.toUri().toURL()}, null)) {
            IllegalStateException missing = assertThrows(IllegalStateException.class,
                    () -> Main.verifySkikoResources(loader, Platform.Platforms.Windows64));
            assertTrue(missing.getMessage().contains("skiko-windows-x64.dll.sha256"));
            assertTrue(missing.getMessage().contains("-PtargetPlatform=windows-x64"));

            Path extracted = Files.createDirectories(temp.resolve("extracted natives"));
            Files.writeString(extracted.resolve("skiko-windows-x64.dll"), "fixture");
            Files.writeString(extracted.resolve("icudtl.dat"), "fixture");
            System.setProperty("skiko.library.path", extracted.toString());
            Main.verifySkikoResources(loader, Platform.Platforms.Windows64);
            assertEquals(extracted.toString(), System.getProperty("skiko.library.path"));

            Files.delete(extracted.resolve("icudtl.dat"));
            assertThrows(IllegalStateException.class, () -> Main.verifySkikoResources(loader, Platform.Platforms.Windows64));
            for (String name : List.of("skiko-windows-x64.dll", "skiko-windows-x64.dll.sha256", "icudtl.dat"))
                Files.writeString(resources.resolve(name), "fixture");
            Main.verifySkikoResources(loader, Platform.Platforms.Windows64);
            assertNull(System.getProperty("skiko.library.path"), "Only clear an unusable override when bundled natives are available");
        } finally {
            if (previous == null) System.clearProperty("skiko.library.path");
            else System.setProperty("skiko.library.path", previous);
        }
    }

    private void fixture(Path base, Path payload) throws Exception {
        Files.copy(Path.of(System.getProperty("anysound.testLauncher")), base.resolve("anysound-launcher.jar"), StandardCopyOption.REPLACE_EXISTING);
        Path source = Files.createDirectories(base.resolve("fixture-src"));
        Path entry = Files.createDirectories(source.resolve("io/anysound")).resolve("MainKt.java");
        Files.writeString(entry, """
                package io.anysound;
                public class MainKt {
                    public static void main(String[] args) throws Exception {
                        if (args[0].equals("fail")) throw new IllegalStateException("expected startup failure");
                        if (Thread.currentThread().getContextClassLoader() != MainKt.class.getClassLoader())
                            throw new AssertionError("Wrong context classloader");
                        new Thread(() -> {
                            try {
                                Thread.sleep(80);
                                var loader = Thread.currentThread().getContextClassLoader();
                                var message = loader.loadClass("fixture.Delayed").getMethod("message").invoke(null);
                                try (var stream = loader.getResourceAsStream("probe.txt")) {
                                    java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), message + "/" + args[1]
                                            + "/" + new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                                }
                            } catch (Exception error) { throw new RuntimeException(error); }
                        }).start();
                    }
                }
                """);
        Path dependency = Files.createDirectories(source.resolve("fixture")).resolve("Delayed.java");
        Files.writeString(dependency, "package fixture; public class Delayed { public static String message() { return \"late dependency\"; } }");
        Path classes = Files.createDirectories(base.resolve("fixture-classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-encoding", "UTF-8", "-d", classes.toString(), entry.toString(), dependency.toString()));
        jar(payload.resolve("anysound-main.jar"), Map.of("io/anysound/MainKt.class", Files.readAllBytes(classes.resolve("io/anysound/MainKt.class"))));
        Path libs = Files.createDirectories(payload.resolve("libs/嵌套 libraries"));
        jar(libs.resolve("dependency.JAR"), Map.of("fixture/Delayed.class", Files.readAllBytes(classes.resolve("fixture/Delayed.class")),
                "probe.txt", "resource 中文".getBytes(StandardCharsets.UTF_8)));
        // Presence checks do not load code from these fixture files.
        Map<String, byte[]> natives = new HashMap<>();
        for (String name : List.of("skiko-windows-x64.dll", "skiko-windows-arm64.dll",
                "libskiko-macos-x64.dylib", "libskiko-macos-arm64.dylib", "libskiko-linux-x64.so", "libskiko-linux-arm64.so")) {
            natives.put(name, new byte[] {1});
            natives.put(name + ".sha256", new byte[] {1});
        }
        natives.put("icudtl.dat", new byte[] {1});
        jar(payload.resolve("libs/fixture-natives.jar"), natives);
    }

    private void jar(Path target, Map<String, byte[]> files) throws Exception {
        try (var out = new JarOutputStream(Files.newOutputStream(target))) {
            for (var file : files.entrySet()) {
                out.putNextEntry(new JarEntry(file.getKey()));
                out.write(file.getValue());
                out.closeEntry();
            }
        }
    }

    private Result launch(Path base, Path payload, boolean packaged, String... args) throws Exception {
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString(),
                "-Dfile.encoding=UTF-8", "-Djava.awt.headless=true", "-Danysound.dataDir=" + base.resolve("profile")));
        if (packaged) command.add("-Dcompose.application.resources.dir=" + payload);
        if (packaged) {
            // jpackage discovers resource JARs and adds them to the system classpath.
            command.addAll(List.of("-cp", base.resolve("anysound-launcher.jar") + java.io.File.pathSeparator
                    + payload.resolve("anysound-main.jar"), "tech.origin.launch.Main"));
        } else command.addAll(List.of("-jar", base.resolve("anysound-launcher.jar").toString()));
        command.addAll(List.of(args));
        Path output = Files.createTempFile(temp, "process-", ".txt");
        Path cwd = Files.createDirectories(temp.resolve("unrelated cwd"));
        Process process = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Launcher timed out");
            return new Result(process.exitValue(), Files.readString(output));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    private record Result(int exit, String output) { }
}
