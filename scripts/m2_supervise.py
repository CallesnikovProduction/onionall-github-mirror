"""Compatibility entrypoint/import for supervise_local_crawl.py."""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
if __name__ == "__main__":
    import runpy
    runpy.run_module("supervise_local_crawl", run_name="__main__")
else:
    import supervise_local_crawl as implementation
    sys.modules[__name__] = implementation
