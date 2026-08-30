#!/usr/bin/env python3
"""Read-only evidence capture. Never stops/restarts VPN, clears logs, or signals Go.

Run before manual recovery. SELinux/run-as failures are retained, not interpreted
as absent sockets. Engine snapshots must also be exported from app Diagnostics;
starting instrumentation here would destroy the session being investigated.
"""

import argparse
import concurrent.futures
import datetime
import json
import os
import pathlib
import re
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", default="com.tcptun.client.debug")
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+", args.package):
        parser.error("invalid package name")
    os.umask(0o077)
    args.output.mkdir(parents=True, exist_ok=False)
    adb = ["adb", "-s", args.serial]
    process = subprocess.run(adb + ["shell", "pidof", args.package], capture_output=True, text=True, timeout=10)
    pid = process.stdout.strip().split()
    if process.returncode != 0 or not pid or not pid[0].isdigit():
        parser.error("app process is not running; no recovery was attempted")
    pid = pid[0]
    app_proc = ["shell", "run-as", args.package]
    requests = {
        "service": ["shell", "dumpsys", "activity", "services", args.package],
        "connectivity": ["shell", "dumpsys", "connectivity"],
        "vpn": ["shell", "dumpsys", "vpn"],
        "interfaces": ["shell", "ip", "addr"],
        "routes": ["shell", "ip", "route", "show", "table", "all"],
        "fds": app_proc + ["ls", "-l", f"/proc/{pid}/fd"],
        "threads": app_proc + ["ls", "-1", f"/proc/{pid}/task"],
        "process": app_proc + ["cat", f"/proc/{pid}/status"],
        "memory": ["shell", "dumpsys", "meminfo", args.package],
        # Only the app's already-redacted log stream; no global credential logs.
        "app-log": ["logcat", "-d", "--pid", pid, "-v", "threadtime", "-s", "TcpTun:I", "*:S"],
    }
    for table in ("tcp", "tcp6", "udp", "udp6"):
        requests[table] = app_proc + ["cat", f"/proc/{pid}/net/{table}"]

    def capture(item):
        name, command = item
        started = datetime.datetime.now(datetime.timezone.utc).isoformat()
        try:
            result = subprocess.run(adb + command, capture_output=True, text=True, timeout=10)
            args.output.joinpath(name + ".txt").write_text(result.stdout + result.stderr)
            return name, {"started": started, "exit_code": result.returncode}
        except subprocess.TimeoutExpired:
            return name, {"started": started, "error": "capture_timeout"}

    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        results = dict(pool.map(capture, requests.items()))
    manifest = {
        "pid": pid, "package": args.package, "captures": results,
        "limitations": [
            "Not an atomic snapshot; correlate per-capture timestamps and runtime IDs.",
            "Export Engine status/statusJSON/outbounds from app Diagnostics separately.",
            "Collect a supported debugger thread dump separately; do not send SIGQUIT to Go.",
            "Device interface success does not prove remote LAN/hotspot reachability.",
            "Permission denied is unknown evidence, not a missing listener.",
        ],
    }
    args.output.joinpath("manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Evidence saved to {args.output}; no recovery or lifecycle mutation performed.")


if __name__ == "__main__":
    main()
