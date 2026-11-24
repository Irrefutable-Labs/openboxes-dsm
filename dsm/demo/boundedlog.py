#!/usr/bin/env python3
"""A host's log that cannot fill a disk: boundedlog.py PATH reads stdin and keeps the newest lines in PATH and PATH.1,
at most MAX_BYTES each. A dsm-app-host once wrote 155 GB to a plain file and stopped the machine, so no host here writes
to one. A runaway is recorded in PATH.runaway with the lines that were repeating, so its cause can be read afterwards.
"""
import collections
import os
import re
import sys
import time

MAX_BYTES = 50 * 1024 * 1024
RUNAWAY_BYTES_PER_MINUTE = 20 * 1024 * 1024
path = sys.argv[1]
out = open(path, "ab")
window_start, window_bytes = time.monotonic(), 0
seen = collections.Counter()

for line in sys.stdin.buffer:
    out.write(line)
    out.flush()
    window_bytes += len(line)
    seen[re.sub(rb"[0-9A-Z]{8,}|[0-9]+", b"#", line[22:112])] += 1
    if time.monotonic() - window_start >= 60:
        if window_bytes > RUNAWAY_BYTES_PER_MINUTE:
            with open(path + ".runaway", "ab") as runaway:
                runaway.write(("%s: %d bytes in a minute, most repeated lines:\n" % (time.ctime(), window_bytes)).encode())
                for text, count in seen.most_common(8):
                    runaway.write(b"%d x %s\n" % (count, text.rstrip()))
        window_start, window_bytes = time.monotonic(), 0
        seen.clear()
    if out.tell() >= MAX_BYTES:
        out.close()
        os.replace(path, path + ".1")
        out = open(path, "ab")
