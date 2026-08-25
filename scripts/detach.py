#!/usr/bin/env python3
"""Detach a command into its own session so it survives the caller's process-group kill.
Usage: detach.py <logfile> <cmd...>  — prints child pid and exits immediately."""
import os, subprocess, sys

log, cmd = sys.argv[1], sys.argv[2:]
pid = os.fork()
if pid:
    print(pid)
    sys.exit(0)
os.setsid()
# sever all inherited fds so the caller's pipe closes immediately
devnull = os.open(os.devnull, os.O_RDWR)
os.dup2(devnull, 0)
fh = open(log, "w")
os.dup2(fh.fileno(), 1)
os.dup2(fh.fileno(), 2)
subprocess.run(cmd, stdout=fh, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
os._exit(0)
