

As of 2026-06-08-ET
This document represents the gold standard of what direction the project should be going in.
This date should be updated every time this file changes.

Motivation behind this syntax choice:
We're working towards max unification of all design principles.

---
STATUS (2026-06-08): IMPLEMENTED. Every slice landed + committed; the
authoritative per-slice detail lives in `docs/univocal-implementation-plan.md`.
Deltas from this draft:
- `self -> this`: DONE (alt-syntax receiver). `@` is unchanged — it's the
  refinement subject, distinct from the receiver.
- The `requires @` / "outside the universe" world-boundary: DROPPED. The in-type
  pipeline's `let` stages call global functions by name, so no import was needed;
  dropping it removed the parent-scope question entirely.
- `^` power operator: DONE — no longer out of scope.
- `@.z` is the canonical field-in-pin spelling (the bare `@z` forms below are loose).
---

"Univocal" - said in one sense of many things.
A univocal type system.
Now THAT sounds cool.

Breaking change required: Refactor self -> this. "this" is the most widely used keyword for this purpose.
Python uses self but it's an explicit argument. "this" is typically an automatic keyword, like in Pontif.



```
struct Point(x:Int,y:Int)

# Inheritance with demotion rule - which is always required.
# the @.x and @.y belong to Point. x and y from the constructor arguments
# Demotion provably loses information because of z, but that's fine
struct Point3D:[Point:@.x==x & @.y==y](x:Int,y:Int,z:Int)
# This variation is also valid and uses positional arguments instead.
# this.x and this.y could have been used in the return type also.
# But x and y can be used instead beacuse they are in the arguments list, and so are in scope.
# The return type may access variables from the arguments, always.
struct Point3D:[Point(x, y)](x:Int,y:Int,z:Int)

# b is a Point with the x and y from a, missing the z.
# z is still recoverable from a but not from b.
let a = Point3D(2,3,5)
let b:Point = a
# let c:Point3D = b # Compile error, can't synthesize data.

# Promotion comes separately.
# It can be a method or a dispatch function.
# Note the destructuring syntax on point referenced in the return type.
# Pontif types are context-aware.
function promote(point:[Point.{x,y}], z:Int):Point3D{x, y, z};
# The semicolon without a function body triggers function synthesis.

# Now it's Point3D(2,3,7)
let promoted:Point3D = promote(b, 7)

# "@" means the current token being described, "this" means the current statement's subject (Point)
# On methods, "this" always refers to the instance of the owning type.
# "this" is necessary here because there's no argument for the instance.
# This method is also able to synthesize because it's uniquely constrained.
method Point.promote(z:Int):[Point3D{this.x, this.y, z}];

# Using a method enables type inference
let promoted = b.promote(11)

# Or, even simpler, using value synthesis:
let x:[Point3D:@.z==0] = b;

# No changes to proofs identified yet.
```


Value Synthesis:

```
# semicolon is now the official function and value synthesis directive.
# Should compile-error if synthesis cannot be determined.
let zero:[Decimal:0];

# From the example above:
# let c:Point3D = b; # This would also compile error.
# let x:[Point3D:@z==0] = b # This would also compile error, value synthesis required.
# This one works. It defines z and triggers synthesis.
let x:[Point3D:@z==0] = b;
```


Computation:
```
# At some point you'll need to call functions within functions
# Might be tempted to do something like this:
# function magnitude(p:[Point.{x,y}]):[Decimal:@==sqrt(...)];
# Except, you can't use external references within the type system like that.
# You could include them as metareferences
# function magnitude(p:[Point.{x,y}], sqrt:[Function(Decimal):Decimal]):[...];
# Or just write a normal function, like the peasentry
function magnitude(p:[Point.{x,y}]):Decimal -> sqrt(x^2 + y^2)

# Or the mildly more sophisticated "monadic" method: the `let` stages compute,
# the final pin returns. Global functions (sqrt) resolve by name — no import —
# so the `requires @` stage was DROPPED (see STATUS).
function magnitude(p:[Point.{x,y}]):[
    let m:Decimal = sqrt(x^2 + y^2) ->
    Decimal:@==m
];
# Gotta really stretch the pinky out on that one.

```

Requires from outside the universe:
DROPPED (see STATUS) — the in-type pipeline needs no import; global functions
resolve by name. The `$fqn` import simplification can return if a non-global
ever needs naming.

Out Of Scope:

a^b should be the power operator, as Int^Int->Int or if either is a Decimal, Decimal^Decimal->Decimal.
DONE (no longer OOS): `^` landed — Int^Int and Decimal^Int (Decimal-promoted),
binding tighter than `*`. Decimal^non-integer (transcendental) stays out of scope.
