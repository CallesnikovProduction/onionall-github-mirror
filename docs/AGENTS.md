# Architecture contracts and engineering evidence

Inherited repository and ancestor AGENTS.md constraints remain in force.

## MODULE PURPOSE

Project contracts, design decisions and separate execution/security evidence.

## RESPONSIBILITIES

Keep architecture instructions, user documentation, proposed designs and measured execution evidence distinct.

## IMPLEMENTED ARCHITECTURE

adr/ records decisions; engineering/ and recovery/ hold ledgers; security/ holds review evidence. Top-level documents describe CLI, archive, admission and scenario contracts. audits/ may contain owner-supplied independent reviews, not implementation authority.

## KEY COMPONENTS

Root README.md; adr/0004-canonical-product-topology.md; archive-mvp.md; m41-durable-admission.md; engineering/security-execution-ledger.md; security/SECURITY-REVIEW.md.

## DATA FLOW

Source/controlled experiment -> explicitly scoped evidence -> ledger/review -> approved contract change -> implementation. A proposal or independent finding is not proof by itself.

## DEPENDENCIES

Actual source, regression tests, explicit owner decisions and existing contracts. User-supplied untracked audits/specifications must not be staged automatically.

## INVARIANTS

Never turn historical evidence into a fresh successful result or mix product defects with confirmed security impact. No secrets/raw runtime logs in tracked documents. AGENTS.md must not become another report or ledger.

## CHANGE GUIDELINES

Check links, affected symbols, authority boundaries and current code before documenting. Keep existing report locations; do not duplicate execution journals in module instructions. Preserve independent audit documents unless expressly asked to edit them.

## RELATED MODULES

[Security review](security/AGENTS.md); [Product topology](adr/0004-canonical-product-topology.md); [Security ledger](engineering/security-execution-ledger.md).

## KNOWN LIMITATIONS

Specs/ADRs may describe proposed contracts: check decision status and actual code before claiming implementation. A local verification report is not production acceptance.
