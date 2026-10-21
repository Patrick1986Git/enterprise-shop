#!/usr/bin/env python3
"""Exercise gosu inside a final PostgreSQL image using only disposable resources.

This is a runtime contract check, not a substitute for provenance, SBOM scans,
govulncheck or the repository's full Flyway/Testcontainers/restore suites.
"""
import argparse
import hashlib
import json
import signal
import subprocess
import tempfile
import time
import uuid
from pathlib import Path


LABEL = "enterprise-shop.gosu-regression"
MARKER = "GOSU_UNEXPECTED_COMMAND_EXECUTION"
PROBE = ('printf "uid=%s\\ngid=%s\\ngroups=%s\\nhome=%s\\npid=%s\\n" '
         '"$(id -u)" "$(id -g)" "$(id -G)" "$HOME" "$$"')
ACCOUNT_RECORDS = ("root:x:0:0:root:/root:/bin/sh\n"
                   "fixture:x:12345:23456:fixture:/tmp/fixture:/bin/sh\n")
GROUP_RECORDS = ("root:x:0:\nfixturegroup:x:23456:\n"
                 "alpha:x:12346:fixture\nbeta:x:12347:fixture\n")
MALFORMED_ACCOUNT_RECORD = "x" * (128 * 1024)


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def validate_fixture_records(records, field_count):
    lines = records.splitlines()
    require(lines, "synthetic fixture records must not be empty")
    for line in lines:
        if field_count == 7 and line == MALFORMED_ACCOUNT_RECORD:
            continue
        fields = line.split(":")
        require(len(fields) == field_count and fields[1] == "x",
                "synthetic fixture records require the expected fields and x placeholder")


