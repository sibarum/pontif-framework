# Type records: Declared, Inferred, Value

*Status: DRAFT (2026-07-07). The designed end-state for how Pontif represents "the type of a
position," to be migrated onto piecemeal via the `sibarum.pontif.types.TypeSystem` facade
(design-first, then strangle — see `project_typesystem_api`). Filename provisional (`type-records`
vs `sort-records` vs `type-triad` — TBD with James).*

> **Syntax note (2026-07-11, James): the top-level `type Name:[Sort]` alias/nominal-subtype
> declaration was DROPPED.** The `type` keyword is kept only as the associated-type / type-parameter
> declarator (`type X` inside traits, `[type T]` slots — `docs/associated-types.md`) and reserved for
> a possible future top-level meaning. The examples below still spell the running case as
> `type Vec3:[…]` because that is where the three-records *model* was first exposed — read them as
> **illustrative of the model, not usable surface syntax**. Under the retained spellings a transparent
> alias is `let Name:Type[…]` (see `ReusableSortTest`) and a nominal subtype is
> `struct Name:[Base](…)` (`docs/univocal-language-design.md`); the Declared / Inferred / Value
> distinction the rest of this doc argues for is unaffected by which spelling introduces the name.*

## The problem this fixes

Pontif today collapses several distinct notions of "the type here" into one `IrSort` per position,
and different passes then read that one value hoping it means what they need. It usually does —
because for a **constructed, named value** the declared type, the inferred type, and the runtime
type all carry the same nominal name, so which one you read doesn't matter.

Methods-on-type-aliases broke that coincidence and exposed the collapse. For

```
type Vec3:[{3*Decimal}]
method Vec3.sum():Decimal -> …
let v:Vec3 = {1.0, 2.0, 3.0}
v.sum()
```

the three notions **disagree**:

- what the programmer *declared* `v` to be: `Vec3` (nominal),
- what inference *proves* about the value `{1,2,3}` in isolation: `_tuple{[Decimal:@==1.0], …}`
  (anonymous — the literal has no nominal name),
- what the value *is* at runtime: a `Vec3`-stamped record.

Method dispatch read the **inferred** value (`baseName(infer(v)) = _tuple`) and looked up
`_tuple.sum` — a miss — even though `v` was declared `Vec3` and the runtime value is a `Vec3`. The
`parseLet` rule `Coercion.None → inferredSort` had *discarded* the declared `Vec3` name, keeping only
the structural narrowing.

The fix is not to pick a winner or merge them. It is to stop pretending they are one thing.

## The three records

Every typed position carries up to three separate records. They answer three different questions and
are consumed by three different mechanisms.

### 1. Declared Sort — *what was asserted here*

The nominal identity the programmer wrote: a parameter's declared sort, a `let`'s annotation, an
ascription, a declared return. **Optional** — it exists only where there is an annotation; a bare
`let x = expr` or any computed sub-expression has no Declared Sort.

- **Consumer: nominal (mechanism-2) dispatch** — methods, static methods, traits, accessors. This is
  the "identity follows the declared sort at each binding" axis. A binding declared `Vec3` has
  `Vec3`'s methods *because it was declared `Vec3`*, regardless of the value's own shape.
- It may be **coarser than the value's actual type** — `let b:Point = point3dValue` declares `Point`,
  so `b` has only `Point`'s methods even though the value is a `Point3D` (the cast law:
  *lose freely, fabricate never* — `docs/univocal-language-design.md`). The Declared Sort is an
  assertion the value must *satisfy*, not a description of what the value most-specifically *is*.

### 2. Inferred Sort — *what we can prove about the value, statically*

The tightest sort inference can establish for the value in context (`NarrowingInference`):
`[Int:@==5]` for the literal `5`, `[Int:@>=0]` for `n-1` under `n>0`, `_tuple{pins}` for `{1,2,3}`.
**Always present** — inference produces something for every expression (at worst the honest coarse
floor).

- **Consumer: the refinement machinery** — the construction/call gates, proof discharge, narrowing.
  This is the axis that keeps `let x:Int = 5` remembering `@==5`.
- It is a **sound static over-approximation** of the runtime value: the value is guaranteed to
  inhabit it, but it may be *looser* than the value (static analysis can't always prove the tightest
  fact).

### 3. Value Type — *what the value actually is, at runtime*

The concrete runtime type of an actual value — construction-stamped, fully pinned. **Runtime-only by
nature**: statically you cannot have it (that is exactly what the Inferred Sort approximates); it
materializes only when a value exists.

