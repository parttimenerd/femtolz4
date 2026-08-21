#!/usr/bin/env python3
"""
Build the femtolz4 native libraries.

Targets:
  darwin-aarch64   built natively on macOS with clang
  linux-amd64      built natively on Linux, or cross-compiled on macOS
                   using x86_64-linux-musl-gcc (brew install FiloSottile/musl-cross/musl-cross)

JVM headers are sourced from $JAVA_HOME when available.  If the headers for
the *target* platform are missing (e.g. cross-compiling linux on macOS) the
script downloads a minimal Temurin JDK tarball and extracts only the headers.

Usage:
  python3 build_native.py               # build all targets for this host
  python3 build_native.py darwin-aarch64 linux-amd64
  python3 build_native.py --list        # show available targets
"""

from __future__ import annotations

import argparse
import os
import platform
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path

# ── Config ─────────────────────────────────────────────────────────────────────

SCRIPT_DIR  = Path(__file__).resolve().parent
NATIVE_SRC  = SCRIPT_DIR / "src" / "main" / "native"
NATIVE_OUT  = SCRIPT_DIR / "src" / "main" / "resources" / "native"

# Temurin 21 JDK tarballs — used only when JAVA_HOME headers are unavailable.
# We unpack just include/ (~80 KB), not the whole JDK.
TEMURIN_HEADERS = {
    "linux-amd64": (
        "https://github.com/adoptium/temurin21-binaries/releases/download/"
        "jdk-21.0.5%2B11/OpenJDK21U-jdk_x64_linux_hotspot_21.0.5_11.tar.gz",
        "jdk-21.0.5+11/include",
    ),
    "darwin-aarch64": (
        "https://github.com/adoptium/temurin21-binaries/releases/download/"
        "jdk-21.0.5%2B11/OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.5_11.tar.gz",
        "jdk-21.0.5+11/Contents/Home/include",
    ),
}

# ── Helpers ─────────────────────────────────────────────────────────────────────

def run(cmd: list[str], **kwargs) -> None:
    print("  $", " ".join(cmd))
    r = subprocess.run(cmd, **kwargs)
    if r.returncode != 0:
        sys.exit(r.returncode)


def host_os() -> str:
    s = platform.system().lower()
    if s == "darwin":  return "macos"
    if s == "linux":   return "linux"
    return s


def host_arch() -> str:
    m = platform.machine().lower()
    if m in ("aarch64", "arm64"): return "aarch64"
    if m in ("x86_64", "amd64"):  return "amd64"
    return m


def find_java_include(target: str) -> Path:
    """
    Return a directory that contains jni.h for *target*.

    1. If JAVA_HOME is set and its include/ has jni.h, use it directly
       (works when host == target, or when a cross-targeting JDK is installed).
    2. Otherwise download the Temurin JDK for the target and cache the
       extracted include/ in /tmp/femtolz4-jni-<target>/.
    """
    java_home = os.environ.get("JAVA_HOME", "")
    if java_home:
        inc = Path(java_home) / "include"
        if (inc / "jni.h").exists():
            # For cross-compilation to linux we still need the linux jni_md.h.
            # The macOS JAVA_HOME won't have it, so fall through to the download.
            platform_subdir = "linux" if "linux" in target else "darwin"
            if (inc / platform_subdir / "jni_md.h").exists():
                return inc

    cache_dir = Path(tempfile.gettempdir()) / f"femtolz4-jni-{target}"
    jni_h = cache_dir / "jni.h"
    if jni_h.exists():
        print(f"  (using cached JNI headers from {cache_dir})")
        return cache_dir

    url, prefix = TEMURIN_HEADERS[target]
    print(f"  Downloading JNI headers for {target} …")
    with tempfile.NamedTemporaryFile(suffix=".tar.gz", delete=False) as tmp:
        urllib.request.urlretrieve(url, tmp.name)
        tarball = tmp.name

    cache_dir.mkdir(parents=True, exist_ok=True)
    with tarfile.open(tarball) as tf:
        for member in tf.getmembers():
            if not member.name.startswith(prefix + "/"):
                continue
            rel = member.name[len(prefix) + 1:]
            if not rel:
                continue
            dest = cache_dir / rel
            if member.isdir():
                dest.mkdir(parents=True, exist_ok=True)
            else:
                dest.parent.mkdir(parents=True, exist_ok=True)
                with tf.extractfile(member) as src_f, open(dest, "wb") as dst_f:
                    shutil.copyfileobj(src_f, dst_f)
    Path(tarball).unlink(missing_ok=True)
    print(f"  JNI headers extracted to {cache_dir}")
    return cache_dir


# ── Per-target build functions ──────────────────────────────────────────────────

