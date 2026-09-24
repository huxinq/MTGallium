"""Serialize Linux Gradle builds without passing a lock fd to its daemon."""
import fcntl
import os
from pathlib import Path
import subprocess
import sys

LOCK = Path('/tmp/mtgallium-science-gradle.lock')


def ancestor_holds_lock(stat):
    ancestors = set()
    pid = os.getppid()
    while pid > 0:
        ancestors.add(pid)
        try:
            fields = Path(f'/proc/{pid}/stat').read_text().rsplit(')', 1)[1].split()
            pid = int(fields[1])
        except (OSError, ValueError):
            break
    for line in Path('/proc/locks').read_text().splitlines():
        fields = line.split()
        if len(fields) < 6 or fields[1:4] != ['FLOCK', 'ADVISORY', 'WRITE']:
            continue
        major, minor, inode = fields[5].split(':')
        if (int(fields[4]) in ancestors and int(inode) == stat.st_ino
                and int(major, 16) == os.major(stat.st_dev)
                and int(minor, 16) == os.minor(stat.st_dev)):
            return True
    return False


def main():
    with LOCK.open('a') as lock:
        if not ancestor_holds_lock(os.fstat(lock.fileno())):
            fcntl.flock(lock, fcntl.LOCK_EX)
        # close_fds also drops any descriptor inherited from an outer flock.
        return subprocess.call(sys.argv[1:], close_fds=True)


if __name__ == '__main__':
    sys.exit(main())
