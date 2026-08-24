Enums: a struct with a closed set of values
===

Status: **RULED and BUILT 2026-08-24** (James). The declarator, the seal, the
type-level member, the lookup, and the enum totality tier all ship. This note
records what was decided and, more importantly, *what it reduces to* — because the
point of the feature is that it introduces almost nothing.

# The declaration

```pontif
enum ResourceType(driver:String) {
    DatabaseTable("postgres")
    LocalFilesystem("NTFS")
    RemoteHttp("tcp/ip")

    latencyBudget():Int -> match this {
        [ResourceType.RemoteHttp] -> 500
        [ResourceType] -> 5
    }
}
```

A field list shared by every case, then a member block holding the cases and
(optionally) methods. Cases and methods are told apart by shape: a method is always
`name(params):Ret -> body`, so a `:` after the balanced `)` is the discriminator.
Both are **members**, so both are newline- or `;`-terminated like every other member
list — `enum Colour { Red; Green; Blue }` on one line, `Red`/`Green`/`Blue` on
separate lines otherwise. The field list may be omitted entirely
(`enum Colour { … }`), and an enum may carry trait obligations (`enum E:[T1 & T2](…)`)
exactly as a struct does.

# What it lowers to (§1)

Nothing new. The above becomes the shipped pinned-subtype form that
`pontif-playground/examples/discriminant-pin.ptf` already demonstrates:

```pontif
struct ResourceType(
    driver:[String:@=="postgres" | @=="NTFS" | @=="tcp/ip"]
    _ordinal:[Int:@>=0 & @<3]
)
struct ResourceType$DatabaseTable  :[ResourceType:@.driver=="postgres" & @._ordinal==0]()
struct ResourceType$LocalFilesystem:[ResourceType:@.driver=="NTFS"     & @._ordinal==1]()
struct ResourceType$RemoteHttp     :[ResourceType:@.driver=="tcp/ip"   & @._ordinal==2]()
```

Each case is an ordinary struct with **no fields of its own** whose is-a morphism
pins every field of the base. That is what makes it a *singleton sort*: the
existing is-a coverage rule already demands that a `struct Sub:Base` determine every
base field, and pinning all of them determines them all. `ConstructionGate`
materializes the pinned values onto the value at construction — which is why
`ResourceType.DatabaseTable.driver` reads `"postgres"` with no demotion step, by the
same mechanism that already makes an `Exp`'s `op` read `"+"`.

The declared fields are narrowed to the closed set the cases actually supply, but
only when the declaration is a bare primitive: an already-refined or user-typed
field keeps exactly what was written, so the narrowing can never silently drop a
refinement the author put there.

`$` is the internal separator (the same one `mangleInstantiation` already uses for
`map[Int,String]` → `map$Int$String`). It never appears in source; the surface
spelling is always `ResourceType.DatabaseTable`, and diagnostics render it that way.

## `_ordinal` (§2)

Every case gets a compiler-forced `_ordinal` discriminant, pinned to its
declaration index. Leading underscore per the forced-member rule; no source may
declare a field or case starting with `_`.

It earns its place three times over:

- a **payload-free** enum (`enum Colour { Red; Green; Blue }`) has no field to be
  told apart by, and without a discriminant its cases would be one value;
- two cases may legitimately carry the **same payload** (`Draft(false)`,
  `Hidden(false)`) and must still be distinct values;
- every enum gets a **declaration order** for free, which is the `Ordinal` trait
  the KEYED work wants (docs/keyed.md).

The case *name* needs no field: each case is a nominal type, so `_type` already
carries it.

## The seal (§3)

The base struct records its complete, ordered cover as
`IrSort.Structural.sealedCases()`. This is the one genuinely new *fact*, and it is
the only reason enum is more than a naming convention. Two things follow:

**The base is abstract.** `ConstructionGate` refuses a direct construction of a
sealed type. Without this the seal is a lie: `ResourceType{driver="NTFS", _ordinal=0}`
would be a value with `LocalFilesystem`'s driver and `DatabaseTable`'s ordinal —
outside the cover, matching no case, and so falling through a match the compiler had
proved total.

**Totality is set arithmetic.** See §4.

# The type-level member (§3a)

`ResourceType.DatabaseTable` names the case's **singleton sort** and that sort's
**unique inhabitant** at once — a sort in a `[…]` or a declared-type position, a
value in an expression position. Because the sort has exactly one value, the two
readings can never disagree, which is what makes one name for both honest rather
than merely convenient.

```pontif
let f:ResourceType.LocalFilesystem = ResourceType.LocalFilesystem   # sort, then value
```

In value position it compiles to the zero-field construction of the case struct, so
no new value machinery exists: a case is a `Record` with no members, and everything
it carries arrives via the pins its declaration already asserted.

## The lookup (§3b)

`ResourceType("postgres")` is a **lookup**, not a construction. A sealed type has
exactly the values its cases name, so applying the enum to a row of literals selects
the case carrying that row; the result is identical to writing the case name. A row
no case carries is a compile error that names the cases which do exist.

A non-literal argument is refused on purpose. `ResourceType(someString)` is a
*narrowing* of the argument's sort, and the standing cast law is **widen for free,
narrow by match**. Write the refinement arm (`match s { [ResourceType:@.driver==…] … }`)
and let the construction gate discharge it, rather than have a lookup quietly become
partial at runtime.

# Match totality (§4)

