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
  manifest, not the source.
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

The **pure** builtin modules (`pontif.core`, `std.*`) have no Java backing and stay hardcoded
in `BuiltinModules`. An **extension** is precisely a module that *does* have associated Java
objects — the side-effect channel. `all()` merges the pure builtins with every installed
extension's module.

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

1. Implement `Extension`: a `moduleName`, a `pontifSource` declaring your types/events/native
   signatures (placeholder bodies), and `effects()`/`calls()` maps of Java objects keyed by the
   bare declaration names.
2. Install it (`Extensions.install`) before compiling a program that `requires` your module —
   either by default (a builtin like IO) or from a launcher (an external one like the GUI).
3. A program drives your effects with the event substrate: `emit YourEvent(…)` hits your sink,
   `action onYourEvent(e:YourEvent) -> …` reacts, your native functions are called directly.

## Deferred

`ServiceLoader` auto-discovery of extensions on the classpath (install is explicit today);
making the pure builtins extensions too (with empty Java maps); the GUI's richer surface
(text/widgets, font atlases) and interactivity (click → `emit` → `action` → mutate a dasum
`Property`).
