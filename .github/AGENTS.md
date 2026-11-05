# CI verification

Inherited repository and ancestor AGENTS.md constraints remain in force.

## MODULE PURPOSE

Repository verification and paired build artifact provenance in GitHub Actions.

## RESPONSIBILITIES

Keep reproducible pinned inputs, least-privilege workflow permissions and clear local-versus-runtime evidence boundaries.

## IMPLEMENTED ARCHITECTURE

workflows/verify.yml sets Java 25/Python, recovers a digest/hash-pinned Frontier base, builds the app/thin CLI/candidate server and runs verify.py. Internal-only product end-to-end requires explicit workflow dispatch input.

## KEY COMPONENTS

`workflows/verify.yml`: immutable action revisions, contents:read, checkout without persisted credentials and sanitized artifact collection.

## DATA FLOW

Selected revision -> pinned dependencies -> paired artifacts/hash manifest -> local checks -> optional isolated generated-product fixture -> sanitized diagnostics.

## DEPENDENCIES

Maven wrapper, Frontier build, scripts/runners/verify.py, scripts/patches and test/admission.

## INVARIANTS

Never add real onion crawling to default CI or expose tokens/raw credential-bearing logs. Preserve image/action/hash pins, paired server/client builds and negative-check failure propagation.

## CHANGE GUIDELINES

Validate workflow syntax and inspect shell/JSON/secret handling with affected script tests. Do not grant write permissions or add publication/deployment as an incidental verification change.

## RELATED MODULES

[Verification](../scripts/runners/AGENTS.md); [Product fixture](../test/admission/AGENTS.md); [Paired server](../frontier-server/AGENTS.md).

## KNOWN LIMITATIONS

Assembly and CI exit status are not live deployment or real Tor acceptance. Optional runtime jobs require available Docker/Linux prerequisites.
