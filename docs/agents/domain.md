# Domain Docs

Engineering skills should consume this repository's domain documentation as follows.

## Before exploring, read these

- **`GLOSSARY.md`** at the repository root, if it exists.
- **`docs/adr/`**: read ADRs that touch the area being changed, if they exist.

If these files do not exist, proceed silently. The `/domain-modeling` skill creates them lazily when terms or decisions are resolved.

## File structure

This is a single-context repository:

```
/
├── GLOSSARY.md
├── docs/adr/
└── src/
```

## Use the glossary's vocabulary

When output names a domain concept, use the term as defined in `GLOSSARY.md`. If the concept is not defined yet, note the gap for `/domain-modeling`.

## Flag ADR conflicts

If output contradicts an existing ADR, surface it explicitly rather than silently overriding it.
