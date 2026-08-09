#!/usr/bin/env python3
"""Measure config->hook propagation latency for the FakeGps location snapshot.

WHAT IT MEASURES
    The interval between new config bytes landing on the hook-readable prefs
    file and each hooked process committing that payload into its serving
    Snapshot.

    That interval is the whole of the user-visible "I moved the pin but the map
    still shows the old address" delay. Every hook callback reads
    MainHook.CURRENT at invocation time (HookUtils.currentSnapshot), so once the
    commit happens ALL surfaces serve the new value on their very next call.
    Nothing downstream of the commit is slow, which is why the commit instant is
    the only thing worth measuring.

WHY IT WRITES THE PREFS FILE DIRECTLY
    Driving the UI would fold app-side Room/ContentProvider/Compose work into
    the number and would not be reproducible tick-to-tick. Writing the transport
    file is the narrowest stimulus that exercises exactly the path under test.

    The write is an in-place truncate+rewrite (`base64 -d > target`) so inode,
    owner, mode and SELinux context are preserved -- no chcon/chown guesswork.
    Content is base64-framed end to end so no adb line-ending translation can
    corrupt the XML. Original bytes are restored on success, failure and SIGINT,
    and the restore is verified by md5.

CORRELATION
    Both ends are already instrumented in the shipped build, so this adds no
    probe code to the module:
      - the hook logs "transport accepted schema=<v> fp=sha256:<16hex>"
      - the fingerprint is computed here identically to
        name.caiyao.fakegps.config.PublishedConfig.fingerprint
    Matching on fingerprint rather than wall-clock proximity is what makes a
    sample attributable to a specific publish instead of to whichever periodic
    tick happened to land nearby.

USAGE
    refresh_latency_probe.py --rounds 5 [--interval-sec 5] [--json-out f.json]
    refresh_latency_probe.py --observe-only 60      # passive cadence census
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import html
import json
import random
import re
import signal
import statistics
import subprocess
import sys
import time

PKG = "name.caiyao.fakegps"
HOOK_PREFS_GLOB_TEMPLATE = "/data/misc/*/prefs/%s/spoof_config.xml"
TMP_REMOTE = "/data/local/tmp/.fakegps_refresh_probe.b64"

ACCEPT_RE = re.compile(
    r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d\d\d)\s+(\d+)\s+\d+\s+\w+\s+\S+?:\s*"
    r"FakeGPS: transport accepted schema=\d+ fp=(sha256:[0-9a-f]+)"
)


def adb(args: list[str], stdin: str | None = None) -> subprocess.CompletedProcess:
    return subprocess.run(["adb"] + args, input=stdin, capture_output=True, text=True)


def sh(cmd: str, check: bool = True) -> str:
    p = adb(["shell", cmd])
    if check and p.returncode != 0:
        raise RuntimeError("adb shell failed: %s\n%s" % (cmd, p.stderr.strip()))
    return p.stdout


def su(cmd: str, check: bool = True) -> str:
    return sh("su -c %s" % shquote(cmd), check=check)


def shquote(s: str) -> str:
    return "'" + s.replace("'", "'\\''") + "'"


def device_ready() -> None:
    out = adb(["devices"]).stdout
    devices = [l for l in out.splitlines()[1:] if l.strip().endswith("\tdevice")]
    if len(devices) != 1:
        raise SystemExit(
            "ABORT need exactly one attached device, found %d.\n"
            "An ambiguous target makes every timestamp unattributable." % len(devices)
        )
    if "uid=0" not in su("id"):
        raise SystemExit("ABORT root (su) required to read/write the hook prefs file.")


def resolve_prefs_path(pkg: str) -> str:
    glob = HOOK_PREFS_GLOB_TEMPLATE % pkg
    paths = [l.strip() for l in su("ls -d %s 2>/dev/null" % glob).splitlines()
             if l.strip()]
    if len(paths) != 1:
        raise SystemExit(
            "ABORT expected exactly 1 hook prefs file, found %d: %r\n"
            "Measuring the wrong copy would report the app-private file's cadence "
            "instead of the hook's." % (len(paths), paths)
        )
    return paths[0]


def read_file_b64(path: str) -> bytes:
    out = su("base64 %s" % shquote(path))
    return base64.b64decode("".join(out.split()))


def write_file_b64(path: str, data: bytes) -> None:
    """In-place rewrite preserving inode/owner/mode/SELinux context."""
    payload = base64.b64encode(data).decode("ascii")
    p = adb(["shell", "su -c %s" % shquote("cat > %s" % TMP_REMOTE)], stdin=payload)
    if p.returncode != 0:
        raise RuntimeError("staging write failed: %s" % p.stderr)
    su("base64 -d %s > %s" % (shquote(TMP_REMOTE), shquote(path)))


def md5(path: str) -> str:
    out = su("md5sum %s" % shquote(path), check=False).split()
    return out[0] if out else "?"


def fingerprint(json_str: str) -> str:
    return "sha256:" + hashlib.sha256(json_str.encode("utf-8")).hexdigest()[:16]


def extract_json(xml_text: str) -> str:
    m = re.search(r'<string name="json">(.*?)</string>', xml_text, re.S)
    if not m:
        raise SystemExit('ABORT prefs file has no <string name="json"> payload.')
    return html.unescape(m.group(1))


def render_xml(json_str: str, published_at_ms: int) -> str:
    esc = (json_str.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
           .replace('"', "&quot;").replace("'", "&apos;"))
    return ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
            '    <string name="json">%s</string>\n'
            '    <long name="published_at" value="%d" />\n</map>\n' % (esc, published_at_ms))


def device_clock() -> tuple[str, float]:
    """(logcat-format stamp, epoch seconds) from the DEVICE clock.

    Both halves come from one call so host/device skew can never enter a delta:
    every latency below is device-clock minus device-clock.
    """
    raw = sh("date '+%m-%d %H:%M:%S.%3N %s.%3N'").strip()
    stamp, epoch = raw.rsplit(" ", 1)
    return stamp, float(epoch)


def parse_logcat_ts(ts: str, year: int, ref_epoch: float) -> float:
    date_part, frac = ts.split(".")
    base = time.mktime(time.strptime("%d-%s" % (year, date_part), "%Y-%m-%d %H:%M:%S"))
    val = base + float("0." + frac)
    # Year rollover guard: a Dec->Jan wrap would otherwise read as ~1y in the past.
    if val - ref_epoch > 86400:
        val = time.mktime(time.strptime("%d-%s" % (year - 1, date_part),
                                        "%Y-%m-%d %H:%M:%S")) + float("0." + frac)
    return val


def scan_accepts(since: str) -> list[tuple[str, int, str]]:
    out = adb(["logcat", "-d", "-v", "threadtime", "-t", since]).stdout
    rows = []
    for line in out.splitlines():
        m = ACCEPT_RE.search(line)
        if m:
            rows.append((m.group(1), int(m.group(2)), m.group(3)))
    return rows


def observe(seconds: int, year: int) -> int:
    """Passive census: per-process tick cadence, zero writes."""
    since, t0 = device_clock()
    print("observing %ds (no writes) ..." % seconds, flush=True)
    time.sleep(seconds)
    rows = scan_accepts(since)
    by_pid: dict[int, list[float]] = {}
    fps: set[str] = set()
    for ts, pid, fp in rows:
        by_pid.setdefault(pid, []).append(parse_logcat_ts(ts, year, t0))
        fps.add(fp)
    if not by_pid:
        print("no reload evidence — hook inactive or descoped.")
        return 1
    names = {}
    for pid in by_pid:
        n = su("ps -A -o NAME -p %d" % pid, check=False).splitlines()
        names[pid] = n[-1].strip() if len(n) > 1 else "?"
    print("\n%-8s %-38s %6s %9s %9s" % ("PID", "PROCESS", "TICKS", "MEAN(s)", "STDEV"))
    for pid, ts in sorted(by_pid.items()):
        gaps = [b - a for a, b in zip(ts, ts[1:])]
        mean = statistics.mean(gaps) if gaps else float("nan")
        sd = statistics.stdev(gaps) if len(gaps) > 1 else 0.0
        print("%-8d %-38s %6d %9.3f %9.3f" % (pid, names[pid][:38], len(ts), mean, sd))
    total = sum(len(v) for v in by_pid.values())
    print("\ndistinct fingerprints observed: %d %s" % (len(fps), sorted(fps)))
    print("total reloads: %d across %d processes in %ds -> %.1f reloads/min"
          % (total, len(by_pid), seconds, total * 60.0 / seconds))
    if len(fps) == 1:
        print("NOTE every reload re-read and re-parsed an UNCHANGED payload.")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--rounds", type=int, default=5)
    ap.add_argument("--interval-sec", type=int, default=None)
    ap.add_argument("--timeout", type=float, default=None)
    ap.add_argument("--settle", type=float, default=2.0,
                    help="Extra wait after the first acceptance so slower "
                         "processes on independent timers are not missed.")
    ap.add_argument("--observe-only", type=int, metavar="SECONDS", default=None)
    ap.add_argument("--json-out", default=None)
    ap.add_argument("--phase", choices=("random", "locked"), default="random",
                    help="random (default): sleep U(0,interval) before each write so the "
                         "write phase is uncorrelated with the tick, reproducing what a user "
                         "who saves at an arbitrary moment actually experiences. "
                         "locked: write immediately after the previous acceptance, which "
                         "deterministically samples the WORST case (~interval).")
    ap.add_argument("--seed", type=int, default=None, help="Seed for --phase random.")
    ap.add_argument("--pkg", default=PKG,
                    help="Package whose spoof_config to probe (default: official). "
                         "Use name.caiyao.fakegps.bench for bench-module runs.")
    args = ap.parse_args()
    if args.seed is not None:
        random.seed(args.seed)

    device_ready()
    year = time.localtime().tm_year
    if args.observe_only:
        return observe(args.observe_only, year)

    path = resolve_prefs_path(args.pkg)
    original = read_file_b64(path)
    original_json = extract_json(original.decode("utf-8"))
    original_md5 = md5(path)
    base = json.loads(original_json)
    interval = args.interval_sec or int(base.get("refreshIntervalSec", 30))
    timeout = args.timeout or (interval * 3 + 6)

    print("prefs path   : %s" % path)
    print("baseline fp  : %s" % fingerprint(original_json))
    print("baseline md5 : %s" % original_md5)
    print("interval     : %ds (%s)" % (interval,
          "--interval-sec" if args.interval_sec else "from live payload"))
    print("rounds       : %d, timeout %.0fs/round" % (args.rounds, timeout))

    state = {"restored": False}

    def restore(*_a):
        if state["restored"]:
            return
        state["restored"] = True
        try:
            write_file_b64(path, original)
            ok = md5(path) == original_md5
        except Exception as e:  # noqa: BLE001
            ok = False
            print("restore error: %s" % e)
        su("rm -f %s" % shquote(TMP_REMOTE), check=False)
        print("\nrestore: %s" % ("OK (md5 matches baseline)" if ok else
                                 "*** FAILED — restore %s by hand ***" % path))

    signal.signal(signal.SIGINT, lambda *a: (restore(), sys.exit(130)))

    rounds = []
    try:
        for i in range(1, args.rounds + 1):
            probe = dict(base)
            probe["refreshIntervalSec"] = interval
            fields = dict(probe.get("fields", {}))
            # Perturb far below any map's rendering resolution: a NEW fingerprint
            # without moving the user's pin anywhere they could notice.
            fields["latitude"] = round(float(fields.get("latitude", 0.0)) + i * 1e-7, 12)
            probe["fields"] = fields
            payload = json.dumps(probe, separators=(",", ":"), ensure_ascii=False)
            fp = fingerprint(payload)

            # Decorrelate the write from the tick phase. Without this the loop
            # always writes just after an acceptance, so every sample lands near
            # the worst case and the reported mean is the period, not period/2.
            if args.phase == "random" and i > 1:
                skew = random.uniform(0, interval)
                print("  (phase skew %.2fs)" % skew, flush=True)
                time.sleep(skew)

            since, t_write = device_clock()
            write_file_b64(path, render_xml(payload, int(time.time() * 1000)).encode())

            print("\nround %d/%d fp=%s" % (i, args.rounds, fp), flush=True)
            seen: dict[int, float] = {}
            deadline = time.time() + timeout
            first_hit = None
            while time.time() < deadline:
                for ts, pid, got in scan_accepts(since):
                    if got == fp and pid not in seen:
                        seen[pid] = max(0.0, parse_logcat_ts(ts, year, t_write) - t_write)
                        first_hit = first_hit or time.time()
                if first_hit and time.time() - first_hit > args.settle:
                    break
                time.sleep(0.3)
            if not seen:
                print("  NO ACCEPTANCE within %.0fs — hook dead or descoped." % timeout)
            for pid, lat in sorted(seen.items(), key=lambda kv: kv[1]):
                print("  pid %-6d %7.3fs" % (pid, lat))
            rounds.append({"index": i, "fp": fp, "latencyByPid": seen})
    finally:
        restore()

    lat = [v for r in rounds for v in r["latencyByPid"].values()]
    print("\n" + "=" * 64)
    if lat:
        print("samples %d | min %.3f | mean %.3f | median %.3f | max %.3f (s)"
              % (len(lat), min(lat), statistics.mean(lat), statistics.median(lat), max(lat)))
        print("configured %ds -> theoretical uniform(0,%d), mean %.1fs"
              % (interval, interval, interval / 2.0))
    else:
        print("no samples collected")

    if args.json_out:
        with open(args.json_out, "w") as fh:
            json.dump({"intervalSec": interval, "prefsPath": path,
                       "rounds": [{**r, "latencyByPid": {str(k): v
                                                         for k, v in r["latencyByPid"].items()}}
                                  for r in rounds]}, fh, indent=2)
        print("wrote %s" % args.json_out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
