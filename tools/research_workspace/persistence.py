"""Small POSIX persistence primitives shared by research evidence writers."""
import json
import os
from pathlib import Path


def canonical_json(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()


def sync_directory(directory):
    descriptor = os.open(directory, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def durable_directory(directory):
    """Persist each parent entry, including directories left by an interrupted mkdir."""
    directory = Path(directory).resolve()
    directory.mkdir(parents=True, exist_ok=True)
    for parent in reversed(directory.parents):
        sync_directory(parent)
    sync_directory(directory)
