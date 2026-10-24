# Third-party strategy for OktoDrive

OktoDrive should prefer permissively licensed upstream components and public protocol specifications so the project is not unintentionally forced into a copyleft license.

## Approved reference/material direction

- **Mumble upstream protocol/source** — BSD-style license. The protocol definitions and upstream behavior are suitable references for our own implementation, subject to retaining required notices when code or files are incorporated.
- **Google Protocol Buffers runtime/compiler** — BSD-3-Clause. This is now integrated for generated Mumble control messages, replacing the hand-written protobuf parser/encoder.
- **Opus codec** — the upstream codec uses a permissive BSD-style license.

## Audio candidates to evaluate

These are candidates, not yet dependencies. Pin an exact version/commit and verify transitive/native licensing before adding one.

- **Voxkit Kopus** — Kotlin Multiplatform Opus wrapper, Apache-2.0. Android is supported through JNI/NDK and the current README states it is based on libopus 1.5.2. Attractive for Kotlin/Android integration; verify the exact Maven version, ABI packaging and Mumble packet requirements before adoption.
- **Concentus** — managed Java Opus implementation under the Opus/BSD-style license. Avoids JNI, but benchmark CPU/battery use on the S25 before choosing it for continuous voice.
- **opus_android** — Android Opus wrapper, Apache-2.0. Older project structure; treat as a fallback/reference unless current Android toolchain compatibility is verified.

## Reference only — do not copy into OktoDrive

- **Humla** — GPLv3+.
- **Mumla** — GPLv3.
- **Jumble / Plumble-derived GPL code** — copyleft; use only to understand architecture, behavior, sequencing and edge cases unless OktoDrive intentionally adopts GPL terms later.
- **HamMumble** — GPLv3; useful as a behavioral/architecture reference only.

Architecture ideas, protocol behavior and independently reimplemented patterns may be studied, but GPL source code must not be copied or mechanically translated into OktoDrive.

## Implementation priorities

1. Stabilize control connection and channel switching.
2. Move long-lived Mumble ownership out of Activity lifecycle and into a service/application-level connection manager.
3. Use generated protobuf messages from permissively licensed upstream protocol definitions. **In progress / integrated for the current control messages.**
4. Add reconnect/backoff and connection-state handling. **Integrated in the current test branch.**
5. Add Opus audio receive/transmit and push-to-talk using a separately verified permissive dependency where practical.
6. Add automated tests for framing, protobuf messages, channel switching and reconnect behavior.

Before adding any dependency, record its repository, exact version/commit and license here or in a dedicated notices file.