Because the value-set *is* the case list, totality is subtraction over a finite
table rather than predicate complementation. `EnumCover` answers one question — which
cases can this arm match? — by substituting each case's constant field values into
the arm's predicate and evaluating. This is decided **case by case, with no solver
call**, which is exactly why the seal can be trusted with it: the reasoning is
auditable by reading three lines of a table.

```pontif
function availability(r:ResourceType):String -> match r {
    [ResourceType("NTFS")]       -> "available"
    [ResourceType.DatabaseTable] -> "needs DB access"
    [ResourceType.RemoteHttp]    -> "needs network"
}
```

Total with **no default arm**. Drop any one of the three and the error names the case
you forgot, rather than rendering an uncovered predicate.

Recognised arm forms, all reduced to a case set:

| Arm | Covers |
| --- | --- |
| `[ResourceType]` | every case (a bare arm of the scrutinee's own sort — already a catch-all) |
| `[ResourceType.RemoteHttp]` | that one case |
| `[ResourceType("NTFS")]` | every case whose fields carry that literal row |
| `[ResourceType:@.driver=="NTFS"]` | every case whose pinned values satisfy the predicate |

`[ResourceType(literal…)]` is sugar for the third row's meaning — the parser
desugars it to `[ResourceType:@.f0==lit0 & …]`. Unlike a struct pattern it is a
*filter*, not a destructure: it says nothing about `_ordinal`, and may match several
cases if the values do not distinguish them. To BIND an enum's fields, refine
(`[ResourceType:@.driver == d]`) or match a case — a positional binder list would be
a destructure of the record, `_ordinal` and all, which is not what the enum face
promises.

Undecidability is reported honestly. An arm outside the closed fragment makes the
cover question unanswerable, and the tier declines, falling back to the standing
conservation rule: no proof of totality means a default arm is required.

# Three fixes this needed in shared machinery (§5)

None is enum-specific; all three were latent gaps that enums are simply the first
feature to walk into. Each is a case of the static and dynamic sides, or two views of
the same relation, disagreeing about the is-a chain.

**The runtime claim rule now reads the is-a chain.** A `struct Exp:[BiOp:@.op=="+"]`
value's claim is `Exp`, and the matcher required the sort's name to *equal* that
claim, so `match anExp { [BiOp] -> … }` found no branch and died at runtime — even
though the static side accepts `let b:BiOp = anExp` without complaint. The runtime
test has to agree with the static one, or the compiler proves an arm reachable that
the matcher then refuses. `Refinements` now consults the nominal is-a relation
(carried on `Simplifier`, populated by `TraitRelations`) in both claim gates. Claims
are still never invented: the value's constructed type remains the only thing
consulted, just read through the relation its own declaration asserted.

**`String` comparisons now fold.** The simplifier had constant-folding rules for
`Int`, `Bool`, `Decimal`, and `Char` comparisons but not `String`, so a String
refinement was only half-decidable at runtime: equal strings collapsed by structural
identity, but *unequal* ones stayed residual. A refinement arm over a String field
therefore raised an undecidable obligation instead of simply not matching.
`RefinementRules.CMP_STR_STR` folds all six operators by `compareTo` — strings order
and compare, they just don't compute.

**A subtype now routes to a trait its base implements.** `StaticDispatch` (the call
gate's decider) held both halves of the answer and never composed them: one view
answers "does the argument's own type implement this trait" (walking the trait-extends
chain), the other answers "what does it inherit from" — and `structAncestors`' own
contract already said an `assign trait Base:T` impl is inherited by every descendant.
Asking only the first question read a `Sub` argument as *provably disjoint* from a `T`
parameter, which is a false disjointness claim: the gate's FAILED verdict is supposed
to mean provably-misroutes. `Assignability.isA` had it right all along by recursing on
the nominal base — which is why `let b:Budgeted = aSub` was fine while the call gate
refused the same widen, one more symptom of the is-a/base-chain fork. Both legs now go
through one `StaticDispatch.satisfiesTrait`, so widening to an unrefined trait an
ancestor implements is a *proved* match rather than merely not-disjoint. For enums this
is what makes `spend(Tier.Costly)` route for `function spend(b:Budgeted)`; witnessed by
the I-group in `StructInheritedTraitImplTest`, negatives included (a struct that
implements nothing, and an ancestor implementing a *different* trait, are both still
rejected).

# Open / deliberately not done (§6)

- **Positional literal patterns over ordinary structs.** `[Person("bob", age)]` still
  rejects the String literal: `parseStructFields`'s literal-clause set covers
  `Int`/`Decimal`/`Char`/`Bool` but not `String`. The enum path does not go through
  it (it produces a refinement directly), so this stays a separate, pre-existing gap.
- **Binding an enum's fields positionally.** Deliberately refused, see §4.
- **Generic enums.** `enum E[type T](…)` does not parse — there is no type-parameter
  slot on the declaration. Not scoped.
- **An enum as an is-a base.** A struct narrowing a sealed enum would add a value
  outside its own cover. Not currently rejected explicitly — the seal makes it
  unconstructible in practice, since the sub-struct's own construction routes through
  the base. Worth an explicit error.
- **Exhaustiveness across a union of enums.** `EnumCover.covered` handles a union
  *arm*, but a union *scrutinee* mixing two enums falls to the generic tiers.

# See also

- `pontif-playground/examples/enums.ptf` — the worked example.
- `pontif-playground/examples/discriminant-pin.ptf` — the same shape, hand-written.
- [subtypes.md](subtypes.md) — the one-construct framing enum specialises.
- [keyed.md](keyed.md) — where `_ordinal`'s `Ordinal` trait belongs.