def build_darwin_aarch64() -> None:
    out = NATIVE_OUT / "darwin-aarch64" / "libfemtolz4.dylib"
    out.parent.mkdir(parents=True, exist_ok=True)

    inc = find_java_include("darwin-aarch64")
    # clang on macOS knows darwin/jni_md.h via the SDK; pass the platform subdir too
    extra_inc = [f"-I{inc / 'darwin'}"] if (inc / "darwin").exists() else []

    run([
        "clang", "-O2", "-shared", "-fPIC",
        f"-I{inc}", *extra_inc,
        f"-I{NATIVE_SRC}",
        str(NATIVE_SRC / "femtolz4_jni.c"),
        "-dynamiclib",
        "-o", str(out),
    ])
    run(["strip", "-x", str(out)])
    print(f"  → {out}  ({out.stat().st_size // 1024} KB)")


def build_linux_amd64_native() -> None:
    out = NATIVE_OUT / "linux-amd64" / "libfemtolz4.so"
    out.parent.mkdir(parents=True, exist_ok=True)

    inc = find_java_include("linux-amd64")
    extra_inc = [f"-I{inc / 'linux'}"] if (inc / "linux").exists() else []

    cc = shutil.which("gcc") or shutil.which("clang") or "gcc"
    run([
        cc, "-O3",
        "-mavx2", "-mfma", "-mbmi", "-mbmi2", "-mpopcnt",
        "-shared", "-fPIC",
        f"-I{inc}", *extra_inc,
        f"-I{NATIVE_SRC}",
        str(NATIVE_SRC / "femtolz4_jni.c"),
        "-o", str(out),
    ])
    strip = shutil.which("strip") or "strip"
    run([strip, "--strip-unneeded", str(out)])
    print(f"  → {out}  ({out.stat().st_size // 1024} KB)")


def build_linux_amd64_cross() -> None:
    """Cross-compile linux-amd64 from macOS using musl-cross."""
    cross_cc = shutil.which("x86_64-linux-musl-gcc")
    if not cross_cc:
        print("  ERROR: x86_64-linux-musl-gcc not found.")
        print("  Install with:  brew install FiloSottile/musl-cross/musl-cross")
        sys.exit(1)

    out = NATIVE_OUT / "linux-amd64" / "libfemtolz4.so"
    out.parent.mkdir(parents=True, exist_ok=True)

    inc = find_java_include("linux-amd64")
    extra_inc = [f"-I{inc / 'linux'}"] if (inc / "linux").exists() else []

    run([
        cross_cc, "-O3",
        "-mavx2", "-mfma", "-mbmi", "-mbmi2", "-mpopcnt",
        "-shared", "-fPIC",
        f"-I{inc}", *extra_inc,
        f"-I{NATIVE_SRC}",
        str(NATIVE_SRC / "femtolz4_jni.c"),
        # Do not link against musl libc: the JVM process provides glibc, so
        # any libc symbols we reference (memcpy etc.) resolve from the JVM's
        # glibc at dlopen time.  This keeps the .so free of any libc dependency
        # and works on both glibc and musl Linux systems.
        "-nostdlib", "-static-libgcc",
        "-o", str(out),
    ])
    # macOS strip cannot parse ELF; use llvm-strip or accept the larger binary
    llvm_strip = shutil.which("llvm-strip") or shutil.which("x86_64-linux-musl-strip")
    if llvm_strip:
        run([llvm_strip, "--strip-unneeded", str(out)])
    else:
        print("  (strip skipped — llvm-strip / musl-strip not found)")
    print(f"  → {out}  ({out.stat().st_size // 1024} KB)")


# ── Target registry ─────────────────────────────────────────────────────────────

def default_targets() -> list[str]:
    os_  = host_os()
    arch = host_arch()
    if os_ == "macos":
        return ["darwin-aarch64", "linux-amd64"]
    if os_ == "linux" and arch == "amd64":
        return ["linux-amd64"]
    print(f"No default targets defined for {os_}/{arch}. Pass targets explicitly.")
    return []


TARGETS = {
    # (host_os, target)  ->  build function
    ("macos",  "darwin-aarch64"): build_darwin_aarch64,
    ("macos",  "linux-amd64"):    build_linux_amd64_cross,
    ("linux",  "linux-amd64"):    build_linux_amd64_native,
}


# ── CLI ─────────────────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("targets", nargs="*",
                        help="Targets to build (default: all for this host)")
    parser.add_argument("--list", action="store_true",
                        help="List available targets for this host and exit")
    args = parser.parse_args()

    os_ = host_os()
    available = [t for (h, t) in TARGETS if h == os_]

    if args.list:
        print("Available targets on this host:")
        for t in available:
            print(f"  {t}")
        return

    targets = args.targets or default_targets()
    if not targets:
        sys.exit(1)

    unknown = [t for t in targets if t not in available]
    if unknown:
        print(f"ERROR: unsupported target(s) on {os_}: {', '.join(unknown)}")
        print(f"Available: {', '.join(available)}")
        sys.exit(1)

    os.chdir(SCRIPT_DIR)
    for target in targets:
        print(f"\nBuilding {target} …")
        TARGETS[(os_, target)]()

    print("\nAll targets built successfully.")


if __name__ == "__main__":
    main()
