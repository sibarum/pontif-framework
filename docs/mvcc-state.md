# MVCC state: the runtime is a transactional store, and it's the value model

Status: **DESIGN — PROPOSED (2026-08-06).** Converged in a design conversation with James, extending
the concurrent runtime (`docs/orchestration.md`, the `over thread` tier / cuts 2–3c). The load-bearing
*intent* is **RULED** by James (a custom, tailor-fit native MVCC; cross-conductor reads allowed; feedback
loops as a first-class strength; unmanaged side-effects explicitly excluded; built custom, not a foreign
DB embedded). The *mechanisms* here — version-as-barrier, self-advancing loop timestamps, well-clocked
cycles as cut-3c's successor, frontier reclamation, the per-tier consistency ladder — are **PROPOSED /
DERIVED**, awaiting ratification.

Read alongside [`docs/orchestration.md`](orchestration.md) (the concurrency model — lanes, mailboxes,
drive-to-quiescence, the conductor graph), [`docs/reactive-gui.md`](reactive-gui.md) (the conduit fold —
state as an immutable threaded value), [`docs/stream-queries.md`](stream-queries.md) and
[`docs/keyed.md`](keyed.md) (the native `Query` / KEYED surface this store backs).

Markers: **RULED** = James ruled it in conversation · **DERIVED** = follows from ruled material + the
standing principles · **PROPOSED** = Claude's suggestion awaiting a ruling.

---

## 0. Thesis

Pontif's mutable state is not backed by a database — **it *is* a database**, and the same object is the
language's value model. Multi-version concurrency control (MVCC) is normally an expensive subsystem: a
storage engine keeps old versions of rows so readers see a consistent snapshot without blocking writers.
Pontif has already paid that subsystem's whole price, for other reasons:

- **Every value is immutable.** A conductor never mutates state in place — it *replaces* an immutable
  `RecordValue` cell (`docs/orchestration.md`, mutable single-owner state; cut B). A "version" is just a
  retained snapshot, and structural sharing makes retaining a chain of them close to free.
- **Every conductor is a single writer.** Its state cell is mutated by exactly one thread (the lane that
  owns it), so there are **no write–write conflicts per conductor** — the hardest part of MVCC (conflict
  detection between concurrent writers to one row) is *absent by construction*.
- **Writes already flow through queues.** A cross-conductor write is a message into the owner's inbox
  (cut 3b), serialized by the single owner.

So the MVCC store is not a foreign engine we bolt on; it is a thin bookkeeping layer — a version clock and
a version→snapshot index — over structures that already exist. This is **RULED**: build it custom,
tailor-fit, native; do **not** embed a relational/page engine (SQLite/H2/…) on the hot path, because a
foreign store reintroduces exactly the object–relational marshaling tax that the native substrate avoids,
and its concurrency model would not be ours. (An embeddable pure-Java MVCC engine may later serve *only*
the durable journal — §11 — never the hot path.)

The payoff: **atomicity questions dissolve, feedback loops become the system's strongest feature rather
than a hazard, and densely-interconnected dynamic runtimes become feasible without hand-rolled corruption
resilience.** "A transactional database, without the database."

### The one boundary (RULED)

The guarantees are about **managed state** — memory Pontif owns and versions. **Unmanaged side-effects**
— third-party systems, network resources, sockets, files, the display's actual pixels — are **not
covered, and we build no API that pretends to make them atomic.** An API that promises atomicity across a
resource we do not control is the RPC-transparency lie wearing a database hat; it is the same dishonesty
the placement model already refuses with its `Unreachable`-typed boundaries (`docs/orchestration.md`,
"honest types"). A transaction is a guarantee about *our* memory, full stop. Effects on the outside world
are ordered against version boundaries (§5) but never rolled back.

---

## 1. What we already have (the substrate)

From the concurrent-runtime cuts, the interpreter already holds:

- `conductorState : Map<String, RecordValue>` — each conductor's single-owner state cell, concurrent
  (cut 2), one writer per key.
- `conduitState` — the conduit fold's threaded `S`, concurrent (cut 3).
- Lanes + mailboxes (cut 3b) — each THREAD conductor and the main thread is a lane with an inbox; a
  cross-lane `emit` hands an immutable event to the owning lane's inbox; the owner fires it on its own
  thread; `driveLanesToQuiescence` drains + joins.
