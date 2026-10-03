package io.anysound.speech

import kotlinx.coroutines.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration

object ModelDownloader {
    suspend fun download(directory: Path, files: ModelFiles = ModelFiles.streaming, progress: (String) -> Unit): Path = withContext(Dispatchers.IO) {
        Files.createDirectories(directory)
        val base = "https://huggingface.co/csukuangfj/${files.model}/resolve/${files.revision}"
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build().use { client ->
            for ((index, name) in files.names.withIndex()) {
                ensureActive()
                val target = directory.resolve(name)
                if (files.matches(target, name)) continue
                val partial = directory.resolve("$name.part")
                try {
                    progress("${files.label}模型 ${index + 1}/${files.names.size} · $name")
                    val request = HttpRequest.newBuilder(URI("$base/$name")).timeout(Duration.ofMinutes(10)).GET().build()
                    val response = runInterruptible { client.send(request, HttpResponse.BodyHandlers.ofInputStream()) }
                    response.body().use { input ->
                        check(response.statusCode() == 200) { "模型下载失败（HTTP ${response.statusCode()}），可手动下载后导入" }
                        val total = response.headers().firstValueAsLong("content-length").orElse(-1)
                        var count = 0L
                        var lastUpdate = 0L
                        Files.newOutputStream(partial).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                ensureActive()
                                val size = runInterruptible { input.read(buffer) }
                                if (size < 0) break
                                output.write(buffer, 0, size)
                                count += size
                                if (count - lastUpdate >= 1024 * 1024) {
                                    progress("${files.label}模型 ${index + 1}/${files.names.size} · ${count / 1024 / 1024} MB" + if (total > 0) " / ${total / 1024 / 1024} MB" else "")
                                    lastUpdate = count
                                }
                            }
                        }
                        check(count > 0 && (total < 0 || total == count)) { "模型下载不完整，请重试" }
                    }
                    check(files.matches(partial, name)) { "模型校验失败，请重新下载" }
                    Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
                } finally { Files.deleteIfExists(partial) }
            }
        }
        files.validate(directory)
        directory
    }
}
