# AnySound technical guide

This document covers development, packaging, integration details, and validation. For installation and everyday use, see the [Chinese user guide](../README.md).

[Run](#run-from-source) · [Packaging](#packaging) · [Branches and releases](#branches-and-releases) · [Architecture](#architecture) · [Models](#offline-models) · [Rendering](#overlay-rendering) · [Tests](#testing-and-acceptance)

## Platform and toolchain

The release target is **Windows 10/11 x64 with SteamVR and VRChat**. Quest and PICO headsets use their PC streaming connection and the controller profile reported by SteamVR. Standalone headset applications and PCVR sessions that bypass SteamVR are outside the supported overlay path.

macOS supports desktop development, local recognition, and shared logic tests. The SteamVR integration is enabled only on Windows. Building a Windows application image or a portable ZIP with a Java runtime requires Windows x64; a Windows Launcher directory can also be assembled on macOS.

| Component | Pinned version |
| --- | --- |
| Java toolchain | 21 |
| Gradle Wrapper | 9.3.0 |
| Kotlin | 2.3.20 |
| Compose Desktop | 1.10.3 |
| Kotlin coroutines | 1.10.2 |
| Kotlin serialization | 1.9.0 |
| sherpa-onnx Java API and native libraries | v1.13.5 |
| Alibaba DashScope Java SDK | 2.23.1 |
| LWJGL | 3.3.6 |

Install JDK 21 and use the included Gradle Wrapper. Kotlin, Python, Node.js, and a separate speech service do not need to be installed. The initial build resolves dependencies from Maven Central, Google Maven, the Gradle Plugin Portal, and JitPack. See [build.gradle.kts](../build.gradle.kts) and [settings.gradle.kts](../settings.gradle.kts) for the authoritative configuration.

## Run from source

On Windows:

```powershell
.\gradlew.bat run
```

On macOS, or a Linux development host with the necessary desktop libraries:

```sh
./gradlew run
```

Use an isolated profile for development or UI checks:

```sh
./gradlew run -PappDataDir=build/test-profile
```

Keep the default `host` target when running or testing locally. `-PtargetPlatform=windows-x64` selects Windows native dependencies; it does not make them runnable on a Mac.

## Packaging

### Launcher directory

Both development launches and packaged applications enter through the standalone Java Launcher adapted from Perlica. It loads the main application and its dependencies, sets the thread context class loader, and does not require Perlica's `bot` or `ui` arguments.

Build a Launcher for the current host:

```sh
./gradlew prepareLauncher
```

Or assemble a Windows x64 Launcher on macOS:

```sh
./gradlew prepareLauncher -PtargetPlatform=windows-x64
```

Both commands replace `build/launcher` with the selected platform's artifacts:

```text
build/launcher/
  anysound-launcher.jar
  anysound-main.jar
  libs/
  licenses/
```

Copy the **entire directory** to the target computer. This distribution requires an installed Java 21 runtime of the matching architecture; Windows needs Java 21 x64. Start it from the Launcher directory:

```powershell
java -Dfile.encoding=UTF-8 --enable-native-access=ALL-UNNAMED -jar anysound-launcher.jar
```

Paths are resolved relative to the Launcher JAR, so launching from another working directory or a path containing spaces or Chinese characters is supported. The default host package contains only that host's native libraries. The Windows target selects Windows Skiko, sherpa-onnx, and LWJGL native libraries; copying a host Mac package to Windows is insufficient.

On Windows, Java 21's `java.exe` converts command-line arguments using the system ANSI code page. Characters outside that code page can become `?` before the Launcher runs; `-Dfile.encoding=UTF-8` does not change this conversion. For a Chinese installation path on an English Windows system, change to the Launcher directory first and use the relative JAR name shown above. Unicode paths and resources inside the JVM are still supported.

Check the layout without opening a window:

```powershell
java -jar anysound-launcher.jar --check
```

This checks application files, the entry point, and the presence of the current platform's Skiko native resource. It does not load native libraries or validate drivers, microphones, or VR hardware.

To create the Windows Launcher ZIP on a Mac:

```sh
./gradlew launcherZip -PtargetPlatform=windows-x64
```

On Windows x64, run `.\gradlew.bat launcherZip`. The output is `build/distributions/AnySound-0.1.0-windows-x64-launcher.zip`, with the complete Launcher under `AnySound/` and documentation alongside it. It does not bundle Java or speech models.

### Windows portable ZIP

Run on **Windows x64 with JDK 21**:

```powershell
.\gradlew.bat clean test portableZip launcherZip
```

The current output is `build\distributions\AnySound-0.1.0-windows-x64.zip`. Extract it and run `AnySound\AnySound.exe`. The ZIP includes the Java runtime, application, Compose, speech and OpenVR native dependencies, user guide, and the `docs` directory. Speech models are downloaded or imported separately. The package does not install SteamVR or configure startup at login.

`createDistributable` builds the host application image under `build/compose/binaries/main/app`. macOS cannot cross-build the Windows jpackage image or the runtime-bundled Windows ZIP.

## Branches and releases

The repository is [MicIsHere/AnySound](https://github.com/MicIsHere/AnySound). `nightly` is the development branch; `stable` is the release branch. The branch name `nightly` does not imply a scheduled build or automatic publication.

To publish tested development work:

```sh
git switch stable
git merge nightly
git push origin stable
git switch nightly
```

Every push to `stable` triggers the [Windows stable release workflow](../.github/workflows/windows-build.yml). It can also be started from the Actions page using **Run workflow** with `stable` selected; runs on other branches skip the release job.

The Windows x64 job:

1. Installs Temurin JDK 21 and runs a clean build, the regular tests, `portableZip`, and `launcherZip`.
2. Extracts both ZIPs into paths containing spaces and Chinese characters, then runs Launcher `--check` through the bundled Windows executable and the installed Java runtime respectively. These are layout/entry-point checks, not GPU or hardware acceptance.
3. Generates `SHA256SUMS.txt` and uploads the packages as Actions artifacts. Test reports from both modules are uploaded even if the build fails.
4. Creates a draft Release for the exact built commit, uploads both ZIPs and checksums, then publishes it as the latest stable release.

Release tags are `stable-<workflow run number>` (for example, `stable-1`), separate from the application version in `build.gradle.kts`. Each new run gets its own Release even if the application version has not changed. Retrying the same run updates its existing Release and replaces the same asset names. Releases are published only after build, tests, and package checks succeed; interrupted first-time uploads leave a draft.

The workflow uses GitHub's automatic `GITHUB_TOKEN` with `contents: write` in the release job. No personal access token or additional secret is required. Repository or organization Actions policies must allow the workflow and this permission. Release jobs are serialized so they do not upload concurrently.

The portable ZIP includes Java; the Launcher ZIP requires Java 21 x64. Models remain separate in both. Windows VR and live cloud service acceptance still require the hardware checklist below.

## Architecture

The Gradle build contains a Kotlin JVM application module and the independent Java `anysound-launcher` module. Application packages live under `src/main/kotlin/io/anysound`:

| Package or class | Responsibility |
| --- | --- |
| `ui` | Compose desktop main window, settings, and IME-aware text input |
| `audio` | Java Sound device capture and PCM conversion |
| `speech` | Recognition engines, model management, and recording lifecycle |
| `text` | Voice text cleanup and Unicode-safe message splitting |
| `osc` | OSC packet encoding and the serial send queue |
| `vr` | SteamVR application registration, input actions, and overlay rendering |
| `config` | Settings validation and persistence |
| `AppController` | Shared state and coordination between the desktop, recognizers, sender, and VR |
| `VoiceSession` | Press, release, finalization, confirmation, cancellation, and stale-result rejection |
| `ChatSender` | Serial message batches, rate limiting, and cancellation of unsent segments |

Kotlin coroutines and `StateFlow` drive shared state. Both speech engines implement `SpeechRecognizer`: start, accept audio, finish, cancel, and close. Partial text callbacks update the preview; `finish()` returns the final text, and errors stop the session.

## Audio and recognition lifecycle

Java Sound supplies **16 kHz, mono, signed 16-bit PCM**. Capture tries the target format and supported 48 kHz / 44.1 kHz device formats, with channel and sample-rate conversion as needed.

One held button represents one recording, even if the recognizer detects multiple sentences. Partial results never trigger a send. On release, the session combines finalized text, applies cleanup, and either queues the complete result once or exposes an editable confirmation preview. Silence does not produce a chat message.

Recordings are limited to 60 seconds; final recognition has a 20-second timeout. Cancellation, microphone failure, input loss, and recognition errors invalidate the session so that late callbacks cannot send or replace a newer transcript. Audio backlog also cancels the session. Shutdown waits for active native recognition to finish releasing its stream before freeing the cached model.

### Offline models

`LocalRecognizer` uses sherpa-onnx on the CPU, with **two worker threads per model**. Model instances are reused across recordings.

- **Fast mode:** bilingual Streaming Zipformer provides the preview and final transcript.
- **Accurate mode:** the same Zipformer provides the preview; SenseVoice Small reprocesses the original in-memory recording on release. Only its final result is sent. Failure does not fall back to a partial streaming transcript.

Accurate mode buffers at most 60 seconds of raw audio in memory. Stream resources and recording buffers are released after completion or cancellation. Models remain cached for the next recording; stopping capture does not unload them. A different local model configuration or preferred language takes effect on the next local recording and replaces the cache. Selecting cloud mode does not currently evict an already-loaded local model.

Model file sizes are not runtime memory budgets. ONNX Runtime needs weights, working buffers, and intermediate tensors in addition to the JVM and UI. Java's `-Xmx` limits the Java heap, not native inference allocations.

#### Streaming model

Download the four files below from this [pinned bilingual Zipformer revision](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/tree/98590b7ed6443e77b714204da2757d75e1a642f4), place them in one directory, and select that directory as the streaming model directory in Settings. The download is approximately 190 MiB.

```text
encoder-epoch-99-avg-1.int8.onnx
decoder-epoch-99-avg-1.int8.onnx
joiner-epoch-99-avg-1.int8.onnx
tokens.txt
```

#### Refinement model

Accurate mode additionally requires this [pinned SenseVoice revision](https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/tree/2365baeacb507f821a0c8120fcee3d484dba7a07), approximately 228 MiB:

```text
model.int8.onnx
tokens.txt
```

Put these files in a **separate directory** and select it as the accuracy model directory in Settings. The two token files are different and must not be mixed.

`ModelFiles` pins filenames, revisions, and SHA-256 hashes. Downloads reuse valid files and validate completed files before accepting them. Import supports these model revisions, not arbitrary ONNX models. The bundled sherpa-onnx Java API and platform native library versions must match.

#### Preferred language

Accurate mode defaults to Mandarin Chinese. Supported SenseVoice language codes are `zh`, `auto`, `en`, `yue`, `ja`, and `ko`. The selected code is passed to `OfflineSenseVoiceModelConfig.setLanguage`. It affects final refinement only; streaming previews and Fast mode remain bilingual Chinese/English.

Settings without a language field default to Chinese; settings without a local mode field default to Fast. Changing language does not require another model download. SenseVoice language conditioning can reduce short Mandarin utterances being interpreted as English, but does not guarantee correct recognition of every accent, name, or mixed-language phrase.

### Cloud recognition

`AlibabaRecognizer` uses the official DashScope Java SDK. Defaults:

| Setting | Value |
| --- | --- |
| Region | Beijing |
| Model | `paraformer-realtime-v2` |
| WebSocket endpoint | `wss://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference` |
| Credential | Session input or `DASHSCOPE_API_KEY` |

The workspace ID replaces `{WorkspaceId}`. Accounts using a different official endpoint can supply the full URL, such as `wss://dashscope.aliyuncs.com/api-ws/v1/inference` when supported by that account. A workspace ID is unnecessary when the endpoint contains no placeholder. Endpoints must use `wss://` and cannot include URL user information.

The SDK's built-in disfluency removal is disabled so application cleanup switches remain in control. Sentence revisions are merged by sentence identity instead of appended repeatedly. Heartbeats are ignored. Cancellation invalidates callbacks and closes the recognition connection.

Only explicit cloud mode selection uploads captured audio. Network errors, invalid credentials, insufficient quota, and timeouts stop the recording's send; there is no automatic provider fallback or audio replay. SDK error details are replaced with a generic message because they may contain credentials or transcripts. See the [official service guide](https://help.aliyun.com/zh/model-studio/real-time-speech-recognition-user-guide).

## Text processing and OSC

Voice cleanup has two independent switches, both enabled by default: conservative filler removal and comma/period cleanup. Filler removal checks boundaries before punctuation is removed, rather than deleting substrings inside ordinary words. The default list contains the Mandarin fillers `嗯` and `呃`. A phrase without a clear boundary, such as `嗯我觉得`, is retained conservatively.

Question marks, exclamation marks, and structural punctuation in decimals, versions, email addresses, and URLs are preserved. Fast mode does not add punctuation; SenseVoice may produce punctuation and normalized numbers. Typed text and manually edited confirmation text bypass further cleanup.

| OSC message | Arguments |
| --- | --- |
| `/chatbox/input` | Text string, `true` to submit, configured notification-sound boolean |
| `/chatbox/typing` | Boolean typing state |

The default destination is `127.0.0.1:9000`, configurable in Settings. OSC uses UDP; the application reports a datagram as sent, never as acknowledged or received by VRChat.

Messages share a serial queue. A long message finishes its segments before the next batch starts. Splitting prioritizes sentence and whitespace boundaries, then safe grapheme boundaries, with these limits:

- 144 UTF-16 code units per segment, without splitting emoji or combining sequences.
- At most nine explicit lines per segment; VRChat's own visual wrapping can impose additional display limits.
- 14,400 UTF-16 code units per submission and 100 pending segments in the queue.
- A default interval of three seconds, configurable from 1.5 to 30 seconds.

Cancellation drops active recording/confirmation work and unsent queued segments. Sent messages cannot be recalled. Idle cancellation leaves the overlay state unchanged, and repeated cancellation does not restart its notice timer. `EnterSendGuard` handles either ordering of Windows IME composition commits and the physical Enter event.

## SteamVR input and registration

The application registers a persistent OpenVR overlay application and action manifest, verifies its identity, and associates its process. Registration is retained at exit; reconnecting an updated build refreshes the manifest. Manifest paths support Unicode and spaces. Default Touch bindings leave physical buttons unassigned so users can select controls that suit their VRChat setup.

Four actions are exposed: hold to transcribe, confirm, cancel, and toggle overlay. A press binding holds recording active immediately; a long-press binding starts after SteamVR's threshold. After cancellation, input handling continues tracking the held recording action until its actual release. It must not clear the held flag and inadvertently restart recording while the user is still holding the button.

The binding button calls OpenVR with `showOnDesktop = false`, opening the editor in the headset even when clicked from a SteamVR desktop panel. Binding UI failures retain the input connection and overlay, and provide a manual route through SteamVR controller settings.

`OpenVrBindingApi` calls the `IVRInput_010` `OpenBindingUI` slot through a compatibility shim for the LWJGL table layout. Its ABI has a native regression test; changes to LWJGL or OpenVR require rechecking it. Input cancellation callbacks are named explicitly to avoid accidentally calling `CoroutineScope.cancel()` inside the VR polling coroutine.

## Overlay rendering

The desktop preview and VR overlay share the same `OverlayPanel` composable. The render path is:

```text
CanvasLayersComposeScene
    → Skia GPU surface
    → OpenGL RGBA8 texture
    → LWJGL VROverlay_SetOverlayTexture
```

A hidden GLFW context, a persistent Compose scene, and a 1024 × 384 texture are reused while connected to SteamVR. This does not capture the desktop window or perform per-frame CPU pixel readback. SteamVR may copy the texture internally, so the full compositor path is not claimed to be zero-copy.

Content changes and Compose invalidations request frames, capped at 60 fps. Static scenes are not redrawn continuously. Each frame clears color and alpha to prevent residual glyphs. GPU drawing is completed before submission; shutdown releases OpenVR's reference to the texture before destroying graphics resources. Normal UV bounds `(0, 0)–(1, 1)` pair with the Skia bottom-left surface origin; an additional vertical flip would invert the image.

Entrance animation lasts approximately 500 ms and exit approximately 400 ms. The overlay retains outgoing content until the Compose exit animation completes, then hides the OpenVR panel. Meter smoothing, a recording pulse, a working spinner, and final-text transitions also run in Compose. Live subtitle updates do not restart a transition per character. An interrupted exit continues from the current animation state; hidden overlays stop continuous animations.

Completed work remains visible for three seconds. A real cancellation shows its notice for two seconds; idle cancellation does not show UI. Errors and confirmation previews stay visible until resolved. Position, size, distance, and opacity are configurable.

Windows graphics drivers must support **OpenGL 3.3**. On systems with multiple GPUs, AnySound and SteamVR should use the same GPU. Rendering failures surface as connection errors while desktop text sending remains available. `CanvasLayersComposeScene` is an internal Compose API; upgrading the pinned Compose version requires rendering regression checks.

## Configuration and data

The default directory is `%APPDATA%\AnySound` on Windows and `~/.anysound` on macOS/Linux when `APPDATA` is absent. `-Danysound.dataDir` overrides it; the Gradle `appDataDir` property supplies that JVM option for development.

```text
settings.json    Ordinary preferences; no API Key
models/          Downloaded offline models
steamvr/         Application/action manifests and example bindings
logs/launch/     Launcher startup and failure logs
```

Settings are validated and saved through a temporary file and replace operation. Unknown JSON fields are ignored. Model paths and other ordinary preferences are persisted; API Keys are session-only or read from the environment. Launcher logs omit launch arguments and environment variables.

Recordings and chat history are not written to disk. Current transcript/message state is retained only for the application session. Model files are intentionally persistent and stored separately from application binaries.

## Testing and acceptance

Run the regular suite on the host platform:

```powershell
.\gradlew.bat test
```

On macOS/Linux, use `./gradlew test`. Tests include the Launcher module and do not require a headset, API Key, or microphone. Coverage includes text cleanup, Unicode splitting, settings compatibility, IME commit protection, real loopback UDP, queue timing/cancellation, recording lifecycle and late callbacks, audio format conversion, SteamVR registration helpers, and overlay rendering.

Launcher subprocess tests keep command-line arguments in ASCII to accommodate Java 21's Windows launcher, while retaining Chinese installation directories, nested library paths, filenames, and resource contents. They use a different working directory from the Launcher directory to check JAR-relative resolution. Child JVMs explicitly set `stdout.encoding` and `stderr.encoding` to UTF-8, matching the test output reader; `file.encoding` alone does not control Windows console output encoding.

Regular overlay checks use a Skia software surface to verify redraws, transparency, animations, and residual pixels. Generated images are under `build/reports/tests/overlay-*.png`. Software rendering does not validate the Windows GPU or OpenVR compositor.

### Optional Windows GPU smoke test

Run in an interactive Windows desktop session with an OpenGL 3.3 driver:

```powershell
.\gradlew.bat :test -PgpuOverlaySmoke=true --tests io.anysound.WindowsGpuOverlayTest
```

This verifies actual OpenGL textures, orientation, alpha, updates, and context reconstruction without requiring a headset. It is skipped during ordinary tests and cloud packaging. OpenVR submission and in-headset appearance still need hardware validation.

### Optional model smoke tests

These download the pinned models and public sample audio; they do not record the microphone:

```powershell
.\gradlew.bat :test -PmodelSmokeDir=build/smoke-model -PrefinementSmokeDir=build/smoke-sensevoice --tests io.anysound.LocalRecognitionSmokeTest
```

Omit `refinementSmokeDir` to test Fast mode only. Checks cover partial/final recognition, model reuse, no reuse of cancelled audio, silence and pauses without duplicated sentences, and preservation of intentionally repeated speech. Test durations and sample transcripts are not general accuracy or performance benchmarks.

### Hardware acceptance

Use the [Windows PCVR acceptance checklist (Chinese)](WINDOWS_ACCEPTANCE.md) for packaging on a machine without Java, Chinese IME input, real microphones, live cloud recognition, Quest/PICO streaming, controller bindings with VRChat in the foreground, overlay orientation/animation, and long-running resource use. Automated tests and a successful build do not establish these hardware results.

## References and licenses

- [VRChat OSC interface](https://docs.vrchat.com/docs/osc-as-input-controller)
- [SteamVR Input](https://github.com/ValveSoftware/openvr/wiki/SteamVR-Input)
- [sherpa-onnx Java API](https://k2-fsa.github.io/sherpa/onnx/java-api/non-android-java.html)
- [Third-party components and licenses](../THIRD_PARTY_NOTICES.md)
- [Perlica Launcher license](../anysound-launcher/LICENSE)
