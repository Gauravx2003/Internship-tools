#!/usr/bin/env python3
"""
WebSession Engine Benchmark Suite (Phase 2: I/O Throughput, Crash Impact & Leak Detection)
Compares 3 Architectures:
  - Approach 1: Single Go Process (N = 1000)
  - Approach 2: Process-per-Session (N = 1)
  - Approach 3: Bounded Multiplexed (N = 5)
"""

import os
import sys
import time
import json
import socket
import subprocess
import threading
from typing import List, Dict, Any, Tuple

from run_benchmarks import (
    ENGINE_BIN,
    MOCK_SSHD_BIN,
    SSH_PORT,
    start_mock_ssh,
    read_proc_rss_mb,
    read_proc_fds,
    percentile,
    ArchitectureController
)

def benchmark_high_terminal_io(mode: str, num_sessions: int, msgs_per_session: int = 50, payload_bytes: int = 1024) -> Dict[str, Any]:
    """Measures terminal I/O throughput and message latency distribution under load."""
    print(f"\n[*] Testing High Terminal I/O: Mode={mode.upper()}, Sessions={num_sessions}, Msgs/Session={msgs_per_session}, MsgSize={payload_bytes}B")
    controller = ArchitectureController(mode, max_sessions_per_proc=5 if mode == "bounded" else (1 if mode == "per_session" else 1000))

    # Open sessions
    for i in range(1, num_sessions + 1):
        controller.open_session(f"io-sess-{mode}-{i}")

    payload = "X" * payload_bytes + "\n"
    latencies = []
    total_bytes_sent = 0

    t_start = time.perf_counter()

    for s_idx in range(1, num_sessions + 1):
        sess_id = f"io-sess-{mode}-{s_idx}"
        proc = controller.session_to_proc[sess_id]
        for m in range(msgs_per_session):
            t0 = time.perf_counter()
            proc.send_line({
                "type": "input",
                "sessionId": sess_id,
                "payload": {"data": payload}
            })
            resp = proc.wait_for_envelope(sess_id, "output", timeout=2.0)
            lat_ms = (time.perf_counter() - t0) * 1000.0
            if resp:
                latencies.append(lat_ms)
                total_bytes_sent += payload_bytes

    t_duration = time.perf_counter() - t_start
    total_msgs = len(latencies)
    throughput_kb_s = round((total_bytes_sent / 1024.0) / t_duration, 2)
    throughput_msgs_s = round(total_msgs / t_duration, 2)

    controller.close_all()

    return {
        "mode": mode,
        "num_sessions": num_sessions,
        "total_messages": total_msgs,
        "total_kb_transferred": round(total_bytes_sent / 1024.0, 2),
        "duration_sec": round(t_duration, 3),
        "throughput_kb_sec": throughput_kb_s,
        "throughput_msgs_sec": throughput_msgs_s,
        "latency_p50_ms": percentile(latencies, 50),
        "latency_p95_ms": percentile(latencies, 95),
        "latency_p99_ms": percentile(latencies, 99),
        "latency_max_ms": round(max(latencies), 2) if latencies else 0.0
    }


def benchmark_process_crash_impact(mode: str, total_sessions: int = 20) -> Dict[str, Any]:
    """Measures blast radius (sessions affected) and recovery time when 1 Go process is killed."""
    print(f"\n[*] Testing Process Crash Impact: Mode={mode.upper()}, TotalSessions={total_sessions}")
    controller = ArchitectureController(mode, max_sessions_per_proc=5 if mode == "bounded" else (1 if mode == "per_session" else 1000))

    session_ids = [f"crash-sess-{mode}-{i}" for i in range(1, total_sessions + 1)]
    for sid in session_ids:
        controller.open_session(sid)

    # Pick the first process and kill it
    victim_proc = controller.processes[0]
    victim_pid = victim_proc.proc.pid
    sessions_on_victim = [sid for sid, p in controller.session_to_proc.items() if p == victim_proc]

    print(f"    Target victim process PID={victim_pid} hosting {len(sessions_on_victim)} sessions")

    t_kill = time.perf_counter()
    victim_proc.proc.kill()
    victim_proc.proc.wait()

    # Measure how many sessions in total failed vs survived
    dead_sessions = []
    surviving_sessions = []
    for sid in session_ids:
        proc = controller.session_to_proc[sid]
        if proc.proc.poll() is not None:
            dead_sessions.append(sid)
        else:
            # Check if still responds
            lat = controller.measure_echo_latency(sid)
            if lat > 0:
                surviving_sessions.append(sid)
            else:
                dead_sessions.append(sid)

    # Measure recovery time: spawn a replacement session
    t0_rec = time.perf_counter()
    new_sid = f"recovery-sess-{mode}"
    controller.open_session(new_sid)
    recovery_time_ms = round((time.perf_counter() - t0_rec) * 1000.0, 2)

    controller.close_all()

    return {
        "mode": mode,
        "total_active_sessions_before_crash": total_sessions,
        "sessions_killed_count": len(dead_sessions),
        "sessions_killed_percentage": round((len(dead_sessions) / total_sessions) * 100.0, 1),
        "surviving_sessions_count": len(surviving_sessions),
        "recovery_time_new_session_ms": recovery_time_ms
    }