- `ConductorCycleCheck` (cut 3c) — rejects a cross-conductor emit cycle at compile time, because a cycle
  is the shape drive-to-quiescence cannot terminate.

MVCC is the next layer *on this*: it makes cross-conductor **reads** first-class and consistent, and — the
prize — it turns cut-3c's blanket "no cycles" into "**well-clocked** cycles are not just allowed but
central."

---

## 2. Versions (DERIVED)

A single monotone **logical clock** — an in-process `AtomicLong`, the *version* — orders all committed
state. Each conductor's cell becomes a small **version chain**: a sequence of `(version, RecordValue)`
snapshots, newest at the head, older ones retained until no reader can still request them (§7).

- A **commit** (a conductor replacing its cell) bumps the clock and appends `(V, newValue)` to that
  conductor's chain.
- A **snapshot as-of `V`** reads, from any conductor's chain, the newest entry with version ≤ `V`.

Because the values are immutable and retained, a snapshot is a *consistent cut* across the entire hive at
one logical instant, obtained with **no locks and no copying** — just an atomic read of the clock and
chain-head walks. This is the property a normal database spends a storage engine to buy; here it is a
consequence of immutability.

The clock is the **only** new shared coordination point. It is not a data lock (an atomic counter never
blocks a reader), and it is strictly in-process — §8 governs what happens at a node boundary, and the
answer is emphatically *not* "one global clock across the network."

---

## 3. Reads: cross-conductor access is a query over a snapshot (RULED surface, DERIVED mechanism)

Revising the earlier "conductor methods are not exposed": **a conductor may read another conductor's
state** (RULED). The exposure is deliberately narrow:

- **Reads are pure queries over a snapshot.** A cross-conductor read runs a *query* — a pure projection
  over an immutable snapshot; it cannot `emit`, cannot mutate, has no effects. (This also settles "on whose
  thread does the method run?" — a pure query over an immutable snapshot runs safely on the caller's
  thread.) This is the natural home for the existing native `Query` / KEYED surface (§10), not a second,
  foreign query language.
- **Writes remain messages** into the owner's inbox (cut 3b). No conductor writes another's cell.

A single field read was *already* race-free before MVCC (the cell reference is read atomically and the
value is immutable — you get a whole old or whole new snapshot, never a torn one). What versioning adds is
**compositional** consistency: read `A.x` then `A.y`, or read `A` and `C` together, and have them agree
**as-of one version**. That read-skew — a reader seeing a mixture of pre- and post-update state — is the
real hazard of naive cross-reads, and **snapshot isolation is its exact cure**. A read-only snapshot
transaction over an immutable versioned store **never conflicts with anything and never aborts.**

**The transaction unit is one handler firing.** When a handler fires, it pins the current version `V₀`;
*every* read it makes — its own state, other conductors' states — is as-of `V₀`, so the whole reaction
sees one coherent world. Its writes commit at a new version on completion. Single-owner writes mean that
commit never conflicts — **no aborts, no retries** for the single-conductor case.

**Contract (RULED).** "Read your writes across conductors" is not a wart to document — it is **not even a
coherent notion**, because *there is no cross-conductor write.* A conductor influences another **only by
`emit`ting an event** the other's own handler folds; it can never write another's cell. So the only thing
you can do to another conductor is emit to it, and the only thing you can read of it is a snapshot as-of
your pin. A conductor reading its **own** state is the sole synchronous case: it reads *and* writes its own
cell **synchronously**, so read-after-write *within* a handler sees the latest value (the live head, not
the pinned snapshot). Stated once: **only the owner reads-and-writes its own state synchronously; everyone
else only emits, and only snapshot-reads.**

---

## 4. Writes: single-owner commits (DERIVED)

A write is a conductor replacing **its own** cell, which commits a new version. There is no other kind of
write (§3): a conductor never writes another's cell — it emits. Because each cell has exactly one writer,
the commit is unconditional — append `(V+1, newValue)`, publish. No lock; the clock's atomic bump is the
linearization point.

**Deliberately deferred: multi-conductor atomic writes.** "Change A *and* B atomically" is a genuine
multi-row transaction — it reintroduces conflict detection, a serialization point, and abort/retry, losing
the single-owner *no-abort* property that makes the above cheap. It is a real software-transactional-memory
feature, not a free consequence of immutability, and it is **out of scope for the first realization.** When
wanted, it rides the same version clock but adds an optimistic-commit check (validate that the read set's
versions are unchanged at commit; else abort/retry) — and it stays **in-process only** (§8).

