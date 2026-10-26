#!/usr/bin/env python3
"""Manage this checkout's local API and userscript installer without touching other services."""

import argparse
import json
import os
from pathlib import Path
import secrets
import signal
import socket
import subprocess
import sys
import time
import urllib.request


ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / "runtime.local"
STATE = RUNTIME / "processes.json"


def running(pid, marker):
    # Verify ownership before treating a reused PID as one of our services.
    result = subprocess.run(["ps", "-p", str(pid), "-o", "command="], capture_output=True, text=True)
    return result.returncode == 0 and marker in result.stdout


def load_state():
    return json.loads(STATE.read_text()) if STATE.exists() else {}


def status():
    for name, item in load_state().items():
        print(f"{name}: {'running' if running(item['pid'], item['marker']) else 'stopped'} (PID {item['pid']})")


def stop():
    for name, item in load_state().items():
        if running(item["pid"], item["marker"]):
            os.kill(item["pid"], signal.SIGTERM)
            print(f"Stopping {name} (PID {item['pid']})")
    # Wait for graceful shutdown; never force-kill a service or stop PostgreSQL.
    for _ in range(40):
        if not any(running(item["pid"], item["marker"]) for item in load_state().values()):
            return
        time.sleep(0.25)
    raise SystemExit("Some services are still stopping; inspect runtime.local/logs.")


def start():
    state = load_state()
    if any(running(item["pid"], item["marker"]) for item in state.values()):
        raise SystemExit("Local services are already running; use status or stop first.")
    for port in (9100, 9101, 6768):
        with socket.socket() as probe:
            # Ignore closed connections in TIME_WAIT while still rejecting active listeners.
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                probe.bind(("127.0.0.1", port))
            except OSError:
                raise SystemExit(f"Port {port} is in use; existing services were left untouched.")
    config = RUNTIME / "application.properties"
    jar = ROOT / "ai-job-hunting-server/target/ai-job.jar"
    installer = RUNTIME / "www/ai-job-hunting.user.js"
    for required in (config, jar, installer):
        if not required.is_file():
            raise SystemExit(f"Missing deployment artifact: {required}")

    java_home = subprocess.check_output(["/usr/libexec/java_home", "-v", "17"], text=True).strip()
    env = os.environ.copy()
    # An unset admin password becomes a fresh random value, never printed or written by this launcher.
    env.setdefault("LOCAL_ADMIN_PASSWORD", secrets.token_urlsafe(32))
    commands = {
        "api": ([str(Path(java_home) / "bin/java"), f"-Djava.io.tmpdir={RUNTIME / 'tmp'}",
                 "-jar", str(jar), f"--spring.config.location={config}"], str(jar)),
        "installer": ([sys.executable, "-m", "http.server", "9101", "--bind", "127.0.0.1",
                       "--directory", str(RUNTIME / "www")], str(RUNTIME / "www")),
    }
    state = {}
    for name, (command, marker) in commands.items():
        with (RUNTIME / "logs" / f"{name}.log").open("ab") as log:
            process = subprocess.Popen(command, cwd=RUNTIME, env=env, stdin=subprocess.DEVNULL,
                                       stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        state[name] = {"pid": process.pid, "marker": marker}
        STATE.write_text(json.dumps(state, indent=2))

    for _ in range(45):
        # Fail immediately when Java exits instead of waiting for a health-check timeout.
        if not running(state["api"]["pid"], state["api"]["marker"]):
            raise SystemExit("API exited during startup; inspect runtime.local/logs/api.log.")
        try:
            with urllib.request.urlopen("http://127.0.0.1:9100/actuator/health", timeout=1) as response:
                if json.load(response).get("status") == "UP":
                    status()
                    print("API: http://127.0.0.1:9100")
                    print("Installer: http://127.0.0.1:9101")
                    return
        except (OSError, ValueError):
            pass
        time.sleep(1)
    raise SystemExit("API did not become healthy; inspect runtime.local/logs/api.log before restarting.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("start", "stop", "status"))
    arguments = parser.parse_args()
    {"start": start, "stop": stop, "status": status}[arguments.action]()