def benchmark_churn_and_leaks(mode: str, cycles: int = 50) -> Dict[str, Any]:
    """Executes repeated connect/disconnect cycles to detect FD and memory leaks."""
    print(f"\n[*] Testing Churn & Leaks: Mode={mode.upper()}, Cycles={cycles}")
    controller = ArchitectureController(mode, max_sessions_per_proc=5 if mode == "bounded" else (1 if mode == "per_session" else 1000))

    # Baseline warmup
    controller.open_session("warmup-1")
    controller.close_session("warmup-1")
    time.sleep(0.2)
    baseline_metrics = controller.collect_os_metrics()

    # Run churn
    t0 = time.perf_counter()
    for c in range(1, cycles + 1):
        sid = f"churn-{mode}-{c}"
        controller.open_session(sid)
        controller.measure_echo_latency(sid)
        controller.close_session(sid)

    t_churn_duration = time.perf_counter() - t0
    time.sleep(0.5)

    post_metrics = controller.collect_os_metrics()
    controller.close_all()

    return {
        "mode": mode,
        "cycles": cycles,
        "duration_sec": round(t_churn_duration, 2),
        "cycles_per_sec": round(cycles / t_churn_duration, 2),
        "baseline_rss_mb": baseline_metrics["total_rss_mb"],
        "post_churn_rss_mb": post_metrics["total_rss_mb"],
        "rss_delta_mb": round(post_metrics["total_rss_mb"] - baseline_metrics["total_rss_mb"], 2),
        "baseline_fds": baseline_metrics["total_open_fds"],
        "post_churn_fds": post_metrics["total_open_fds"],
        "fd_delta": post_metrics["total_open_fds"] - baseline_metrics["total_open_fds"]
    }


