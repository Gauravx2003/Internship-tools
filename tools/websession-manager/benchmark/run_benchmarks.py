#!/usr/bin/env python3
"""
WebSession Engine Benchmark Suite
Compares 3 Architectures:
  - Approach 1: Single Go Process (N = 1000)
  - Approach 2: Process-per-Session (N = 1)
  - Approach 3: Bounded Multiplexed (N = 5)

Collects exact OS metrics:
  - Process count
  - Memory consumption (RSS in MB per process and total)
  - Open File Descriptors (FDs)
  - Process startup time
  - SSH connection establishment latency
  - Session creation throughput (sessions/sec)
  - Terminal echo latency (p50, p95, p99)
"""

import os
import sys
import time
import json
import socket
import select
import subprocess
import threading
from typing import List, Dict, Any, Tuple

ENGINE_BIN = os.path.abspath("backend/session-engine/bin/session-engine")
MOCK_SSHD_BIN = os.path.abspath("benchmark/mockssh/mock-sshd")
SSH_PORT = 2222

def read_proc_rss_mb(pid: int) -> float:
    """Reads VmRSS from /proc/[pid]/status in MB."""
    try:
        with open(f"/proc/{pid}/status", "r") as f:
            for line in f:
                if line.startswith("VmRSS:"):
                    parts = line.split()
                    kb = int(parts[1])
                    return kb / 1024.0
    except Exception:
        pass
    return 0.0

def read_proc_fds(pid: int) -> int:
    """Counts open file descriptors in /proc/[pid]/fd."""
    try:
        return len(os.listdir(f"/proc/{pid}/fd"))
    except Exception:
        return 0

class ProcessSessionEngine:
    """Manages a single Go engine process communicating over stdin/stdout pipes."""
    def __init__(self, max_sessions: int):
        self.max_sessions = max_sessions
        t0 = time.perf_counter()
        self.proc = subprocess.Popen(
            [ENGINE_BIN, "--stdio", f"--max-sessions={max_sessions}"],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1
        )
        self.startup_latency_ms = (time.perf_counter() - t0) * 1000.0
        self.sessions = set()
        self.lock = threading.Lock()
        self.pending_replies = {}
        self.reader_running = True
        self.reader_thread = threading.Thread(target=self._stdout_reader, daemon=True)
        self.reader_thread.start()

    def _stdout_reader(self):
        while self.reader_running and self.proc.poll() is None:
            line = self.proc.stdout.readline()
            if not line:
                break
            line = line.strip()
            if not line:
                continue
            try:
                env = json.loads(line)
                sess_id = env.get("sessionId") or env.get("session.id")
                with self.lock:
                    if sess_id in self.pending_replies:
                        self.pending_replies[sess_id].append(env)
            except Exception:
                pass

    def send_line(self, envelope: dict):
        with self.lock:
            line = json.dumps(envelope) + "\n"
            self.proc.stdin.write(line)
            self.proc.stdin.flush()

    def wait_for_envelope(self, sess_id: str, env_type: str, timeout: float = 3.0) -> dict:
        deadline = time.time() + timeout
        while time.time() < deadline:
            with self.lock:
                queue = self.pending_replies.get(sess_id, [])
                for i, env in enumerate(queue):
                    if env.get("type") == env_type:
                        return queue.pop(i)
            time.sleep(0.005)
        return None

    def close(self):
        self.reader_running = False
        try:
            self.proc.stdin.close()
        except Exception:
            pass
        try:
            self.proc.terminate()
            self.proc.wait(timeout=1.0)
        except Exception:
            self.proc.kill()


