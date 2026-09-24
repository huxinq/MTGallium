"""Conservative defaults for pools of research JVMs (not Session or CLI)."""
import os
from pathlib import Path

JAVA_OPTIONS = ('-Xms64m', '-Xmx768m', '-XX:+UseParallelGC', '-XX:ActiveProcessorCount=2')
WORKER_BYTES = 1536 * 1024**2


def available_memory() -> int:
    """Linux available RAM, bounded by the current cgroup v2 allowance."""
    try:
        fields = dict(line.split(':', 1) for line in Path('/proc/meminfo').read_text().splitlines())
        available = int(fields['MemAvailable'].split()[0]) * 1024
    except (OSError, KeyError, ValueError):
        # Unknown platforms get one worker rather than a guessed large pool.
        return WORKER_BYTES
    try:
        group = next(line[3:] for line in Path('/proc/self/cgroup').read_text().splitlines()
                     if line.startswith('0::'))
        directory = Path('/sys/fs/cgroup') / group.lstrip('/')
        root = Path('/sys/fs/cgroup')
        while directory == root or root in directory.parents:
            limit = (directory / 'memory.max').read_text().strip()
            if limit != 'max':
                available = min(available, max(0, int(limit) - int((directory / 'memory.current').read_text())))
            if directory == root:
                break
            directory = directory.parent
    except (OSError, ValueError, StopIteration):
        pass
    return available


def default_workers() -> int:
    memory_workers = available_memory() // WORKER_BYTES
    if memory_workers < 1:
        raise RuntimeError('Less than 1.5 GiB available for a research JVM; wait or explicitly set threads')
    return min(12, max(1, (os.cpu_count() or 1) - 2), memory_workers)