class RuntimeChecks:
    def __init__(self, image):
        self.image = image
        self.token = uuid.uuid4().hex
        self.created = []
        self.results = []

    def docker(self, *args, timeout=30, check=True, data=None, binary=False):
        result = subprocess.run(["docker", *args], input=data, capture_output=True,
                                text=not binary, timeout=timeout)
        if check and result.returncode:
            stderr = result.stderr.decode(errors="replace") if binary else result.stderr
            raise RuntimeError(f"docker {args[0]} failed ({result.returncode}): {stderr[:3000]}")
        return result

    def inspect(self, container):
        return json.loads(self.docker("inspect", container).stdout)[0]

    def create(self, name, command, *, entrypoint="/usr/local/bin/gosu", postgres=False, fixtures=None):
        args = ["create", "--name", f"gosu-regression-{self.token[:12]}-{name}",
                "--label", f"{LABEL}={self.token}", "--platform", "linux/amd64",
                "--user", "0:0", "--network", "none", "--pids-limit", "128",
                "--memory", "512m" if postgres else "256m", "--cpus", "1",
                "--security-opt", "no-new-privileges:true"]
        if postgres:
            # The image creates an anonymous PGDATA volume. It survives restart
            # and is removed with this exact created container by rm --volumes.
            args += ["--env", "POSTGRES_PASSWORD=synthetic-gosu-regression-password",
                     "--env", "POSTGRES_USER=gosu_test", "--env", "POSTGRES_DB=gosu_source"]
        else:
            args += ["--cap-drop", "ALL", "--cap-add", "SETUID", "--cap-add", "SETGID"]
        if fixtures is not None:
            require(entrypoint == "/usr/local/bin/gosu" and not postgres,
                    "account fixtures are only supported for disposable gosu probes")
            args += ["--mount", f"type=bind,source={fixtures.resolve()},target=/account-fixtures,readonly"]
            # Docker resolves its startup user before the container starts. Keep
            # the image's valid account files until that boundary has completed.
            # Pass the gosu arguments positionally, never as shell source.
            entrypoint = "/bin/sh"
            command = ["-ec", "cp /account-fixtures/passwd /etc/passwd; "
                       "cp /account-fixtures/group /etc/group; "
                       "exec /usr/local/bin/gosu \"$@\"", "gosu-account-fixture", *command]
        if entrypoint is not None:
            args += ["--entrypoint", entrypoint]
        result = self.docker(*args, self.image, *command)
        container = result.stdout.strip()
        self.created.append(container)
        require(self.inspect(container)["Config"]["Labels"].get(LABEL) == self.token,
                "created container label mismatch")
        return container

    def fixture(self, directory, account_records=ACCOUNT_RECORDS, group_records=GROUP_RECORDS):
        validate_fixture_records(account_records, 7)
        validate_fixture_records(group_records, 4)
        directory.mkdir(mode=0o755)
        directory.chmod(0o755)
        for name, content in (("passwd", account_records), ("group", group_records)):
            path = directory / name
            path.write_text(content)
            path.chmod(0o644)
        return directory

    def run(self, name, command, *, directory=None, account_records=ACCOUNT_RECORDS, group_records=GROUP_RECORDS,
            entrypoint="/usr/local/bin/gosu", expected=0):
        fixtures = self.fixture(directory / name, account_records, group_records) if directory is not None else None
        container = self.create(name, command, entrypoint=entrypoint, fixtures=fixtures)
        start = time.monotonic()
        result = self.docker("start", "--attach", container, check=False)
        elapsed = time.monotonic() - start
        state = self.inspect(container)["State"]
        require(not state["Running"] and not state["OOMKilled"], f"{name}: running/OOM state {state}")
        require(state["ExitCode"] == expected and result.returncode == expected,
                f"{name}: expected {expected}, got {state['ExitCode']}: {result.stderr[:1000]}")
        self.results.append({"test": name, "status": "passed", "exit_code": expected,
                             "seconds": round(elapsed, 3), "stdout": result.stdout[:2000],
                             "stderr": result.stderr[:2000]})
        return result

    def identity(self, name, spec, uid, gid, groups, home, directory):
        result = self.run(name, [spec, "/bin/sh", "-ec", PROBE], directory=directory)
        actual = dict(line.split("=", 1) for line in result.stdout.splitlines())
        require(actual["uid"] == str(uid) and actual["gid"] == str(gid), f"{name}: {actual}")
        require(set(map(int, actual["groups"].split())) == set(groups), f"{name}: {actual}")
        require(actual["home"] == home and actual["pid"] == "1", f"{name}: {actual}")

    def bounded_error(self, name, account_records, group_records, error, directory, spec="fixture"):
        result = self.run(name, [spec, "/bin/sh", "-ec", f"printf {MARKER}"],
                          directory=directory, account_records=account_records, group_records=group_records, expected=1)
        require(MARKER not in result.stdout + result.stderr, f"{name}: command executed")
        require(error in result.stderr, f"{name}: missing {error!r}: {result.stderr[:1000]}")

    def privilege_contracts(self, directory):
        base = self.run("image-identity", ["-ec", "id -u postgres; id -g postgres; id -G postgres"],
                        entrypoint="/bin/sh")
        uid, gid, groups = base.stdout.strip().splitlines()
        self.postgres_identity = (uid, gid, set(groups.split()))
        named = self.run("inherited-postgres-account", ["postgres", "/bin/sh", "-ec", PROBE])
        actual = dict(line.split("=", 1) for line in named.stdout.splitlines())
        require(actual["uid"] == uid and actual["gid"] == gid and actual["pid"] == "1",
                f"postgres account transition mismatch: {actual}")
        require(set(actual["groups"].split()) == set(groups.split()), "postgres groups mismatch")
        self.identity("named-user", "fixture", 12345, 23456, [12346, 12347, 23456], "/tmp/fixture", directory)
        self.identity("numeric-existing-user", "12345", 12345, 23456,
                      [12346, 12347, 23456], "/tmp/fixture", directory)
        self.identity("named-group", "fixture:alpha", 12345, 12346, [12346], "/tmp/fixture", directory)
        self.identity("numeric-user-and-group", "12345:23456", 12345, 23456,
                      [23456], "/tmp/fixture", directory)
        self.identity("unmapped-numeric-ids", "34567:45678", 34567, 45678, [45678], "/", directory)
        self.run("exec-exit-status", ["fixture", "/bin/sh", "-ec", "exit 37"],
                 directory=directory, expected=37)
        self.bounded_error("unknown-user", ACCOUNT_RECORDS, GROUP_RECORDS, "no matching entries in passwd file",
                           directory, "unmapped_fixture_name")

    def malformed_accounts(self, directory):
        # >1 MiB line: exercises ParseGroupFilter's new Scanner ceiling.
        self.bounded_error("group-line-limit", ACCOUNT_RECORDS,
                           GROUP_RECORDS + "huge:x:12348:" + "x" * (1024 * 1024) + "\n",
                           "token too long", directory)
        # >64 KiB passwd line: existing Scanner limit, retained by the update.
        self.bounded_error("passwd-line-limit", ACCOUNT_RECORDS + MALFORMED_ACCOUNT_RECORD + "\n", GROUP_RECORDS,
                           "token too long", directory)
        # Short valid lines distinguish the 10 MiB read ceiling from token limits.
        group_record = "other:x:23457:notfixture\n"
        oversized_group_records = GROUP_RECORDS + group_record * ((10 * 1024 * 1024 // len(group_record)) + 1)
        self.bounded_error("group-file-limit", ACCOUNT_RECORDS, oversized_group_records,
                           "file exceeds 10485760 bytes", directory)
        account_record = "other:x:23457:23457:other:/tmp:/bin/sh\n"
        oversized_account_records = ACCOUNT_RECORDS + account_record * ((10 * 1024 * 1024 // len(account_record)) + 1)
        self.bounded_error("passwd-file-limit", oversized_account_records, GROUP_RECORDS,
                           "file exceeds 10485760 bytes", directory)

    def signal_contract(self):
        script = ('trap \'printf "term-received\\n"; exit 42\' TERM; '
                  'printf "ready pid=%s\\n" "$$"; while :; do sleep 1 & wait "$!"; done')
        container = self.create("signal", ["postgres", "/bin/sh", "-ec", script])
        self.docker("start", container)
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            logs = self.docker("logs", container).stdout
            if "ready pid=1" in logs:
                break
            require(self.inspect(container)["State"]["Running"], f"signal target exited: {logs}")
            time.sleep(0.2)
        else:
            raise AssertionError("signal target did not become PID 1")
        self.docker("kill", "--signal", "TERM", container)
        status = self.docker("wait", container, timeout=15).stdout.strip()
        logs = self.docker("logs", container).stdout
        require(status == "42" and "term-received" in logs, f"signal/exit contract: {status}, {logs}")
        self.results.append({"test": "pid1-exec-signal", "status": "passed", "exit_code": 42, "stdout": logs})

    def ready(self, container):
        deadline = time.monotonic() + 90
        command = ['test "$(cat /proc/1/comm)" = postgres && '
                   'pg_isready -U gosu_test -d gosu_source >/dev/null']
        while time.monotonic() < deadline:
            result = self.docker("exec", container, "/bin/sh", "-ec", *command, check=False)
            if result.returncode == 0:
                return
            state = self.inspect(container)["State"]
            require(state["Running"] and not state["OOMKilled"],
                    "PostgreSQL exited before readiness: " + self.docker("logs", container).stderr[-3000:])
            time.sleep(0.5)
        raise AssertionError("final PID 1 PostgreSQL server did not become ready")

    def sql(self, container, database, statement):
        return self.docker("exec", container, "psql", "-X", "--username", "gosu_test",
                           "--dbname", database, "--no-align", "--tuples-only",
                           "--set", "ON_ERROR_STOP=1", "--command", statement).stdout.strip()

    def postgres_contract(self):
        container = self.create("postgres", ["postgres", "-c", "listen_addresses="],
                                entrypoint=None, postgres=True)
        self.docker("start", container)
        self.ready(container)
        state = self.inspect(container)
        mounts = state["Mounts"]
        require(all(m["Type"] == "volume" for m in mounts), "unexpected PostgreSQL host mount")
        require(any(m["Destination"] == "/var/lib/postgresql" for m in mounts), "missing PG18 data mount")
        status = self.docker("exec", container, "cat", "/proc/1/status").stdout
        uid = next(line for line in status.splitlines() if line.startswith("Uid:"))
        gid = next(line for line in status.splitlines() if line.startswith("Gid:"))
        groups = next(line for line in status.splitlines() if line.startswith("Groups:"))
        expected_uid, expected_gid, expected_groups = self.postgres_identity
        require(expected_uid != "0" and uid.split()[1:] == [expected_uid] * 4,
                f"PostgreSQL process UID mismatch: {uid}")
        require(gid.split()[1:] == [expected_gid] * 4, f"PostgreSQL process GID mismatch: {gid}")
        require(set(groups.split()[1:]) | {expected_gid} == expected_groups,
                f"PostgreSQL supplementary groups mismatch: {groups}")
        version = self.sql(container, "gosu_source", "SHOW server_version_num")
        require(180000 <= int(version) < 190000, f"expected PostgreSQL 18: {version}")
        self.sql(container, "gosu_source", "CREATE TABLE privilege_fixture (id integer PRIMARY KEY, value text NOT NULL); "
                 "INSERT INTO privilege_fixture VALUES (1, 'Zażółć gęślą jaźń'); "
                 "CREATE TEXT SEARCH DICTIONARY fixture_polish "
                 "(TEMPLATE = ispell, DictFile = polish, AffFile = polish, StopWords = polish);")
        self.docker("restart", "--time", "15", container, timeout=45)
        self.ready(container)
        expected = "1|Zażółć gęślą jaźń"
        require(self.sql(container, "gosu_source", "TABLE privilege_fixture") == expected,
                "data did not survive PostgreSQL restart")
        dump = self.docker("exec", container, "pg_dump", "--username", "gosu_test",
                           "--format", "custom", "gosu_source", binary=True).stdout
        require(dump.startswith(b"PGDMP"), "invalid custom-format dump")
        self.docker("exec", container, "createdb", "--username", "gosu_test", "gosu_restored")
        self.docker("exec", "--interactive", container, "pg_restore", "--username", "gosu_test",
                    "--dbname", "gosu_restored", "--exit-on-error", data=dump, binary=True)
        require(self.sql(container, "gosu_restored", "TABLE privilege_fixture") == expected,
                "restored table contents differ")
        require(self.sql(container, "gosu_restored", "SELECT pg_get_userbyid(relowner) FROM pg_class "
                         "WHERE oid='privilege_fixture'::regclass") == "gosu_test", "restore owner differs")
        require(self.sql(container, "gosu_restored", "SELECT count(*) FROM pg_ts_dict "
                         "WHERE dictname='fixture_polish'") == "1", "dictionary was not restored")
        self.docker("stop", "--time", "15", container, timeout=45)
        final = self.inspect(container)["State"]
        require(final["ExitCode"] == 0 and not final["OOMKilled"], "unclean PostgreSQL signal shutdown")
        self.results.append({"test": "postgres18-start-restart-dump-restore-stop", "status": "passed",
                             "server_version_num": version, "pid1_uid": uid,
                             "pid1_gid": gid, "pid1_groups": groups,
                             "dump_sha256": hashlib.sha256(dump).hexdigest(), "dump_bytes": len(dump),
                             "restored_owner": "gosu_test", "anonymous_volumes": mounts})

    def cleanup(self):
        errors = []
        for container in reversed(self.created):
            try:
                metadata = self.inspect(container)
                require(metadata["Config"]["Labels"].get(LABEL) == self.token,
                        "refusing cleanup of container without this invocation's label")
                self.docker("rm", "--force", "--volumes", container)
            except Exception as exc:
                errors.append(f"{container}: {exc}")
        return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    checks = RuntimeChecks(args.image)
    evidence = {"image": args.image, "invocation_label": f"{LABEL}={checks.token}", "status": "failed"}
    def interrupted(signum, frame):
        raise SystemExit(128 + signum)
    signal.signal(signal.SIGTERM, interrupted)
    try:
        identity = json.loads(checks.docker("image", "inspect", args.image).stdout)[0]
        require(identity["Os"] == "linux" and identity["Architecture"] == "amd64", "expected linux/amd64 image")
        evidence["image_id"] = identity["Id"]
        checks.image = identity["Id"]
        with tempfile.TemporaryDirectory(prefix="gosu-account-fixtures-") as temporary:
            Path(temporary).chmod(0o755)
            checks.privilege_contracts(Path(temporary))
            checks.malformed_accounts(Path(temporary))
            checks.signal_contract()
            checks.postgres_contract()
        evidence["status"] = "passed"
    except Exception as exc:
        evidence["error"] = f"{type(exc).__name__}: {exc}"
    finally:
        evidence["tests"] = checks.results
        evidence["cleanup_errors"] = checks.cleanup()
        if evidence["cleanup_errors"]:
            evidence["status"] = "failed"
        payload = json.dumps(evidence, indent=2, ensure_ascii=False) + "\n"
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(payload)
        print(payload, end="")
    return 0 if evidence["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