---

## 5. Feedback loops: the crown jewel (DERIVED, from James's RULED vision)

James's vision: feedback loops are **never a weakness to avoid — they are one of the system's strongest
features.** MVCC is what makes that true and safe. This is not novel physics; it is the model that
**timely dataflow / Naiad** (feedback made cheap by advancing a logical timestamp on the loop back-edge),
**synchronous dataflow** (Lustre/Esterel — feedback is legal iff a unit delay sits in the loop, and the
clock tick *is* that delay), and **BSP/Pregel** (supersteps; messages sent in step N are read in N+1;
iterative convergence) have each proven at scale. Pontif gets there natively.

A loop advances the version each time around: iteration N reads the snapshot iteration N committed, so
**N → N+1 is a genuine happens-before edge.**

> **The one rule that decides whether this works.** The version boundary is a **barrier
> (happens-before), NOT a delay.** "The re-emit usually takes a moment, and that moment is usually enough
> to avoid a race" builds a heisenbug generator — it makes races *rare*, which is the worst outcome
> (green tests, production corruption). A version edge eliminates races on versioned state **by
> construction**, regardless of wall-clock timing, load, or scheduler. Sell it — and implement it — as a
> clock, never as a pause. The pause being "usually immediate" is fine *because it is an uncontended
> barrier*, not because the pause does protective work.

**Pacing is self-driven (DERIVED).** A loop advances *its own* timestamp on the back-edge; its reads are
as-of its own current version. Parallel requests get their *own* snapshots and **must never pace the
loop** — the MVCC isolates concurrent work, it does not schedule your iteration. (Waiting on the *global*
clock instead would make a loop run faster when the system is busier and stall when it is idle — backwards,
and starvation-prone.)

**This falls straight out of the lane model.** A feedback back-edge that crosses a **mailbox** — enqueue,
then a later dequeue — *already* crosses a version boundary, because state commits in between. So:

> **Well-clocked = the back-edge goes through the queue.** A cross-lane (or self) `emit` that closes a
> cycle must be **enqueued** (crossing a version), never folded inline. The cut-3b "fold inline when the
> current thread already owns the lane" optimization is correct for *non-cyclic* emits, but a cyclic
> back-edge must yield to enqueue — the queue hop *is* the clock tick. A synchronous, same-handler,
> inline re-emit that loops is the causality violation (it never advances a version → unbounded recursion
> → the stack blows). Detecting which emits are back-edges is exactly cut-3c's graph (§6).

**Termination is decidable the BSP way (DERIVED):** a loop halts at a **fixpoint** — a version whose
re-run produces no state change — or at a declared iteration bound. This is the runtime companion to §6's
static check; the generator/unfold driver's existing "cap the steps, fail loudly rather than hang"
discipline (`docs/stream-war.md`) is the same fail-loud stance for the residual case.

---

## 6. "Well-clocked" supersedes "acyclic": cut-3c's successor (PROPOSED)

Cut 3c (`ConductorCycleCheck`) rejects *all* seated-conductor emit cycles, because in an unversioned world
a cycle is a non-terminating hang. That "no-go" is **gauge-relative** (cf.
[[impossibility-is-gauge-relative]]): it was frozen relative to a *stateless* model. Add the version
coordinate and it dissolves into a coherent transition.

The successor check moves the criterion from **acyclic** to **well-clocked**:

- An emit graph edge is **delayed** if it crosses a version boundary (a mailbox hop — §5).
- A cycle is **legal iff every cycle carries at least one delayed edge** (a register on the back-edge).
- An **instantaneous** cycle — one closed entirely by synchronous inline dispatch, no version advance —
  is the causality loop, and *that* is what the check rejects.

This is precisely **Lustre's causality analysis** (an instantaneous feedback loop is a compile error; a
`pre`/`fby`-delayed one is well-clocked), and it is strictly more expressive than acyclicity: cyclic hives
become first-class, and only the genuinely non-terminating shape is refused. `ConductorCycleCheck` keeps
its graph and its DFS; it changes its *verdict function* — reject a cycle only when no edge on it is
delayed — and it grows the runtime rule that a back-edge is enqueued, not inlined.

