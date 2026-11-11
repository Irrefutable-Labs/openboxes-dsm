#!/usr/bin/env python3
"""Start the demo's services, each in a session of its own so they outlive whatever started them:
up.py network | a | b | seed. JAVA_HOME, DSM_CHECKOUT and DSM_BIN come from the environment. Logs go to dsm/demo/logs."""
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
LOGS = os.path.join(HERE, "logs")
os.makedirs(LOGS, exist_ok=True)
COMMANDS = {
    "network": ["bash", os.path.join(HERE, "network.sh")],
    "a": ["bash", os.path.join(HERE, "openboxes.sh"), "a"],
    "b": ["bash", os.path.join(HERE, "openboxes.sh"), "b"],
    "seed": [sys.executable, "-u", os.path.join(HERE, "seed.py")],
}
for name in sys.argv[1:]:
    log = open(os.path.join(LOGS, name + ".log"), "wb")
    process = subprocess.Popen(COMMANDS[name], stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                               start_new_session=True, close_fds=True)
    print(name, "started", process.pid)
