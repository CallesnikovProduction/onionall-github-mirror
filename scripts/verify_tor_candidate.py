"""Public compatibility entrypoint for security/verify_tor_candidate.py."""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
if __name__ == "__main__":
    import runpy
    runpy.run_module("security.verify_tor_candidate", run_name="__main__")
else:
    from security import verify_tor_candidate as implementation
    sys.modules[__name__] = implementation
