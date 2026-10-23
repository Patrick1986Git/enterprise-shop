#!/bin/sh
set -eu

if [ "$#" -lt 1 ]; then
    echo 'Missing Java certificate blocklist path' >&2
    exit 1
fi
blocklist=$1
shift
expected=2596bb6dda83c66d96c5a252d7b2ae1f24cc1ae3f1890223b4f3591a8d52bf10
if [ ! -f "$blocklist" ] || [ ! -r "$blocklist" ] \
    || ! actual=$(sha256sum < "$blocklist") \
    || [ "$actual" != "$expected  -" ]; then
    echo 'Java certificate blocklist verification failed; refusing to start' >&2
    exit 1
fi
if [ "$#" -gt 0 ]; then
    exec "$@"
fi
