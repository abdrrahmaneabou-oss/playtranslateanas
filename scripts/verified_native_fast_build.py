#!/usr/bin/env python3
"""Use only bit-identical ARM64 libraries from verified GitHub Actions #11.

This never guesses from the presence of an APK. A fast build is eligible only
while every pinned native source/config blob and both native gitlink revisions
match the successful 2026-11-21 debug build. Any modification forces a full
native build; the script refuses to extract or validate stale objects.
"""
import hashlib
import os
import pathlib
import subprocess
import sys
import zipfile

BASELINE_RUN = "https://github.com/abdrrahmaneabou-oss/playtranslateanas/actions/runs/44934642215"
# git object IDs: stable across branches, independent of checkout timestamps.
PINNED_GIT_OBJECTS = {
    "mnn/third-party/MNN": "4f8b086d6f17f5482063dc530ffc33d78a085750",
    "bergamot/third-party/slimt": "a0a486cf573c862c2f74eb32f068fec561ba0a08",
    "mnn/src/main/cpp/CMakeLists.txt": "a8c63bd2f81bd6ff690fd601ded48b3fb743ecb7",
    "mnn/src/main/cpp/logging.h": "9fa096a104b4de4e9316ff19f6bdab158e911aa8",
    "mnn/src/main/cpp/mnn_chat.cpp": "242a96596426b9af3031d548820a477e820c0e76",
    "mnn/src/main/cpp/mnn_infer.cpp": "93a4a208a14f94d12975b1cd2e4a4f9acb11cfc9",
    "bergamot/src/main/cpp/CMakeLists.txt": "15c2977e765c0105e68fdda76e64041058f48d3f",
    "bergamot/src/main/cpp/bergamot_jni.cpp": "f07966ce3edf1ccb9e6ba692fbe08658fde0474b",
    "gradle/libs.versions.toml": "eb2c87dfdc3c89ab7c191b2268fb89b8c925871d",
    # These two versions differ from build #11 ONLY by the explicitly guarded
    # optional useVerifiedPrebuiltNative flag / conditional CMake declaration.
    "mnn/build.gradle.kts": "3b1f0820b08f600277ef52d9c479cd53039b4122",
    "bergamot/build.gradle.kts": "18efb4a78a41a24b2b2048c54f0ace8635926aea",
}

# From APK artifact run #11. The app's native ML Kit / OpenCV dependencies
# stay owned by their upstream AARs; copy only MNN/Bergamot + NDK shared STL.
NATIVE_HASHES = {
    "libMNN.so": "7ea33404574bff0a15ce34c59f6df9b7eb76e2d52ff07865c51e374d4af64e03",
    "libMNN_Express.so": "85b371e30c5eb939d5efd6a9ce4fb425d210dff4cf06a423fb9adceded4e4ae0",
    "libllm.so": "db14938fbbb7b123f948f617c922b9ed75fb5b8754ca685fd8865ceae57965f8",
    "libmnn-chat.so": "3e8fe11ca2f1cfb5e4f87fe04a50cda2e46c52b0d05ec953e2042028ad5a4165",
    "libc++_shared.so": "4397241b4bd20a8e579bfb41d21107857e12985f6a01ca0c2a5f83380d1270b4",
    "libbergamot_jni.so": "469223e2071dfb7c50817cad98093a392251e4c28bcb8ed56f00be16e6188654",
}
MNN = set(NATIVE_HASHES) - {"libbergamot_jni.so"}

def object_at_head(path):
    output = subprocess.check_output(["git", "ls-tree", "HEAD", "--", path], text=True).strip()
    return output.split()[2] if output else None

def eligible():
    return [(name, expected, object_at_head(name))
            for name, expected in PINNED_GIT_OBJECTS.items()
            if object_at_head(name) != expected]

def sha256(data):
    return hashlib.sha256(data).hexdigest()

def output(name, value):
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf8") as dest:
            print(f"{name}={value}", file=dest)

def check():
    mismatches = eligible()
    if mismatches:
        print("Native baseline differs; will compile C++ from the new sources:")
        for name, expected, actual in mismatches:
            print(f"  {name}: expected {expected}, got {actual}")
    else:
        print("Exact known-good MNN/Bergamot native source and build config confirmed.")
        print("Eligible for preverified ARM64 binaries from", BASELINE_RUN)
    output("eligible", "false" if mismatches else "true")

def extract(apkfile):
    if eligible():
        raise RuntimeError("Source changed; prebuilt libraries are forbidden")
    with zipfile.ZipFile(apkfile) as apk:
        for name, expected in NATIVE_HASHES.items():
            data = apk.read(f"lib/arm64-v8a/{name}")
            actual = sha256(data)
            if actual != expected:
                raise RuntimeError(f"Untrusted native library {name}: {actual} != {expected}")
            group = "mnn" if name in MNN else "bergamot"
            path = pathlib.Path("fast-native") / group / "arm64-v8a" / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            print(f"Staged verified {name} ({len(data)} bytes)")
    print("All six original native runtime libraries verified by SHA-256.")

def verify_cache():
    if eligible():
        raise RuntimeError("Native source/config no longer matches trusted baseline")
    for name, expected in NATIVE_HASHES.items():
        group = "mnn" if name in MNN else "bergamot"
        path = pathlib.Path("fast-native") / group / "arm64-v8a" / name
        if not path.is_file() or sha256(path.read_bytes()) != expected:
            raise RuntimeError("Native cache absent or checksum mismatch: " + str(path))
    print("Verified all six cached libraries from successful run #11")

def verify(apkfile, check_baseline):
    with zipfile.ZipFile(apkfile) as apk:
        names = set(apk.namelist())
        if not any(s.startswith("lib/arm64-v8a/") for s in names):
            raise RuntimeError("ARM64 native slice missing")
        if any(s.startswith("lib/armeabi-v7a/") for s in names):
            raise RuntimeError("Unexpected 32-bit libraries")
        for name, expected in NATIVE_HASHES.items():
            path = f"lib/arm64-v8a/{name}"
            if path not in names:
                raise RuntimeError("Missing required native runtime: " + name)
            if check_baseline:
                actual = sha256(apk.read(path))
                if actual != expected:
                    raise RuntimeError(f"Native runtime changed unexpectedly: {name}")
            print("OK:", name)
    print("Validated ARM64 APK and its required native runtime binaries.")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit("usage: verified_native_fast_build.py check|extract|verify <APK> [--baseline]")
    if sys.argv[1] == "check":
        check()
    elif sys.argv[1] == "verify-cache":
        verify_cache()
    elif sys.argv[1] == "extract" and len(sys.argv) == 3:
        extract(sys.argv[2])
    elif sys.argv[1] == "verify" and len(sys.argv) >= 3:
        verify(sys.argv[2], "--baseline" in sys.argv[3:])
    else:
        raise SystemExit("invalid command")
