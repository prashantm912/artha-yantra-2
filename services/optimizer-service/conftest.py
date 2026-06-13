"""Put the service root on sys.path so ``app`` and ``tests`` import in pytest."""

import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent))
