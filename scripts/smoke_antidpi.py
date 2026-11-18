#!/usr/bin/env python3
"""Verify pinned ByeDPI serves SOCKS5 with the Android argument presets."""
import pathlib
import socket
import subprocess
import time

ROOT = pathlib.Path(__file__).resolve().parent.parent
EXEC = ROOT / "third_party" / "byedpi" / "ciadpi"
BASE = ["--ip", "127.0.0.1", "--port", "19080", "--max-conn", "1024"]
PRESETS = {
    "TCP Split": ["-Kt,h,u,i", "-s1", "-An"],
    "Disorder": ["-Kt,h,u,i", "-d1", "-An"],
    "TLS Record": ["-Kt,h", "-r3+s", "-An"],
    "Split + Disorder": ["-Kt,h", "-s1+s", "-d3+s", "-An"],
}

def check(name, flags):
    process = subprocess.Popen(
        [str(EXEC), *BASE, *flags],
        cwd=EXEC.parent,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
    )
    ok = False
    try:
        for _ in range(40):
            if process.poll() is not None:
                break
            try:
                with socket.create_connection(("127.0.0.1", 19080), 0.3) as sock:
                    sock.settimeout(0.5)
                    sock.sendall(b"\x05\x01\x00")
                    if sock.recv(2) == b"\x05\x00":
                        ok = True
                        break
            except (OSError, socket.timeout):
                time.sleep(0.15)
    finally:
        process.terminate()
        try:
            _, errors = process.communicate(timeout=3)
        except subprocess.TimeoutExpired:
            process.kill()
            _, errors = process.communicate(timeout=3)
    if not ok:
        raise AssertionError(
            f"{name} did not start SOCKS5 with {flags}: "
            + errors.decode("utf-8", "replace")[-2000:]
        )
    print(f"PASS: {name} SOCKS5 handshake")

if __name__ == "__main__":
    for name, flags in PRESETS.items():
        check(name, flags)
