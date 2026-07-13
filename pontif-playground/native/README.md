# Native builds of the Pontif Editor

`mvn -Pnative -pl pontif-playground -am package` turns the editor into a
standalone GraalVM `native-image` executable. The profile is OS-aware:

| Platform | Output |
| --- | --- |
| macOS   | `target/Pontif Editor.app` (a double-clickable bundle) wrapped around `target/pontif-editor` |
| Windows / Linux | the bare `target/pontif-editor` binary |

A single `-Pnative` invocation does the right thing per platform — the macOS
`.app` step lives in the OS-activated `native-macos` profile (see the module
`pom.xml`), which rides along with `-Pnative` on a Mac and stays dormant
elsewhere.

## Prerequisites

- **GraalVM JDK 25** with `native-image` on the `PATH` (this repo was built
  with GraalVM CE 25.0.1 installed via SDKMAN).
- **macOS**: Xcode Command Line Tools (for the system frameworks native-image
  links against). No Homebrew packages are required.
- **Windows**: the MSVC build tools.

## Building on macOS

```bash
# From the repo root. -s settings.local.xml is only needed off the corporate
# VPN (see ../../settings.local.xml); -Dmsdf.mode=prebuilt consumes the
# committed font atlases instead of the Windows-only msdf-atlas-gen tool.
mvn -s settings.local.xml -Pnative -Dmsdf.mode=prebuilt -DskipTests \
    -pl pontif-playground package

open "pontif-playground/target/Pontif Editor.app"
```

The bundle is **self-contained**: dasum's `NativeLibLoader` extracts the GLFW
and NFD dylibs (embedded in the image as classpath resources) to a temp dir at
launch, so nothing else needs to live inside the `.app`.

## Native libraries

The GUI depends on two native libraries that ship inside the dasum modules as
per-platform classpath resources under `natives/<os>-<arch>/`:

| Library | macOS arm64 file | Source |
| --- | --- | --- |
| GLFW 3.4 | `dasum-glfw/.../natives/macos-aarch64/libglfw3.dylib` | Official GLFW 3.4 macOS prebuilt (`lib-arm64/libglfw.3.dylib`, renamed) |
| NFDe 1.3.0 | `dasum-nfd/.../natives/macos-aarch64/libnfd.dylib` | Compiled from btzy/nativefiledialog-extended `src/nfd_cocoa.m` |

To rebuild `libnfd.dylib` from source (no CMake needed):

```bash
git clone --depth 1 https://github.com/btzy/nativefiledialog-extended.git
cd nativefiledialog-extended
clang -arch arm64 -dynamiclib -O2 -mmacosx-version-min=11.0 \
      -Isrc/include src/nfd_cocoa.m \
      -framework AppKit -framework UniformTypeIdentifiers \
      -install_name @rpath/libnfd.dylib -o libnfd.dylib
```

Both dylibs are declared as globbed resources in each module's
`META-INF/native-image/.../reachability-metadata.json`, so native-image embeds
the correct one for the target platform automatically.

## Distribution note

`package-macos-app.sh` applies an **ad-hoc** code signature so the app launches
locally (double-click / `open`). Shipping it to other Macs additionally needs a
Developer ID signature + Apple notarization, which is out of scope here.
