#!/usr/bin/env python3
"""Retain native crash evidence for explicitly authorized local Java trial PIDs.

No registry/process/profile changes. Raw captures stay in private-dumps; stdout and
JSON contain exception/module/stack metadata only, never arbitrary memory strings.
"""
from __future__ import annotations

import argparse
import ctypes
import json
import os
from pathlib import Path
import re
import struct
import time
import uuid


def evidence_pid(name: str) -> int | None:
    match = re.fullmatch(r"(?:javaw?\.exe\.(\d+)\.dmp|hs_err_pid(\d+)\.log)", name, re.I)
    return int(next(x for x in match.groups() if x)) if match else None


def read_authorized(path: Path) -> set[int]:
    try:
        value = json.loads(path.read_text(encoding="utf-8-sig"))
        if not isinstance(value, list) or any(type(x) is not int or x <= 0 for x in value):
            return set()
        return set(value)
    except (OSError, ValueError):
        return set()


def open_shared(path: Path):
    if os.name != "nt":
        return path.open("rb")
    import msvcrt
    from ctypes import wintypes
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    create = kernel.CreateFileW
    create.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD,
                       wintypes.LPVOID, wintypes.DWORD, wintypes.DWORD, wintypes.HANDLE]
    create.restype = wintypes.HANDLE
    # Keep access to a captured handle even if WER unlinks its directory entry.
    handle = create(str(path), 0x80000000, 1 | 2 | 4, None, 3, 0x80, None)
    if handle == wintypes.HANDLE(-1).value:
        raise ctypes.WinError(ctypes.get_last_error())
    try:
        fd = msvcrt.open_osfhandle(handle, os.O_RDONLY | os.O_BINARY)
    except BaseException:
        kernel.CloseHandle(handle)
        raise
    return os.fdopen(fd, "rb", buffering=0)


