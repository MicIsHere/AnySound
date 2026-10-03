package io.anysound

import io.anysound.vr.callInput010BindingUi
import org.junit.jupiter.api.Test
import org.lwjgl.system.Callback
import org.lwjgl.system.CallbackI
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.*
import org.lwjgl.system.libffi.FFICIF
import org.lwjgl.system.libffi.LibFFI.*
import kotlin.test.*

class OpenVrBindingApiTest {
    @Test fun `real JNI calls binding entry with correct ABI instead of legacy input entry`() {
        val methods = checkNotNull(javaClass.getResourceAsStream("/steamvr/ivrinput-010-methods.txt"))
            .bufferedReader().use { it.readLines() }.filter { it.isNotBlank() && !it.startsWith('#') }
        MemoryStack.stackPush().use { stack ->
            val legacyCif = FFICIF.calloc(stack)
            assertEquals(FFI_OK, ffi_prep_cif(legacyCif, FFI_DEFAULT_ABI, ffi_type_uint8, null))
            var legacyCalls = 0
            val legacy = object : CallbackI {
                override fun getCallInterface() = legacyCif
                override fun callback(ret: Long, args: Long) {
                    legacyCalls++
                    memPutAddress(ret, 0L)
                }
            }.address()
            try {
                val bindingCif = FFICIF.calloc(stack)
                val argumentTypes = stack.pointers(ffi_type_pointer.address(), ffi_type_uint64.address(),
                    ffi_type_uint64.address(), ffi_type_uint8.address())
                assertEquals(FFI_OK, ffi_prep_cif(bindingCif, FFI_DEFAULT_ABI, ffi_type_sint32, argumentTypes))
                val received = mutableListOf<List<Any>>()
                val binding = object : CallbackI {
                    override fun getCallInterface() = bindingCif
                    override fun callback(ret: Long, args: Long) {
                        val values = memPointerBuffer(args, 4)
                        received.add(listOf(memUTF8(memGetAddress(values[0])), memGetLong(values[1]),
                            memGetLong(values[2]), memGetByte(values[3]).toInt()))
                        memPutAddress(ret, 7L)
                    }
                }.address()
                try {
                    val table = stack.callocPointer(methods.size)
                    table.put(methods.indexOf("IsUsingLegacyInput"), legacy)
                    table.put(methods.indexOf("OpenBindingUI"), binding)
                    assertEquals(7, callInput010BindingUi(table.address(), "io.anysound.desktop", false))
                    assertEquals(7, callInput010BindingUi(table.address(), "io.anysound.desktop", true))
                    assertEquals(0, legacyCalls, "不能把 bool 返回值当成 OpenVR 错误码")
                    assertEquals<List<List<Any>>>(listOf(
                        listOf("io.anysound.desktop", 0L, 0L, 0),
                        listOf("io.anysound.desktop", 0L, 0L, 1),
                    ), received)
                    table.put(methods.indexOf("OpenBindingUI"), 0L)
                    assertFailsWith<IllegalStateException> {
                        callInput010BindingUi(table.address(), "io.anysound.desktop", false)
                    }
                } finally { Callback.free(binding) }
            } finally { Callback.free(legacy) }
        }
    }
}
