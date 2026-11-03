"""Compatibility entrypoint/import for tor_acceptance_runner.py."""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
if __name__ == "__main__":
    import runpy
    runpy.run_module("tor_acceptance_runner", run_name="__main__")
else:
    import tor_acceptance_runner as implementation
    sys.modules[__name__] = implementation
