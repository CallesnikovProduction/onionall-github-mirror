"""Derive test topology without editing or duplicating the verified M0 graph."""
from pathlib import Path
import argparse


def topology(source):
    if source.count('name: "crawler"') != 1 or source.count("\nspouts:\n") != 1:
        raise ValueError("Unexpected base Flux shape; inspect before deriving M1")
    override = ('    - resource: false\n      file: "test/m1/crawler-conf.yaml"\n'
                '      override: true\n\nspouts:\n')
    return source.replace('name: "crawler"', 'name: "crawler-m1"', 1).replace("spouts:\n", override, 1)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("run_dir", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    destination = args.run_dir.resolve()
    if not destination.is_relative_to(root / ".runtime" / "m1"):
        raise SystemExit("Output must stay in .runtime/m1")
    destination.mkdir(parents=True, exist_ok=True)
    (destination / "topology.flux").write_text(topology((root / "crawler.flux").read_text()), encoding="utf-8")
