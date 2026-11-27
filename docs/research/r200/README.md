# R200 research objectives

[Objectives](objectives.json) contains 200 technical questions in 20 categories.
Each category has one objective for documentation, public forums, source code,
projects, comparisons, implementation, performance, compatibility, published
literature and exact quotations. [Rubric](rubric.json) records the evidence
needed to assess each objective by its stable ID.

The batch uses the current documentation and public-forum intake vocabulary.
The rubric carries distinctions such as literature, source code and measured
comparisons. Every objective requests `approved-sources` and `DISCOVER_ONLY`.

Validate with the [packaged CLI](../../m6-research.md#batch-manifest-validation)
from the checkout root with JDK 25 configured.

```sh
target/onionall/bin/onionall --json research batch validate docs/research/r200/objectives.json
```

Stage A passed on the Windows package. All 200 IDs were returned with exit 0.
A copy with a duplicate objective ID returned exit 2 and
`DUPLICATE_OBJECTIVE_ID`. Both executions reported zero network requests,
zero persistent writes, `source_approval=NOT_GRANTED` and
`execution=NOT_STARTED`. The manifest and rubric contain no findings or approved
origins. Real execution starts with a small approved source pilot and explicit
request budgets for its selected objectives.

Validated SHA-256 values

- Objectives `e1dae6f60e0791b062aad2281441f3b962a74cc0ef12b8fb142ca719fcd07e4d`
- Rubric `3e7c6287351567017bd119c4bf596e2e90e3c81f687dcf78b4cb88441e1c5484`
