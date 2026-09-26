"""Synthetic-only tests: never open a real process dump or user profile."""
import importlib.util
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import time
import unittest

SPEC = importlib.util.spec_from_file_location("watch_native_dumps", Path(__file__).with_name("watch_native_dumps.py"))
WATCH = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(WATCH)


def synthetic_dump(pid):
    data = bytearray(2048)
    struct.pack_into("<IIIIIIQ", data, 0, 0x504D444D, 0, 7, 32, 0, 1, 0)
    for index, (kind, size, rva) in enumerate([(4, 112, 128), (6, 168, 256),
            (7, 56, 448), (15, 24, 512), (3, 52, 544), (24, 16, 1100), (5, 20, 1180)]):
        struct.pack_into("<III", data, 32 + index * 12, kind, size, rva)
    struct.pack_into("<I", data, 128, 1)
    struct.pack_into("<QIIII", data, 132, 0x10000000, 0x10000, 0, 0, 1024)
    module = "C:\\synthetic-only\\jvm.dll".encode("utf-16-le")
    struct.pack_into("<I", data, 1024, len(module))
    data[1028:1028 + len(module)] = module
    struct.pack_into("<I", data, 256, 77)
    struct.pack_into("<IIQQI", data, 264, 0xC0000005, 0, 0, 0x10001234, 2)
    struct.pack_into("<2Q", data, 296, 0, 0xFFFFFFFFFFFFFFFF)
    struct.pack_into("<II", data, 416, 256, 640)
    struct.pack_into("<H", data, 448, 9)
    struct.pack_into("<III", data, 512, 24, 1, pid)
    struct.pack_into("<I", data, 544, 1)
    struct.pack_into("<I", data, 548, 77)
    struct.pack_into("<Q", data, 564, 0x30000000)
    struct.pack_into("<QII", data, 572, 0x20000000, 24, 960)
    struct.pack_into("<Q", data, 792, 0x20000000)
    struct.pack_into("<Q", data, 888, 0x10001234)
    struct.pack_into("<3Q", data, 960, 0x10004321, 0xDEADBEEF, 0x10003456)
    struct.pack_into("<IIQ", data, 1100, 1, 77, 1120)
    thread_name = "Synthetic-worker".encode("utf-16-le")
    struct.pack_into("<I", data, 1120, len(thread_name))
    data[1124:1124 + len(thread_name)] = thread_name
    struct.pack_into("<IQII", data, 1180, 1, 0x30000000, 32, 1240)
    struct.pack_into("<QQ", data, 1248, 0x20001000, 0x1FFFF000)
    return bytes(data)


class WatcherTest(unittest.TestCase):
    def test_exact_pid_names(self):
        self.assertEqual(WATCH.evidence_pid("javaw.exe.3732.dmp"), 3732)
        self.assertEqual(WATCH.evidence_pid("java.exe.3732.dmp"), 3732)
        self.assertEqual(WATCH.evidence_pid("hs_err_pid3732.log"), 3732)
        for value in ["javaw.exe.37320.dmp.tmp", "notjavaw.exe.3732.dmp", "3732.dmp", "javaw.exe.3732.dmp.secret"]:
            self.assertIsNone(WATCH.evidence_pid(value))

    def test_authorization_requires_positive_integer_array(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "pids.json"
            for value in [True, {"pid": 3732}, [True], [0], ["3732"], [3732, -1]]:
                path.write_text(json.dumps(value))
                self.assertEqual(WATCH.read_authorized(path), set())
            path.write_text("[3732,424242]", encoding="utf-8-sig")
            self.assertEqual(WATCH.read_authorized(path), {3732, 424242})

    def test_metadata_and_pid_cross_check(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "synthetic.dmp"
            path.write_bytes(synthetic_dump(424242))
            info = WATCH.minidump_metadata(path, 424242)
            self.assertEqual(info["recordedPid"], 424242)
            self.assertEqual(info["exception"]["module"], "jvm.dll")
            self.assertEqual(info["exception"]["offset"], "0x1234")
            self.assertEqual(info["exception"]["access"], "read")
            self.assertEqual(info["exception"]["threadName"], "Synthetic-worker")
            self.assertEqual(info["threadStackBounds"]["rspRelativeToLimit"], 4096)
            self.assertEqual(len(info["stackCandidates"]), 2)
            self.assertNotIn("synthetic-only", json.dumps(info))
            with self.assertRaisesRegex(ValueError, "PID"):
                WATCH.minidump_metadata(path, 424243)
            path.write_bytes(b"MDMP")
            with self.assertRaises(ValueError):
                WATCH.minidump_metadata(path, 424242)

    def test_shared_handle_survives_unlink(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "synthetic-file"
            path.write_bytes(b"synthetic only")
            with WATCH.open_shared(path) as retained:
                path.unlink()
                self.assertEqual(retained.read(), b"synthetic only")

    def test_only_authorized_capture_and_final_rewrite(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            watched = root / "watch"
            watched.mkdir()
            pids = root / "pids.json"
            pids.write_text("[424242]")
            authorized = watched / "javaw.exe.424242.dmp"
            authorized.write_bytes(bytes(2048))
            (watched / "javaw.exe.424243.dmp").write_bytes(synthetic_dump(424243))
            process = subprocess.Popen([sys.executable, str(Path(WATCH.__file__)),
                "--authorized-pids", str(pids), "--output", str(root / "out"),
                "--watch-dir", str(watched), "--duration", "4"], stdout=subprocess.PIPE,
                stderr=subprocess.PIPE, text=True)
            try:
                self.assertIn('"ready"', process.stdout.readline())
                self.assertIn('"retained-handle"', process.stdout.readline())
                # Rewrite existing bytes after initial capture: real dump writers
                # update their header/directories in place, not just append.
                time.sleep(0.1)
                with authorized.open("r+b") as stream:
                    stream.write(synthetic_dump(424242))
                _, errors = process.communicate(timeout=8)
                self.assertEqual(process.returncode, 0, errors)
                report = json.loads((root / "out/native-dump-watch-report.json").read_text())
                self.assertEqual(len(report["captures"]), 1)
                self.assertEqual(report["captures"][0]["metadata"]["recordedPid"], 424242)
                resumed = subprocess.run([sys.executable, str(Path(WATCH.__file__)),
                    "--authorized-pids", str(pids), "--output", str(root / "out"),
                    "--watch-dir", str(watched), "--duration", "1", "--resume"],
                    capture_output=True, text=True, timeout=5)
                self.assertEqual(resumed.returncode, 0, resumed.stderr)
                self.assertNotIn("retained-handle", resumed.stdout)
                report = json.loads((root / "out/native-dump-watch-report.json").read_text())
                self.assertEqual(len(report["captures"]), 1)
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait()


if __name__ == "__main__":
    unittest.main()