- **Consumer: runtime dispatch** — the fully-determined dispatch query with every argument pinned to
  its constant value (`project_dispatch_query` slice 4; `DispatchTable.resolve`). It is the semantics
  of a call; static lowering is a guarded optimization over it
  (`docs/dispatch-unification.md`, "Execution model").
- It is **richer than the Inferred Sort** — the actual value pins refinements static analysis left
  residual, which is *why* runtime dispatch resolves calls static dispatch had to leave ambiguous.

## Which record each consumer reads

| Consumer | Reads | Because |
|---|---|---|
| Nominal / method / accessor dispatch | **Declared Sort** | identity follows the declaration |
| Construction & call gates, proof, narrowing | **Inferred Sort** | refinement precision, soundly static |
| Runtime dispatch | **Value Type** | the concrete value is the final determinant |

**Computed-receiver fallback (the one place the axes touch).** A receiver with no Declared Sort —
`(a + b).sum()`, `f(x).sum()` — has no annotation for nominal dispatch to read. It falls back to the
**nominal head of the Inferred Sort**: a computed value still carries the identity it was constructed
as, and the Inferred Sort is its static proxy. So the rule is total:

> Nominal dispatch reads the Declared Sort when the position has one; otherwise the nominal head of
> the Inferred Sort.

This is why methods-on-aliases needs **no** name-stamp or merge hack: `v`'s Declared Sort is `Vec3`
(method dispatch finds `Vec3.sum`), its Inferred Sort stays `_tuple{pins}` (the gate checks
structural fit — transparency), its Value is the stamped `Vec3` (runtime resolves on it). The records
were never meant to be equal; keeping them separate makes each consumer correct by construction.

## Relationships & invariants

Along the **refinement** axis (soundness):

- `Value ⊑ Inferred` — the runtime value always inhabits its static over-approximation. This is the
  no-lie boundary: if the value could escape the Inferred Sort, inference lied.
- The value must **satisfy** the Declared Sort (checked at the construction/call gate) — but Declared
  is *not* on the `⊑`-chain with Inferred, because it is a different axis:

Along the **nominal** axis (identity):

- Declared can be **coarser** than the value's own nominal type (demotion drops methods). Declared
  never *fabricates* identity the value lacks — it only *forgets* (lose-freely/fabricate-never).
- For an anonymous value (`{1,2,3}`), the value has **no** nominal identity of its own; the Declared
  Sort is the *only* source of one. With no declaration, an anonymous aggregate stays anonymous
  (transparent) — which is exactly why a method-less tuple alias inlines and a method-hosting one
  goes nominal (`project_type_aliases`).

## Resolved: is the Value Type all you need at runtime? (mostly — under a re-stamp discipline)

> **SUPERSEDED (2026-07-18) by the view-based ruling — `docs/type-system-roadmap.md` §6.5.**
> The re-stamp discipline below is **retired.** Concrete identity is now **immutable**: a nominal
> rebinding is a *view* (the value keeps its concrete type), so the Value Type is always honest
> without re-stamping — which is exactly what this section wanted, reached by *not* mutating the
> value rather than by re-stamping it. Demotion retains (not forgets) and does not re-tag; the
> same-structure stale-stamp hole cannot occur (implicit sibling coercion is forbidden — an
> explicit cast is required). Read the section below as historical motivation; the mechanism it
> proposes (re-stamp on every nominal binding) is not the resolution taken.

> James (2026-07-07): "It might be perfectly acceptable to only keep the value type at runtime,
> assuming all methods and accessors are linked at the IR. Then you only need the value type for
> dispatch." — and: "if you run into problems, fail fast. It's not a big deal to keep a bit more
> metadata in memory."