Cut-3c's blanket rejection is **interim, not a floor to keep** (RULED — write no code destined to be
phased out). It is *correct for the current runtime*, where every cycle is instantaneous — a self- or
cross-emit that closes a loop folds inline (cut 3b), so it genuinely hangs — and it rejects exactly the
programs that would. It is **replaced, verdict-only** (the graph and DFS are reused, not thrown away) in
lockstep with the runtime that makes well-clocked loops real (slice 3): the moment a back-edge can cross a
mailbox, the verdict becomes "reject only *instantaneous* cycles" and the blanket form is **deleted, not
retained as a fallback.** So nothing durable is written to be discarded — ~10 lines of verdict change
hands exactly when the feature that makes them wrong arrives, and never lingers as a parallel safety net.

---

## 7. Reclamation: the frontier (PROPOSED — the one genuinely hard part)

Immutability gives versions for free; knowing when a version is **dead** is the real engineering. A
loop that bumps versions quickly churns snapshots, and without reclamation the version chains grow without
bound.

The mechanism is a **frontier / low-water-mark**: the minimum version any live reader, in-flight handler,
or active loop could still request. Any chain entry older than the frontier is unreachable and can be
dropped (and its structurally-shared substructure GC'd normally). This is **timely dataflow's** central
complexity budget, and it should be **designed in from the start**, not discovered as unbounded memory
growth later.

Concretely (PROPOSED): track the set of *pinned versions* — each firing handler pins its `V₀`, each loop
pins its current iteration version; the frontier is their minimum (or the clock head when none are
pinned). Reclamation runs opportunistically as pins are released (a handler completes, a loop reaches
fixpoint). The pin set is small (bounded by concurrent handlers + active loops) and lives beside the lane
bookkeeping.

---

## 8. The consistency ladder: honest guarantees per tier (PROPOSED)

The tier matrix (`docs/orchestration.md`) already carries an *honest failure* guarantee per placement. MVCC
adds an *honest consistency* guarantee per placement — and the two compose rather than fighting:

| Tier | Consistency guarantee |
| --- | --- |
| **main lane** | serializable / snapshot — one clock, one heap |
| **same-process thread** | serializable / snapshot — same clock, shared heap, versions by reference |
| **separate process** | **causal / eventual** — no global clock; a boundary the types already admit can fail |
| **cross-machine** | **causal / eventual** — same |

**The bright line (RULED intent):** in-process snapshot isolation is cheap and sound — do it. **"Atomic
across all runtimes" is out of the core promise.** A global version clock across nodes is distributed
consensus (Spanner's TrueTime, Calvin's deterministic sequencer, or a 2PC coordinator) — latency-bound,
partition-sensitive, and it reintroduces exactly the synchronous cross-boundary coupling the placement
model refuses. So consistency degrades *honestly* with distance, the same way reachability does: an
in-process transaction is serializable; a cross-node one is causal, and the **types say so**. This keeps
the model composable instead of quietly rebuilding a distributed database underneath it.

---

## 9. Managed vs unmanaged, restated (RULED)

To make §0's boundary operational:

- **Managed** = every `RecordValue` in a versioned cell. Fully covered: snapshot reads, single-owner
  commits, well-clocked loops, frontier reclamation.
- **Unmanaged** = every effect that leaves Pontif's heap — network calls, third-party services, file and
  socket I/O, the actual rendered frame. **Not transactional.** These are *ordered against* version
  boundaries (an effect emitted at commit `V` happens after everything ≤ `V`), and — a feature — the
  display consuming one version per frame gives clean frames for free. But they are **never rolled back**,
  and **no API will claim to make them atomic.**

---

## 10. One query surface, native (DERIVED)

The store must **not** grow a second, foreign query language. Pontif already has the beginnings of a
native one: **stream-queries** (`&s:[type-sort]` is a first-class `Query`; terminal ops pick cardinality —
`docs/stream-queries.md`) and the **KEYED disposition** model (dict/array/set/graph as dispositions of one
`Iterate`; junctions; generated `equals`/`hash` per struct — `docs/keyed.md`). That is already a query
language *and* an indexing model, in the language.

So: the MVCC store **backs** `Query` / KEYED; a cross-conductor read (§3) is a `Query` evaluated over a
snapshot; the long-discussed "type-refinements-as-queries" compiles to that same surface. A refinement is
a predicate; a predicate over a versioned relation is a query; the index that accelerates it is a KEYED
disposition. Embedding SQLite would have created a *competing* surface — the native unification is the
reason to build custom.

---

## 11. Durability and the journal (PROPOSED)

The version chain is in-heap and dies on crash. That is fine for the concurrency model, but the design docs
already posit a **journal** (wire-format, for deliberate recovery — `docs/orchestration.md`). Observe:
**the committed version chain *is* the journal.** Time-travel ("what did the hive look like at `V`?") and
recovery are the same artifact.

If durable persistence is wanted, the honest role for a foreign engine appears here — and *only* here: an
embeddable **pure-Java MVCC** store (an MVStore-class B-tree, not SQLite's single-writer C engine — chosen
for GraalVM native-image friendliness) as a **sink for committed versions**, explicitly **off the hot
path**. The hot path stays native in-heap; the journal optionally spills to disk. This gives the borrowed
engine a real, non-competing job and keeps it out of the value model.

---

## 12. Prior art — existence proofs and cautionary tales

The *specific* fusion — an immutable-value language whose native value model is a versioned MVCC store with
logical-clock feedback — is fresh. "A language with a built-in database and a native API" is not, and the
precedents are gifts:

- **MUMPS/M** — a language whose runtime *is* a hierarchical database (globals), native API, transactions
  included; runs much of US healthcare (Epic). The existence proof, 50 years deep.
- **Erlang/OTP — ETS + Mnesia** — an in-memory term store in every runtime, plus a built-in transactional,
  distributed DBMS. Pontif's closest relative (shared actor lineage). Study it *and* its scars — Mnesia's
  partition/`inconsistent_database` folklore is §8's "don't chase distributed atomicity" lesson made real.
- **Datomic** (immutable-value DB, monotone `t`, as-of reads) and **Smalltalk/GemStone** (persistent
  transactional object memory) — the immutable-substrate realization we are converging on independently.
- **Timely dataflow / Naiad, Lustre/Esterel, Pregel** — the feedback-as-first-class lineage §5 builds on.

What we take: immutable versions, as-of snapshots, single-writer simplicity, logical-clock feedback. What
we avoid: Mnesia-style cross-node strong consistency; a foreign relational surface competing with the
native `Query`.

---

## 13. Build order (PROPOSED)

Slice to de-risk; each cut lands green, additive, and behavior-preserving until a program opts in
(exactly the cut-2/3 discipline).

1. **Versioned cell + snapshot read (in-process).** Turn each `conductorState` entry into a version chain;
   add the `AtomicLong` clock; a handler pins `V₀` at fire; commit appends `(V+1, newValue)` and bumps the
   clock. **Own-state reads stay the live head** (§3 — read-after-write within a handler is unchanged), so
   there is *no observable change*: the chain is built, but its only reader so far is the head, which equals
   today's `conductorState.get`. The as-of-`V₀` snapshot-read path exists but has **no cross-conductor
   consumer yet** (that is slice 2), so it is exercised only by a direct unit test (commit a few versions,
   read as-of an old one, prove isolation). Prove the whole existing suite byte-for-byte unchanged. A few
   hundred lines; de-risks everything.
2. **Cross-conductor read queries (§3).** Expose pure snapshot queries; the read contract; tests for
   read-skew consistency (a reader sees one coherent version while another conductor commits).
3. **Well-clocked cycles (§5–6).** Enqueue cyclic back-edges; upgrade `ConductorCycleCheck`'s verdict from
   acyclic to well-clocked; a version-paced feedback loop that converges to a fixpoint — the first program
   that *uses* a cycle as a feature.
4. **Frontier reclamation (§7).** Pin-set + low-water-mark + opportunistic drop; a churning-loop test that
   stays bounded in memory.
5. **(Later / optional)** Multi-conductor atomic writes (optimistic-commit, in-process only); the durable
   journal sink (§11); the per-tier consistency types (§8).

---

## Non-goals (RULED / DERIVED)

- Atomicity over unmanaged side-effects (§9). **Never.**
- A global version clock across process/host boundaries (§8). Consistency degrades honestly with distance.
- Embedding a foreign database on the hot path (§0). Custom, native, tailor-fit. A pure-Java MVCC engine is
  admissible *only* as an off-hot-path durability sink (§11).
- A second, SQL-shaped query language competing with native `Query`/KEYED (§10).