def minidump_metadata(path: Path, expected_pid: int) -> dict:
    """Read directories, module identities, exception context and stack words only."""
    with path.open("rb") as stream:
        length = path.stat().st_size

        def read(offset: int, size: int) -> bytes:
            if offset < 0 or size < 0 or offset + size > length:
                raise ValueError("Incomplete or invalid minidump metadata range")
            stream.seek(offset)
            result = stream.read(size)
            if len(result) != size:
                raise ValueError("Short minidump read")
            return result

        signature, version, count, directory, _, timestamp, flags = struct.unpack("<IIIIIIQ", read(0, 32))
        if signature != 0x504D444D or count > 128:
            raise ValueError("Not a supported minidump")
        streams = {}
        for index in range(count):
            kind, size, rva = struct.unpack("<III", read(directory + index * 12, 12))
            streams[kind] = (size, rva)
        memory_ranges = []
        if 5 in streams:
            memory_rva = streams[5][1]
            memory_count, = struct.unpack("<I", read(memory_rva, 4))
            if memory_count > 1000000:
                raise ValueError("Unreasonable memory descriptor count")
            for index in range(memory_count):
                base, size, rva = struct.unpack("<QII", read(memory_rva + 4 + index * 16, 16))
                memory_ranges.append((base, size, rva))

        def thread_stack_bounds(teb: int):
            # Only NT_TIB StackBase/StackLimit metadata; never read other TEB or
            # general memory contents. Those are the two pointers at TEB+8.
            for base, size, rva in memory_ranges:
                if base <= teb + 8 and teb + 24 <= base + size:
                    return struct.unpack("<QQ", read(rva + teb + 8 - base, 16))
            return None

        def thread_deallocation_stack(teb: int):
            # Windows x64 TEB stack-reservation metadata at the requested offset.
            # Kept explicitly named as such; not portable to other TEB layouts.
            address = teb + 0x1478
            for base, size, rva in memory_ranges:
                if base <= address and address + 8 <= base + size:
                    return struct.unpack("<Q", read(rva + address - base, 8))[0]
            return None
        result = {"format": "minidump", "expectedPid": expected_pid,
                  "timestampUnix": timestamp, "flags": hex(flags)}
        if 15 in streams:
            size, rva = streams[15]
            if size >= 12:
                _, misc_flags, pid = struct.unpack("<III", read(rva, 12))
                if misc_flags & 1:
                    result["recordedPid"] = pid
                    if pid != expected_pid:
                        raise ValueError("Minidump PID does not match the authorized filename")
        modules = []
        module_details = []
        if 4 in streams:
            _, rva = streams[4]
            module_count, = struct.unpack("<I", read(rva, 4))
            if module_count > 4096:
                raise ValueError("Unreasonable module count")
            for index in range(module_count):
                base, size, _, _, name_rva = struct.unpack("<QIIII", read(rva + 4 + index * 108, 24))
                name_size, = struct.unpack("<I", read(name_rva, 4))
                if name_size > 32768:
                    raise ValueError("Unreasonable module name length")
                name = read(name_rva + 4, name_size).decode("utf-16-le", errors="replace")
                modules.append((base, base + size, name.replace("\\", "/").rsplit("/", 1)[-1]))
                if re.search(r"^(jvm|shaderc)\.dll$", modules[-1][2], re.I):
                    entry = rva + 4 + index * 108
                    ms, ls = struct.unpack("<II", read(entry + 32, 8))
                    detail = {"name": modules[-1][2], "base": hex(base), "imageSize": size,
                              "fileVersion": ".".join(str(x) for x in [ms >> 16, ms & 65535, ls >> 16, ls & 65535])}
                    cv_size, cv_rva = struct.unpack("<II", read(entry + 76, 8))
                    if 24 <= cv_size <= 4096:
                        cv = read(cv_rva, cv_size)
                        if cv[:4] == b"RSDS":
                            detail["pdbGuid"] = str(uuid.UUID(bytes_le=cv[4:20]))
                            detail["pdbAge"] = struct.unpack_from("<I", cv, 20)[0]
                            detail["pdbName"] = cv[24:].split(b"\0", 1)[0].decode("utf-8", errors="replace").replace("\\", "/").rsplit("/", 1)[-1]
                    module_details.append(detail)

        def location(address: int) -> dict:
            answer = {"address": hex(address)}
            for lower, upper, name in modules:
                if lower <= address < upper:
                    answer.update(module=name, offset=hex(address - lower))
                    break
            return answer

        result["relevantModules"] = [name for _, _, name in modules if re.search(
            r"jvm|java|lwjgl|sdl|nvog|opengl|rtss|discordhook|nahimic|overlay|freetype|shaderc|vulkan", name, re.I)]
        result["compilerModuleDetails"] = module_details
        if 6 not in streams:
            result["exception"] = None
            return result
        _, rva = streams[6]
        tid, = struct.unpack("<I", read(rva, 4))
        code, exception_flags, _, address, parameter_count = struct.unpack("<IIQQI", read(rva + 8, 28))
        parameters = struct.unpack("<15Q", read(rva + 40, 120))
        exception = {"threadId": tid, "code": hex(code), "flags": hex(exception_flags), **location(address)}
        if code == 0xC0000005 and parameter_count >= 2:
            exception["access"] = {0: "read", 1: "write", 8: "execute"}.get(parameters[0], str(parameters[0]))
            exception["accessAddress"] = hex(parameters[1])
        result["exception"] = exception
        if 24 in streams:
            _, names_rva = streams[24]
            name_count, = struct.unpack("<I", read(names_rva, 4))
            for index in range(min(name_count, 4096)):
                named_tid, name_rva = struct.unpack("<IQ", read(names_rva + 4 + index * 12, 12))
                if named_tid == tid:
                    name_size, = struct.unpack("<I", read(name_rva, 4))
                    exception["threadName"] = read(name_rva + 4, min(name_size, 512)).decode("utf-16-le", errors="replace")
                    break
        context_size, context_rva = struct.unpack("<II", read(rva + 160, 8))
        # AMD64 only; do not interpret another architecture's context as x64.
        architecture = None
        if 7 in streams:
            architecture, = struct.unpack("<H", read(streams[7][1], 2))
        if architecture == 9 and context_size >= 256:
            rsp, = struct.unpack("<Q", read(context_rva + 152, 8))
            rip, = struct.unpack("<Q", read(context_rva + 248, 8))
            result["instructionPointer"] = location(rip)
            result["stackPointer"] = hex(rsp)
            candidates = []
            if 3 in streams:
                _, threads_rva = streams[3]
                thread_count, = struct.unpack("<I", read(threads_rva, 4))
                for index in range(min(thread_count, 4096)):
                    entry = threads_rva + 4 + index * 48
                    entry_tid, = struct.unpack("<I", read(entry, 4))
                    if entry_tid != tid:
                        continue
                    teb, = struct.unpack("<Q", read(entry + 16, 8))
                    bounds = thread_stack_bounds(teb)
                    if bounds:
                        stack_base, stack_limit = bounds
                        result["threadStackBounds"] = {"base": hex(stack_base), "committedLimit": hex(stack_limit),
                            "rspRelativeToLimit": rsp - stack_limit,
                            "accessAddressRelativeToLimit": parameters[1] - stack_limit if code == 0xC0000005 and parameter_count >= 2 else None}
                        deallocation = thread_deallocation_stack(teb)
                        if deallocation is not None and 0 < deallocation <= stack_limit <= stack_base and stack_base - deallocation <= 1024 * 1024 * 1024:
                            result["threadStackBounds"].update(windowsX64TebDeallocationStack=hex(deallocation),
                                reservedBytesFromDeallocationStack=stack_base - deallocation)
                    start, size, stack_rva = struct.unpack("<QII", read(entry + 24, 16))
                    result["capturedExceptionThreadStack"] = {"start": hex(start), "end": hex(start + size),
                        "capturedBytes": size, "rspOffsetFromStart": rsp - start}
                    offset = max(0, rsp - start)
                    if offset >= size:
                        break
                    data = read(stack_rva + offset, min(size - offset, 65536))
                    for word_offset in range(0, len(data) - 7, 8):
                        word, = struct.unpack_from("<Q", data, word_offset)
                        entry_location = location(word)
                        if "module" in entry_location:
                            candidates.append({"stackOffset": hex(offset + word_offset), **entry_location})
                            if len(candidates) == 32:
                                break
                    break
            result["stackCandidates"] = candidates
            result["stackCaveat"] = "Module-matching stack words only; not an unwound or symbolized call stack."
        return result


