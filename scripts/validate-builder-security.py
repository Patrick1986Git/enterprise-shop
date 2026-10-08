#!/usr/bin/env python3
"""Fail closed on unreviewed root Dockerfile security boundaries."""

import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
REVIEWED_STAGES = [
    ('eclipse-temurin:21-jdk-jammy', 'builder'),
    ('eclipse-temurin:21-jre-jammy', 'runtime'),
]


def validate_stages(contents):
    stages = []
    for line in contents.splitlines():
        if re.match(r'^\s*FROM\b', line, re.IGNORECASE):
            match = re.fullmatch(r'\s*FROM\s+(\S+)\s+AS\s+(\S+)\s*', line, re.IGNORECASE)
            if not match:
                raise ValueError('Unreviewed Dockerfile FROM instruction')
            stages.append((match[1], match[2]))
    if stages != REVIEWED_STAGES:
        raise ValueError('Root Dockerfile stages require an explicit builder security policy review')


if __name__ == '__main__':
    validate_stages((ROOT / 'Dockerfile').read_text())
