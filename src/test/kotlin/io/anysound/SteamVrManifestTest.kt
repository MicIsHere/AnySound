package io.anysound

import io.anysound.config.Settings
import io.anysound.vr.HudContent
import io.anysound.vr.SteamVr
import io.anysound.vr.readOpenVrString
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.lwjgl.system.MemoryStack
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class SteamVrManifestTest {
    @TempDir lateinit var temp: Path

    @Test fun `native strings end at NUL and do not include unused output buffer bytes`() {
        MemoryStack.stackPush().use { stack ->
            val output = stack.calloc(128)
            // Native output calls write bytes without changing the buffer's position or limit.
            "io.anysound.desktop\u0000ignored".toByteArray(Charsets.UTF_8).forEachIndexed { index, byte ->
                output.put(index, byte)
            }
            assertEquals(SteamVr.appKey, readOpenVrString(output))
            assertEquals(0, output.position())
            assertEquals(128, output.limit())

            val empty = stack.calloc(128)
            assertEquals("", readOpenVrString(empty), "空清单不能因为缓冲区中含 NUL 而被当成有效路径")
            val path = "C:\\用户\\设置 空间\\actions.json"
            val property = stack.calloc(512)
            path.toByteArray(Charsets.UTF_8).forEachIndexed { index, byte -> property.put(index, byte) }
            assertEquals(path, readOpenVrString(property))

            // Respect the output buffer bounds even if the terminator is missing.
            output.put(0, 'x'.code.toByte()).put(1, 'y'.code.toByte()).limit(2)
            assertEquals("xy", readOpenVrString(output))
        }
    }

    @Test fun `manifest retains Chinese paths and points to available actions and launcher`() = runBlocking {
        val directory = temp.resolve("中文用户/AnySound 设置/steamvr")
        val vr = SteamVr(directory, { Settings() }, { HudContent("", "") }, {}, {}, {}, {}, {})
        try {
            val manifest = Json.parseToJsonElement(Files.readString(vr.prepareManifest())).jsonObject
            val app = manifest.getValue("applications").jsonArray.single().jsonObject
            assertEquals(SteamVr.appKey, app.getValue("app_key").jsonPrimitive.content)
            assertTrue(app.getValue("is_dashboard_overlay").jsonPrimitive.boolean)
            val actionPath = Path.of(app.getValue("action_manifest_path").jsonPrimitive.content)
            assertEquals(directory.resolve("actions.json").toAbsolutePath(), actionPath)
            val actions = Json.parseToJsonElement(Files.readString(actionPath)).jsonObject
            assertEquals(4, actions.getValue("actions").jsonArray.size)
            for (binding in actions.getValue("default_bindings").jsonArray) {
                assertTrue(Files.isRegularFile(directory.resolve(binding.jsonObject.getValue("binding_url").jsonPrimitive.content)))
            }
            val args = app.getValue("arguments").jsonPrimitive.content
            assertTrue(args.contains("tech.origin.launch.Main"))
            for (entry in System.getProperty("java.class.path").split(File.pathSeparator)) {
                val absolute = Path.of(entry.ifEmpty { "." }).toAbsolutePath().normalize().toString()
                assertTrue(args.contains(absolute), "SteamVR 从不同工作目录启动时仍需能找到 Launcher")
            }
        } finally { vr.close() }
    }
}