def hs_err_metadata(path: Path, expected_pid: int) -> dict:
    # Select just the JVM header and actual native frame block; no environment,
    # command line, heap, locals, instructions, memory maps or arbitrary strings.
    selected = []
    in_native = False
    with path.open(encoding="utf-8", errors="replace") as stream:
        for index, line in enumerate(stream):
            if index >= 1024:
                break
            line = line.rstrip("\r\n")
            if line.startswith(("# A fatal error", "#  EXCEPTION_", "# JRE version:", "# Java VM:", "# Problematic frame:", "# C  [", "# V  [", "# J  ")):
                selected.append(line)
            if line.startswith("Native frames:"):
                in_native = True
            elif in_native:
                if not line.strip() or line.startswith(("Java frames:", "siginfo:")):
                    break
                if len(selected) < 64:
                    selected.append(line)
    return {"format": "hs_err", "expectedPid": expected_pid, "nativeMetadata": selected[:64]}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--authorized-pids", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--watch-dir", type=Path, action="append", default=[])
    parser.add_argument("--duration", type=float, default=240)
    parser.add_argument("--poll-ms", type=float, default=25)
    parser.add_argument("--max-mib", type=int, default=512)
    parser.add_argument("--resume", action="store_true", help="Preserve this output directory's earlier captures and skip their source names")
    args = parser.parse_args()
    if not 1 <= args.duration <= 900 or not 10 <= args.poll_ms <= 1000 or not 1 <= args.max_mib <= 4096:
        parser.error("Use duration 1..900 seconds, poll 10..1000 ms, max 1..4096 MiB")
    output = args.output.resolve()
    private = output / "private-dumps"
    private.mkdir(parents=True, exist_ok=True)
    watched = args.watch_dir or [Path(os.environ["LOCALAPPDATA"]) / "CrashDumps"]
    active = {}
    captured = set()
    metadata = []
    start = time.monotonic()
    report_path = output / "native-dump-watch-report.json"
    if args.resume and report_path.exists():
        prior = json.loads(report_path.read_text(encoding="utf-8"))
        for record in prior.get("captures", []):
            if evidence_pid(record.get("name", "")) != record.get("pid"):
                raise ValueError("Invalid earlier capture identity")
            metadata.append(record)
            for directory in watched:
                captured.add(str((directory / record["name"]).resolve()))

    def report(status):
        report_path.write_text(json.dumps({"status": status, "elapsedSeconds": round(time.monotonic() - start, 3),
            "authorizedPidFile": str(args.authorized_pids.resolve()),
            "watchedDirectories": [str(x.resolve()) for x in watched],
            "captures": metadata, "scope": "Explicit PID filename match only; private local raw evidence; metadata-only inspection."}, indent=2), encoding="utf-8")

    def finish(key, item, reason):
        # MiniDumpWriteDump may fill directories/header offsets after reserving
        # file space. Re-read the retained source, including already seen bytes.
        item["source"].seek(0)
        item["target"].seek(0)
        copied = 0
        limit = args.max_mib * 1024 * 1024
        while copied < limit:
            chunk = item["source"].read(min(1024 * 1024, limit - copied))
            if not chunk:
                break
            item["target"].write(chunk)
            copied += len(chunk)
        item["target"].truncate(copied)
        item["bytes"] = copied
        item["source"].close()
        item["target"].close()
        record = {"pid": item["pid"], "name": item["path"].name, "bytes": item["bytes"], "completion": reason,
                  "localCapture": str(item["destination"])}
        try:
            fn = minidump_metadata if item["path"].suffix.lower() == ".dmp" else hs_err_metadata
            record["metadata"] = fn(item["destination"], item["pid"])
        except (OSError, ValueError, struct.error) as error:
            record["metadataError"] = str(error)
        metadata.append(record)
        del active[key]
        print(json.dumps(record), flush=True)
        report("watching")

    print(json.dumps({"status": "ready", "authorizedPidFile": str(args.authorized_pids.resolve()),
                      "durationSeconds": args.duration, "watchedDirectories": [str(x) for x in watched]}), flush=True)
    report("watching")
    try:
        while time.monotonic() - start < args.duration:
            authorized = read_authorized(args.authorized_pids)
            for directory in watched:
                try:
                    paths = list(directory.iterdir())
                except OSError:
                    continue
                for path in paths:
                    pid = evidence_pid(path.name)
                    key = str(path.resolve())
                    if pid not in authorized or key in captured:
                        continue
                    try:
                        source = open_shared(path)
                    except OSError:
                        continue
                    destination = private / f"{len(metadata) + len(active):02d}-{path.name}"
                    target = destination.open("wb")
                    active[key] = {"source": source, "target": target, "destination": destination,
                                   "path": path, "pid": pid, "bytes": 0, "lastGrowth": time.monotonic()}
                    captured.add(key)
                    print(json.dumps({"status": "retained-handle", "pid": pid, "name": path.name}), flush=True)
            for key, item in list(active.items()):
                remaining = args.max_mib * 1024 * 1024 - item["bytes"]
                chunk = item["source"].read(min(1024 * 1024, remaining))
                if chunk:
                    item["target"].write(chunk)
                    item["bytes"] += len(chunk)
                    item["lastGrowth"] = time.monotonic()
                if item["bytes"] >= args.max_mib * 1024 * 1024:
                    finish(key, item, "size-limit; may be partial")
                elif not chunk and time.monotonic() - item["lastGrowth"] >= 3:
                    finish(key, item, "no further bytes observed for 3 seconds; completeness not guaranteed")
            time.sleep(args.poll_ms / 1000)
    finally:
        for key, item in list(active.items()):
            finish(key, item, "watch duration ended; may be partial")
        report("finished")
    print(json.dumps({"status": "finished", "captures": len(metadata), "report": str(report_path)}), flush=True)


if __name__ == "__main__":
    main()