class ArchitectureController:
    """Manages session dispatching according to the specified architecture."""
    def __init__(self, mode: str, max_sessions_per_proc: int):
        self.mode = mode
        self.max_sessions_per_proc = max_sessions_per_proc
        self.processes: List[ProcessSessionEngine] = []
        self.session_to_proc: Dict[str, ProcessSessionEngine] = {}
        self.lock = threading.Lock()

    def open_session(self, sess_id: str) -> Tuple[ProcessSessionEngine, float]:
        with self.lock:
            # Clean up dead processes
            self.processes = [p for p in self.processes if p.proc.poll() is None]

            target_proc = None
            if self.mode == "single":
                if not self.processes:
                    self.processes.append(ProcessSessionEngine(1000))
                target_proc = self.processes[0]
            elif self.mode == "per_session":
                target_proc = ProcessSessionEngine(1)
                self.processes.append(target_proc)
            elif self.mode == "bounded":
                for p in self.processes:
                    if len(p.sessions) < self.max_sessions_per_proc:
                        target_proc = p
                        break
                if target_proc is None:
                    target_proc = ProcessSessionEngine(self.max_sessions_per_proc)
                    self.processes.append(target_proc)

            target_proc.sessions.add(sess_id)
            target_proc.pending_replies[sess_id] = []
            self.session_to_proc[sess_id] = target_proc

        t0 = time.perf_counter()
        target_proc.send_line({
            "type": "open",
            "sessionId": sess_id,
            "payload": {
                "host": "127.0.0.1",
                "port": SSH_PORT,
                "username": "alice",
                "password": "benchpass",
                "authType": "password"
            }
        })
        resp = target_proc.wait_for_envelope(sess_id, "state", timeout=4.0)
        connect_latency_ms = (time.perf_counter() - t0) * 1000.0

        if not resp or resp.get("payload", {}).get("state") != "connected":
            raise RuntimeError(f"Session {sess_id} failed to connect: {resp}")

        return target_proc, connect_latency_ms

    def measure_echo_latency(self, sess_id: str) -> float:
        proc = self.session_to_proc.get(sess_id)
        if not proc or proc.proc.poll() is not None:
            return 0.0
        payload = f"echo_{sess_id}_{time.time_ns()}\n"
        t0 = time.perf_counter()
        try:
            proc.send_line({
                "type": "input",
                "sessionId": sess_id,
                "payload": {"data": payload}
            })
            resp = proc.wait_for_envelope(sess_id, "output", timeout=2.0)
            lat_ms = (time.perf_counter() - t0) * 1000.0
            return lat_ms
        except Exception:
            return 0.0

    def close_session(self, sess_id: str):
        proc = self.session_to_proc.get(sess_id)
        if proc and proc.proc.poll() is None:
            try:
                proc.send_line({"type": "close", "sessionId": sess_id})
                proc.wait_for_envelope(sess_id, "closed", timeout=2.0)
            except Exception:
                pass
            with self.lock:
                proc.sessions.discard(sess_id)
                self.session_to_proc.pop(sess_id, None)

    def collect_os_metrics(self) -> Dict[str, Any]:
        pids = [p.proc.pid for p in self.processes if p.proc.poll() is None]
        memories = [read_proc_rss_mb(pid) for pid in pids]
        fds = [read_proc_fds(pid) for pid in pids]
        startup_times = [p.startup_latency_ms for p in self.processes]

        return {
            "num_processes": len(pids),
            "total_rss_mb": round(sum(memories), 2),
            "avg_rss_per_proc_mb": round(sum(memories) / len(memories), 2) if memories else 0.0,
            "min_rss_mb": round(min(memories), 2) if memories else 0.0,
            "max_rss_mb": round(max(memories), 2) if memories else 0.0,
            "total_open_fds": sum(fds),
            "avg_fds_per_proc": round(sum(fds) / len(fds), 1) if fds else 0.0,
            "avg_startup_time_ms": round(sum(startup_times) / len(startup_times), 2) if startup_times else 0.0
        }

    def close_all(self):
        for p in self.processes:
            p.close()
        self.processes.clear()
        self.session_to_proc.clear()


def percentile(data: List[float], p: float) -> float:
    if not data:
        return 0.0
    sorted_d = sorted(data)
    idx = int(len(sorted_d) * (p / 100.0))
    idx = min(idx, len(sorted_d) - 1)
    return round(sorted_d[idx], 2)


def run_benchmark_for_mode(mode: str, num_sessions: int, max_per_proc: int = 5) -> Dict[str, Any]:
    print(f"  --> Running {mode.upper()} mode with {num_sessions} concurrent sessions...")
    controller = ArchitectureController(mode, max_per_proc)

    connect_latencies = []
    t_start = time.perf_counter()

    # Open all sessions
    for i in range(1, num_sessions + 1):
        sess_id = f"sess-{mode}-{num_sessions}-{i}"
        _, lat = controller.open_session(sess_id)
        connect_latencies.append(lat)

    t_total_creation = time.perf_counter() - t_start
    creation_throughput = round(num_sessions / t_total_creation, 2)

    # Let sessions settle and collect OS metrics under load
    time.sleep(0.5)
    os_metrics = controller.collect_os_metrics()

    # Measure message / terminal I/O latency across all sessions
    echo_latencies = []
    sample_size = min(num_sessions, 50)
    for i in range(1, sample_size + 1):
        sess_id = f"sess-{mode}-{num_sessions}-{i}"
        lat = controller.measure_echo_latency(sess_id)
        if lat > 0:
            echo_latencies.append(lat)

    # Close all sessions
    t_close_start = time.perf_counter()
    for i in range(1, num_sessions + 1):
        sess_id = f"sess-{mode}-{num_sessions}-{i}"
        controller.close_session(sess_id)
    cleanup_time_sec = round(time.perf_counter() - t_close_start, 3)

    controller.close_all()

    return {
        "mode": mode,
        "num_sessions": num_sessions,
        "os_metrics": os_metrics,
        "creation_throughput_sessions_sec": creation_throughput,
        "connect_latency_avg_ms": round(sum(connect_latencies) / len(connect_latencies), 2),
        "connect_latency_p95_ms": percentile(connect_latencies, 95),
        "connect_latency_p99_ms": percentile(connect_latencies, 99),
        "echo_latency_p50_ms": percentile(echo_latencies, 50),
        "echo_latency_p95_ms": percentile(echo_latencies, 95),
        "echo_latency_p99_ms": percentile(echo_latencies, 99),
        "cleanup_time_sec": cleanup_time_sec
    }


def start_mock_ssh():
    proc = subprocess.Popen([MOCK_SSHD_BIN, f"--port={SSH_PORT}"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for _ in range(50):
        try:
            with socket.create_connection(("127.0.0.1", SSH_PORT), timeout=0.1):
                return proc
        except Exception:
            time.sleep(0.05)
    raise RuntimeError("Mock SSH server failed to start")
