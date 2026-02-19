#!/usr/bin/env python3
import os
import shutil
import subprocess
import sys

ROOT        = os.path.dirname(os.path.dirname(__file__))
SRC_DIR     = os.path.join(ROOT, "scripts")
BUILD_DIR   = os.path.join(ROOT, "build")

# 1. Clean+recreate build/
if os.path.isdir(BUILD_DIR):
    shutil.rmtree(BUILD_DIR)
os.makedirs(BUILD_DIR, exist_ok=True)

# 2. pip-install dependencies into build/
subprocess.check_call([
    sys.executable, "-m", "pip", "install",
    "-r", os.path.join(SRC_DIR, "requirements.txt"),
    "-t", BUILD_DIR
])

# 3. Copy all the function code into build/
for fname in os.listdir(SRC_DIR):
    if fname.endswith(".py"):
        shutil.copy2(
            os.path.join(SRC_DIR, fname),
            BUILD_DIR
        )

print("Build complete:", BUILD_DIR)