def main():
    print("=================================================================")
    print(" STARTING RESILIENCE & I/O BENCHMARK SUITE (PHASE 2)")
    print("=================================================================")
    mock_proc = start_mock_ssh()

    modes = ["single", "per_session", "bounded"]
    resilience_results = {
        "io_throughput": {},
        "crash_impact": {},
        "churn_leaks": {}
    }

    try:
        # 1. High Terminal I/O Benchmark (20 sessions, 50 msgs each, 1KB each)
        for m in modes:
            res = benchmark_high_terminal_io(m, num_sessions=20, msgs_per_session=50, payload_bytes=1024)
            resilience_results["io_throughput"][m] = res

        # 2. Crash Impact & Blast Radius Benchmark (20 active sessions, kill 1 process)
        for m in modes:
            res = benchmark_process_crash_impact(m, total_sessions=20)
            resilience_results["crash_impact"][m] = res

        # 3. Repeated Churn & Leak Detection Benchmark (50 connect/disconnect cycles)
        for m in modes:
            res = benchmark_churn_and_leaks(m, cycles=50)
            resilience_results["churn_leaks"][m] = res

        with open("benchmark/results/phase2_resilience_results.json", "w") as f:
            json.dump(resilience_results, f, indent=2)

        print("\n=================================================================")
        print(" PHASE 2 BENCHMARK SUMMARY")
        print("=================================================================")

        print("\n--- 1. Terminal I/O Throughput (20 sessions, 1KB payloads) ---")
        print(f"{'Metric':<35} | {'Single Process':<18} | {'Process-per-Session':<20} | {'Bounded (N=5)':<18}")
        print("-" * 100)
        s_io = resilience_results["io_throughput"]["single"]
        p_io = resilience_results["io_throughput"]["per_session"]
        b_io = resilience_results["io_throughput"]["bounded"]
        print(f"{'Throughput (KB/sec)':<35} | {s_io['throughput_kb_sec']:<18} | {p_io['throughput_kb_sec']:<20} | {b_io['throughput_kb_sec']:<18}")
        print(f"{'Message Rate (msgs/sec)':<35} | {s_io['throughput_msgs_sec']:<18} | {p_io['throughput_msgs_sec']:<20} | {b_io['throughput_msgs_sec']:<18}")
        print(f"{'Latency p50 (ms)':<35} | {s_io['latency_p50_ms']:<18} | {p_io['latency_p50_ms']:<20} | {b_io['latency_p50_ms']:<18}")
        print(f"{'Latency p95 (ms)':<35} | {s_io['latency_p95_ms']:<18} | {p_io['latency_p95_ms']:<20} | {b_io['latency_p95_ms']:<18}")
        print(f"{'Latency p99 (ms)':<35} | {s_io['latency_p99_ms']:<18} | {p_io['latency_p99_ms']:<20} | {b_io['latency_p99_ms']:<18}")

        print("\n--- 2. Process Crash Impact & Blast Radius (20 active sessions) ---")
        print(f"{'Metric':<35} | {'Single Process':<18} | {'Process-per-Session':<20} | {'Bounded (N=5)':<18}")
        print("-" * 100)
        s_cr = resilience_results["crash_impact"]["single"]
        p_cr = resilience_results["crash_impact"]["per_session"]
        b_cr = resilience_results["crash_impact"]["bounded"]
        print(f"{'Sessions Killed on 1 Crash':<35} | {s_cr['sessions_killed_count']:<18} | {p_cr['sessions_killed_count']:<20} | {b_cr['sessions_killed_count']:<18}")
        print(f"{'Blast Radius (% sessions lost)':<35} | {str(s_cr['sessions_killed_percentage']) + '%':<18} | {str(p_cr['sessions_killed_percentage']) + '%':<20} | {str(b_cr['sessions_killed_percentage']) + '%':<18}")
        print(f"{'Surviving Sessions':<35} | {s_cr['surviving_sessions_count']:<18} | {p_cr['surviving_sessions_count']:<20} | {b_cr['surviving_sessions_count']:<18}")
        print(f"{'Recovery Time (ms)':<35} | {s_cr['recovery_time_new_session_ms']:<18} | {p_cr['recovery_time_new_session_ms']:<20} | {b_cr['recovery_time_new_session_ms']:<18}")

        print("\n--- 3. Churn & Leak Detection (50 connect/disconnect cycles) ---")
        print(f"{'Metric':<35} | {'Single Process':<18} | {'Process-per-Session':<20} | {'Bounded (N=5)':<18}")
        print("-" * 100)
        s_lk = resilience_results["churn_leaks"]["single"]
        p_lk = resilience_results["churn_leaks"]["per_session"]
        b_lk = resilience_results["churn_leaks"]["bounded"]
        print(f"{'Churn Rate (cycles/sec)':<35} | {s_lk['cycles_per_sec']:<18} | {p_lk['cycles_per_sec']:<20} | {b_lk['cycles_per_sec']:<18}")
        print(f"{'Residual RSS Delta (MB)':<35} | {s_lk['rss_delta_mb']:<18} | {p_lk['rss_delta_mb']:<20} | {b_lk['rss_delta_mb']:<18}")
        print(f"{'Residual FD Delta':<35} | {s_lk['fd_delta']:<18} | {p_lk['fd_delta']:<20} | {b_lk['fd_delta']:<18}")

    finally:
        mock_proc.terminate()
        try:
            mock_proc.wait(timeout=2)
        except Exception:
            mock_proc.kill()
        print("\n[*] Phase 2 finished cleanly.")


if __name__ == "__main__":
    main()
