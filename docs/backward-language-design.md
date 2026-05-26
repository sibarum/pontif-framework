# Pontif: Backward Language Design

#. Start by implementing language features in the execution AST (Truffle)
#. Then extend the IR with those new features, with slightly different semantics to simplify or abstract features as desired.
#. Lower the IR down to the reference language - doing the absolute minimum to gain support for the feature.
#. Finally, use the reference language output to guide design of the primary language (sometimes this is called the "alternate language" or "alt language").

It's ok to hypothesize about language features ahead of time, but none of this should be load-bearing or contractual.
Here's why:

* This is a highly experimental language. Simplicity of runtime is paramount.
* The only complexity should be in the complex language features: type narrowing, multi-dispatch, etc. Not in kitbashing features.
* The final parser->IR->AST should be a clean flow.
* The language syntax should never fight with the runtime implementation. They should fit like a glove.
* The primary language should be thought of as one big syntactic sugar for the reference language.

