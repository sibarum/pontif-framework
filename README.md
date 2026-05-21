# Pontif Framework

Pontif Framework is a high-level abstraction for the Truffle framework.
The goal is to provide a rich toolset of AST feature builders.
This framework is intended to make implementing an AST as simple as
filling a shopping cart.
BYO-parser.

To be clear: These are builders for AST definitions and AST instantiation.
This leads to immediate concerns: Does this require source code generation? Then we need a maven plugin.
Is it possible to define a new AST at runtime? Or must it be defined at compile time?

## Builders for high-level systems:

Scripting

- modules and namespaces?
- imports and exports?
- flow and branching control
- Loops, streams, optionals, promises
- Exception handling

Utilities

- Arrays, Lists, Sets, Maps
- Tensors
- Primitive data types

Type System Builder

- Inheritance?
- Prototypes?
- Mixins?
- Generics?
- Strict typing?

Multi-Dispatch

- Similar to Julia's multi-dispatch
- Full utilization of Truffle optimizations for dispatch

Proof Assist

- See proof-language-concept.md
- See simple-proof-example.md

## WIP - needs scoping