**The Inferred Sort is always safe to discard at runtime.** It is a sound *over-approximation* of the
value, so the concrete value carries strictly more information — the value `5` pins `@==5`; the value
satisfies every refinement inference could only bound. Runtime dispatch on the value is at least as
precise as anything the Inferred Sort could have told it. (This is also why runtime dispatch resolves
calls static dispatch left residual: the value pins what the Inferred Sort couldn't.)

**The Declared Sort is safe to discard at runtime *iff* every nominal binding transfers its declared
identity into the Value Type — a re-stamp, even when it is a pure re-tag with no structural change.**
The Declared Sort's whole job is nominal identity; if that identity is stamped onto the value at the
binding, the Value Type carries it and Declared need not survive.

Two facts make this precise:

- **"All methods/accessors link at the IR" does not fully hold**, so dispatch *does* happen at runtime
  and the stamp *does* get read. Polymorphism is preserved by *not* linking trait/union/open receivers
  (`docs/dispatch-unification.md`); open/dynamic values (event payloads, deserialized data,
  heterogeneous streams) can't be monomorphized even in principle. So a concrete-typed call links and
  never consults the Value Type (devirtualization fast path — take it whenever provable), but the
  abstract cases fall to runtime dispatch on the Value Type.
- **The stamp can go stale.** Demotion (`let b:Point = point3dValue`) already re-stamps for free — the
  coercion is a projection that rebuilds the value as a `Point`, so the Value Type matches the
  declaration before anything dispatches. But a **transparent, same-structure re-tag** coerces `None`,
  so *nothing runs* and the value keeps its original stamp:

  ```
  type Vec3:[{3*Decimal}]
  type Color:[{3*Decimal}]           # same structure, different name + methods
  trait Showable { show():String }
  method Vec3.show():String  -> "vector"
  method Color.show():String -> "color"

  let v:Vec3 = {1.0, 2.0, 3.0}       # value stamped Vec3
  let c:Color = v                     # transparent → None → no projection, no re-stamp
  let s:Showable = c                  # upcast to a trait → forces RUNTIME dispatch
  s.show()                            # value still Vec3-stamped → "vector"  ❌ (c is a Color → "color")
  ```

  If Declared is discarded and the Value Type is stale, `s.show()` lies. The fix is to make
  `let c:Color = v` re-stamp the value `Color` (a re-tag; no structural change) — then the Value Type
  is correct and Declared is genuinely compile-time-only.

**Decision.** Keep the runtime to a single record (**Value Type**), and make **every nominal binding
re-stamp** the value to its Declared Sort — projection where the structure changes (demotion, already
done), a pure re-tag where it doesn't (transparent same-structure aliases, the gap above). This
matches "identity follows the declared sort at each binding": each binding *enforces* it. Inferred and
Declared are then both compile-time-only.

**Fail-fast fallback (James).** The re-stamp discipline is the preferred, clean end-state — but it is
not dogma. If threading a correct re-stamp through some binding site proves fragile or leaky, **fail
fast and just carry the Declared Sort (and Inferred, if ever needed) as runtime metadata** rather than
contorting to preserve single-record minimalism. A little extra metadata is cheap; a silent stale-stamp
lie is not. So: aim for single-record-via-re-stamp, fall back to runtime metadata the moment re-stamp
gets in the way.

## Facade surface (migration targets)

The `TypeSystem` facade should expose the two static records explicitly, so callers stop reading one
`IrSort` and guessing:

- `declaredSortOf(position)` — the annotation, or empty (nominal-dispatch source).
- `infer(expr, ctx)` — the Inferred Sort (already exists; the refinement source).
- nominal-dispatch entry that reads Declared-then-Inferred-head per the fallback rule (today
  `MethodOperatorResolver` reads only the inferred head — the first migration target).

The Value Type stays where it is — the runtime `DispatchTable` on concrete `SymExpr` values.

## Migration path

1. **This doc** — fix the model. *(here)*
2. Teach nominal (method/accessor) dispatch to read the **Declared Sort** with the computed-receiver
   fallback, instead of `baseName(infer(receiver))`. This alone makes methods-on-aliases resolve,
   with no `parseLet` collapse to undo — the Declared Sort (`LetIn.claim`, param sorts) already
   carries the name.
3. Stop the `parseLet` `None → inferredSort` rule from *discarding* the Declared Sort — carry both
   records forward rather than collapsing to one. (Sequenced after 2 so the consumer exists first.)
4. Methods-on-aliases (`project_type_aliases`) then lands as a thin feature on top: `AliasResolver`
   keeps a method-hosting alias nominal; the rest is already served. **Concrete-receiver** method calls
   (`let v:Vec3 = …; v.sum()`) work at this point via static linking on the Declared Sort — no runtime
   stamp needed.
5. **Re-stamp discipline** (later slice, only for the polymorphic same-structure case): make a
   transparent nominal rebind (`let c:Color = v`) re-tag the value to its Declared Sort, so a
   subsequent trait upcast dispatches correctly at runtime. Not needed for concrete-receiver dispatch;
   sequenced when the polymorphic case is exercised. Fail-fast fallback per above if the re-tag is
   fragile: carry Declared as runtime metadata instead.

## Relationships

- `docs/dispatch-unification.md` — the execution model (runtime = semantics, static-lowering = guarded
  optimization) is the Value-Type half of this doc.
- `project_dispatch_query` — dispatch as one query on a determinacy gradient; the three records are
  three determinacy points (Declared/Inferred static, Value fully-determined at runtime).
- `docs/univocal-language-design.md` — the cast law (lose-freely/fabricate-never) governs the nominal
  axis of the Declared Sort.
- `project_type_aliases` — the immediate consumer/motivation.
- The no-lie law — `Value ⊑ Inferred` is exactly the boundary inference must never cross.
