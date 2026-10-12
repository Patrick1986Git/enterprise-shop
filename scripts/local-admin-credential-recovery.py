#!/usr/bin/env python3
"""Launch the approved Java terminal tool without inherited JVM or driver logging overrides."""
import os
from pathlib import Path
import subprocess
import sys


def main():
    root = Path(__file__).resolve().parents[1]
    jar = root / "target/enterprise-shop-0.0.1-SNAPSHOT.jar"
    if not sys.stdin.isatty() or not sys.stdout.isatty() or not sys.stderr.isatty() or not jar.is_file():
        print("A protected local terminal and verified application JAR are required", file=sys.stderr)
        return 1
    environment = {key: value for key, value in os.environ.items()
                   if key not in {"JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH"}
                   and not key.startswith("PG") and not key.startswith("LOADER_")}
    command = ["java", "-Dloader.main=com.company.shop.module.user.operator.LocalAdministratorRecoveryCommand",
               "-cp", str(jar), "org.springframework.boot.loader.launch.PropertiesLauncher", *sys.argv[1:]]
    try:
        return subprocess.run(command, env=environment, check=False).returncode
    except OSError:
        print("Local recovery launcher unavailable", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
