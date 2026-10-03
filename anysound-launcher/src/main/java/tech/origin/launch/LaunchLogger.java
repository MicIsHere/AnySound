/*
 * Copyright (c) 2022 - 2026, Origin Technology. All rights reserved.
 * Adapted for AnySound, 2026-10-03. See anysound-launcher/NOTICE.md.
 */

package tech.origin.launch;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class LaunchLogger {
    private static BufferedWriter bufferedWriter;

    public static Path logDirectory() {
        String override = System.getProperty("anysound.dataDir");
        String appData = System.getenv("APPDATA");
        Path data = override != null ? Path.of(override)
                : appData != null ? Path.of(appData, "AnySound") : Path.of(System.getProperty("user.home"), ".anysound");
        return data.resolve("logs/launch");
    }

    static {
        try {
            Path directory = logDirectory();
            Files.createDirectories(directory);
            String name = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS"));
            bufferedWriter = Files.newBufferedWriter(directory.resolve(name + "-" + ProcessHandle.current().pid() + ".txt"),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (Exception ignored) { }
    }

    public static void info(String msg, Throwable error) { log(msg, "INFO", error); }
    public static void info(String msg) { info(msg, null); }
    public static void warn(String msg, Throwable error) { log(msg, "WARN", error); }
    public static void warn(String msg) { warn(msg, null); }
    public static void error(String msg, Throwable error) { log(msg, "ERROR", error); }
    public static void error(String msg) { error(msg, null); }
    public static void fatal(String msg, Throwable error) { log(msg, "FATAL", error); }
    public static void fatal(String msg) { fatal(msg, null); }
    public static void debug(String msg, Throwable error) { log(msg, "DEBUG", error); }
    public static void debug(String msg) { debug(msg, null); }

    private static synchronized void log(String msg, String level, Throwable error) {
        String str = String.format("%s %-5s %s/launch - %s", LocalDateTime.now(), level, Thread.currentThread().getName(), msg);
        if (error != null) {
            StringWriter trace = new StringWriter();
            error.printStackTrace(new PrintWriter(trace));
            str += "\n" + trace;
        }
        System.out.println(str);
        try {
            if (bufferedWriter != null) {
                bufferedWriter.write(str);
                bufferedWriter.newLine();
                bufferedWriter.flush();
            }
        } catch (IOException ignored) { }
    }

    public static synchronized void close() {
        try { if (bufferedWriter != null) bufferedWriter.close(); }
        catch (IOException ignored) { }
        finally { bufferedWriter = null; }
    }
}
