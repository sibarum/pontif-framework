# The Pontif extension API — side-effects as Pontif-interface + Java objects

Status: **LANDED (2026-06-29).** The API + the IO migration + the first GUI extension
(window-open) are on master. Builds on the event substrate (docs/events.md): an extension
supplies native-backed event types, sinks, and sources; `emit`/`action` drive them.

## The ruling (James, 2026-06-29)

**Pontif needs a first-class extension API.** An extension is a **Pontif-written interface**
(a module of ordinary-looking declarations) **plus associated Java objects** that back the
native parts, **bound by name**. This is the **single channel through which all side-effects
enter** the otherwise-pure language. The GUI is not special plumbing — it is the *first
external extension*; the previously-hardcoded `StdOut`/`StdErr`/`stdin` are "the builtin IO
extension" that predated the API.

## The contract

`Extension` (pontif-runtime, `module/Extension.java`):

- `moduleName()` — the module the extension contributes (`pontif.events`, `pontif.gui`).
- `pontifSource()` — the Pontif **interface** module: the types, events, and native function
  signatures. Native declarations carry a **placeholder body** (`-> {}`); they read as
  ordinary pure declarations. *No new Pontif syntax* — the effect boundary lives in the
  manifest, not the source. **You do not override this method.** By default it loads a
  `.ptf` file shipped as a classpath resource under `/pontif-modules/<moduleName>.ptf` (see
  `ModuleResources`), so the source lives in a real `.ptf` file — editable with Pontif tooling,
  not trapped in a Java text block. The module name is the only key; there is no second filename
  to keep in sync. (Override only for a source genuinely synthesized at runtime.)
- `effects()` — emit **sinks**, by bare event-type name. Installed into `NativeFunctions`
  qualified as `moduleName/EventName`; an `emit` routes by the event's (qualified) type.
- `calls()` — application-invoked **native functions**, by bare name (`stdin`, `window`).
  Installed into `NativeCalls`; a resolved call runs the Java object against the evaluated
  args instead of the placeholder body.

`Extensions.install(ext)` (pontif-runtime) wires the three: module source → the builtin set
(`BuiltinModules.registerExtensionModule`), effects → `NativeFunctions.register`, calls →
`NativeCalls.register` (under both bare and qualified names). Install happens at startup,
before compile/run.

## Pure builtins vs extensions

The **pure** builtin modules (`pontif.core`, `std.*`) have no Java backing. `pontif.core` ships
its source as `pontif-modules/pontif.core.ptf` (loaded via `ModuleResources`, same convention as
extensions); the `std.*` modules are built directly from IR in `BuiltinModules` (no Pontif source
at all). An **extension** is precisely a module that *does* have associated Java objects — the
side-effect channel. `all()` merges the pure builtins with every installed extension's module.

## The registries (pontif-ir) — the runtime seam

- `NativeFunctions` — emit effects, keyed by the **fully-qualified** event type (exact match,
  so a same-named struct from another module fails closed). `register` + `get`.
- `NativeCalls` — application natives (`name → args→Object`), generalizing the retired
  `NativeSources`: any arity, returning a value (a `LiveSource` for `stdin`, the for-effect
  `DriveResult` for `window`, …). `IrInterpreter`'s resolved-call path consults it and, when a
  name matches, runs the Java object instead of the body.

## Installed extensions

- **`IoExtension`** (pontif-runtime, builtin, installed by default — no external dependency):
  the `pontif.events` module + `StdOut`/`StdErr` sinks + the `stdin` source. The CLI keeps IO.
- **`GuiExtension`** (pontif-builtin-gui, the first external one): the `pontif.gui` module +
  the `window(title)` call backed by `DasumBridge` (the dasum GLFW/OpenGL toolkit). Installed
  by `GuiLauncher`, the dasum-bearing entry point kept out of the lean CLI. **No core module
  depends on dasum.**

## How to write an extension

1. Write your module's Pontif source as `src/main/resources/pontif-modules/<moduleName>.ptf`
   (the filename **is** the module name, e.g. `pontif-modules/pontif.gui.ptf`). Native
   declarations get placeholder bodies (`-> {}`); everything else is real Pontif.
2. Implement `Extension` (a **public no-arg constructor** — ServiceLoader needs it): just a
   `moduleName()` matching the `.ptf` filename, plus `effects()`/`calls()` maps of Java objects
   keyed by the bare declaration names. **Do not override `pontifSource()`** — the default loads
   your `.ptf` from the classpath.
3. Ship a provider file `META-INF/services/sibarum.pontif.runtime.module.Extension` in your module
   (one implementation class per line). That's the *only* wiring — no launcher/editor edits.
4. Make sure your module is on the runtime's classpath where it should be usable (e.g. add it as a
   dependency of `pontif-playground` for the editor). It then self-registers.
5. A program drives your effects with the event substrate: `emit YourEvent(…)` hits your sink,
   `action onYourEvent(e:YourEvent) -> …` reacts, your native functions are called directly.

## Auto-discovery (ServiceLoader)

`Extensions.installDiscovered()` loads every `Extension` on the classpath via `ServiceLoader` and
installs it; it runs once from `BuiltinModules`' static initializer, **before any module
resolution**, on every path (editor in-process compile, the spawned run subprocesses, the CLI,
tests). So an extension present on the classpath is always resolvable with **no entry-point
wiring** — the launchers and the editor no longer name individual extensions. A context whose
classpath omits an extension module (the lean CLI) simply doesn't find it, so it stays lean. A
provider that fails to load/parse is logged and skipped, so one broken extension can't take down
the runtime. The pure builtins (`IoExtension`, math) are still installed directly by
`BuiltinModules` (they live in `pontif-runtime` itself). The shaded editor fat-jar merges the
per-module provider files via the shade `ServicesResourceTransformer`; native-image picks up
`ServiceLoader.load(Extension.class)` providers on the build classpath automatically.

## Deferred

Making the pure builtins extensions too (with empty Java maps); the GUI's richer surface
(text/widgets, font atlases) and interactivity (click → `emit` → `action` → mutate a dasum
`Property`).
