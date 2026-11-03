"""Compatibility entrypoint/import for recover_tor_gateway.py."""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
if __name__ == "__main__":
    import runpy
    runpy.run_module("recover_tor_gateway", run_name="__main__")
else:
    import recover_tor_gateway as implementation
    sys.modules[__name__] = implementation
