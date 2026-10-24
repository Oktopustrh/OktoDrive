# Third-party strategy for OktoDrive

OktoDrive should prefer permissively licensed upstream components and public protocol specifications so the project is not unintentionally forced into a copyleft license.

## Approved reference/material direction

- **Mumble upstream protocol/source** — BSD-style license. The protocol definitions and upstream behavior are suitable references for our own implementation, subject to retaining required notices when code or files are incorporated.
- **Google Protocol Buffers runtime/compiler** — BSD-3-Clause. Preferred replacement for hand-written protobuf parsing/encoding when we migrate the control protocol to generated message classes.
- **Opus codec** — the upstream codec uses a permissive BSD-style license.

## Audio candidates to evaluate

These are candidates, not yet dependencies. Pin an exact version/commit and verify transitive/native licensing before adding one.

- **Kopus** — Kotlin Multiplatform Opus wrapper, MIT. Attractive for Kotlin/Android integration, but verify maintenance, ABI packaging and Mumble frame requirements before adoption.
- **Concentus** — managed Java Opus implementation under the Opus/BSD-style license. Avoids JNI, but benchmark CPU/battery use on the S25 before choosing it for continuous voice.
- **opus_android** — Android Opus wrapper, Apache-2.0. Older project structure; treat as a fallback/reference unless current Android toolchain compatibility is verified.

## Reference only — do not copy into OktoDrive

- **Humla** — GPLv3+.
- **Mumla** — GPLv3.
- **Jumble / Plumble-derived GPL code** — copyleft; use only to understand architecture, behavior, sequencing and edge cases unless OktoDrive intentionally adopts GPL terms later.

Architecture ideas, protocol behavior and independently reimplemented patterns may be studied, but GPL source code must not be copied or mechanically translated into OktoDrive.

## Implementation priorities

1. Stabilize control connection and channel switching.
2. Move long-lived Mumble ownership out of Activity lifecycle and into a service/application-level connection manager.
3. Replace hand-written protobuf encoding/parsing with generated messages from permissively licensed upstream protocol definitions.
4. Add reconnect/backoff and connection-state handling.
5. Add Opus audio receive/transmit and push-to-talk using a separately verified permissive dependency where practical.
6. Add automated tests for framing, protobuf messages, channel switching and reconnect behavior.

Before adding any dependency, record its repository, exact version/commit and license here or in a dedicated notices file.
