# Third-party components

AnySound uses the following projects. Their licenses and copyright notices remain applicable; dependency JARs and native distributions retain their embedded notices. The speech model is distributed separately.

| Component | License / source |
| --- | --- |
| Perlica Launcher, adapted for AnySound | Origin Technology; upstream [AGPL-3.0](anysound-launcher/LICENSE) |
| Kotlin, Kotlin coroutines and serialization | [Apache-2.0](https://github.com/JetBrains/kotlin) |
| Compose Multiplatform | [Apache-2.0](https://github.com/JetBrains/compose-multiplatform) |
| Skiko / Skia | [Apache-2.0 / BSD-style notices](https://github.com/JetBrains/skiko) |
| sherpa-onnx | [Apache-2.0](https://github.com/k2-fsa/sherpa-onnx) |
| Bilingual streaming Zipformer model | [Apache-2.0; model card](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20) |
| SenseVoice Small, sherpa-onnx conversion | [Model source](https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17), [license reference](https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/blob/2365baeacb507f821a0c8120fcee3d484dba7a07/LICENSE) |
| ONNX Runtime, included by sherpa-onnx | [MIT](https://github.com/microsoft/onnxruntime) |
| Alibaba DashScope Java SDK | [Apache-2.0](https://github.com/aliyun/alibabacloud-bailian-java-sdk) |
| LWJGL and OpenVR / OpenGL / GLFW bindings | [BSD-3-Clause](https://github.com/LWJGL/lwjgl3) |
| GLFW | [Zlib](https://github.com/glfw/glfw) |
| Valve OpenVR | [BSD-3-Clause](https://github.com/ValveSoftware/openvr) |
| SLF4J | [MIT](https://www.slf4j.org/license.html) |
| Java runtime bundled by jpackage | Use the license and notices supplied with the JDK used to build the package. |

Transitive Java dependencies are visible with `gradlew.bat dependencies --configuration runtimeClasspath`. Gradle Wrapper is distributed under the [Apache-2.0 license](https://github.com/gradle/gradle/blob/master/LICENSE).
