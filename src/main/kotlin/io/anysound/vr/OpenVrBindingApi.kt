package io.anysound.vr

import org.lwjgl.openvr.VR.VR_GetGenericInterface
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.*
import org.lwjgl.system.Pointer.POINTER_SIZE
import org.lwjgl.system.libffi.FFICIF
import org.lwjgl.system.libffi.LibFFI.*

internal fun openVrBindingUi(appKey: String, showOnDesktop: Boolean): Int = MemoryStack.stackPush().use { stack ->
    val error = stack.callocInt(1)
    // Pin the ABI, rather than using the version of a future LWJGL release.
    val table = VR_GetGenericInterface("FnTable:IVRInput_010", error)
    check(error[0] == 0 && table != NULL) { "无法取得 SteamVR 输入接口（${error[0]}）" }
    callInput010BindingUi(table, appKey, showOnDesktop)
}

internal fun callInput010BindingUi(table: Long, appKey: String, showOnDesktop: Boolean): Int {
    require(table != NULL) { "SteamVR 输入接口为空" }
    // LWJGL 3.3.6's OpenVR.IVRInput omits IsUsingLegacyInput at slot 29.
    // Its VRInput_OpenBindingUI therefore calls a bool-returning function as int.
    // Valve's IVRInput_010 puts OpenBindingUI at slot 30 (zero-based):
    // https://github.com/ValveSoftware/openvr/blob/v2.5.1/headers/openvr_capi.h
    val function = memGetAddress(table + 30L * POINTER_SIZE)
    check(function != NULL) { "SteamVR 绑定界面入口不可用" }
    return MemoryStack.stackPush().use { stack ->
        // Win64 has one calling convention; libffi's default ABI matches it.
        // Use LWJGL's portable FFI path so the same call can be tested on macOS.
        val signature = FFICIF.calloc(stack)
        val types = stack.pointers(ffi_type_pointer.address(), ffi_type_uint64.address(),
            ffi_type_uint64.address(), ffi_type_uint8.address())
        check(ffi_prep_cif(signature, FFI_DEFAULT_ABI, ffi_type_sint32, types) == FFI_OK) {
            "无法准备 SteamVR 绑定调用"
        }
        val args = stack.pointers(
            memAddress(stack.pointers(memAddress(stack.UTF8(appKey)))),
            memAddress(stack.longs(0L)), memAddress(stack.longs(0L)),
            memAddress(stack.bytes(if (showOnDesktop) 1.toByte() else 0.toByte())),
        )
        val result = stack.calloc(POINTER_SIZE)
        ffi_call(signature, function, result, args)
        result.getInt(0)
    }
}
