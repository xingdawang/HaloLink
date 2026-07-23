#!/bin/bash
cd "$(dirname "$0")" || exit 1

finish_with_error() {
  code=$?
  if [ "$code" -ne 0 ]; then
    echo
    echo "HaloLink Bridge did not start successfully (exit code $code)."
    echo "Copy the messages above if you need help diagnosing it."
    echo
    read -r -p "Press Return to close this window..." _
  fi
  exit "$code"
}
trap finish_with_error EXIT

if ! command -v python3 >/dev/null 2>&1; then
  echo "Python 3 was not found. Install Python 3 and run this file again."
  exit 1
fi

if [ ! -d .venv ]; then
  echo "Creating HaloLink Python environment..."
  python3 -m venv .venv
fi
source .venv/bin/activate
if [ ! -f .venv/.halolink_deps_installed ]; then
  python -m pip install --upgrade pip
  python -m pip install -r requirements.txt
  touch .venv/.halolink_deps_installed
fi

python bridge.py
