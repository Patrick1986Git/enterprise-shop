import copy
import importlib.util
import json
import re
import struct
import subprocess
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "validate-gosu-provenance.py"
SPEC = importlib.util.spec_from_file_location("gosu_provenance", SCRIPT)
POLICY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(POLICY)

BUILDINFO = """/evidence/gosu: go1.26.9
\tpath\tgithub.com/tianon/gosu
\tmod\tgithub.com/tianon/gosu\t(devel)\t
\tdep\tgithub.com/moby/sys/user\tv0.4.1\th1:RgjRlaDKi/Xmyrz4t8lyzXT6v2ooFeO/7xtchmhVWE0=
\tdep\tgolang.org/x/sys\tv0.1.0\th1:kunALQeHf1/185U1i0GOB/fy1IPRDDpuoOOqRReG57U=
\tbuild\t-buildmode=exe
\tbuild\t-compiler=gc
\tbuild\t-trimpath=true
\tbuild\tCGO_ENABLED=0
\tbuild\tGOARCH=amd64
\tbuild\tGOOS=linux
\tbuild\tGOAMD64=v1
\tbuild\tDefaultGODEBUG=asynctimerchan=1
"""


class GosuRepositoryReceiptTest(unittest.TestCase):
    def test_checked_in_lockfiles_match_receipt_at_the_validator_default_path(self):
        candidate = json.loads(POLICY.CANDIDATE.read_text())
        POLICY.validate_candidate(candidate)
        self.assertEqual(POLICY.REVIEWED, POLICY.ROOT / "docker/postgres/gosu")
        for name, digest in candidate["lockfiles"].items():
            with self.subTest(name=name):
                self.assertEqual(POLICY.sha256(POLICY.regular_bytes(POLICY.REVIEWED / name)), digest)

    def test_actual_dockerfile_binds_reviewed_builder_source_and_executable(self):
        candidate = json.loads(POLICY.CANDIDATE.read_text())
        dockerfile = (POLICY.ROOT / "docker/postgres/Dockerfile").read_text()
        self.assertEqual(re.findall(r"^FROM (\S+) AS gosu-builder$", dockerfile, re.MULTILINE),
                         [candidate["builder"]["image"]])
        self.assertEqual(re.findall(r"^ADD --checksum=sha256:([0-9a-f]{64}) (\S+) /tmp/gosu-source.tar.gz$",
                                    dockerfile, re.MULTILINE),
                         [(candidate["source"]["archive_sha256"], candidate["source"]["archive_url"])])
        self.assertIn("COPY gosu/go.mod gosu/go.sum ./", dockerfile)
        self.assertIn('test "$(go env GOVERSION)" = ' + candidate["builder"]["go_version"], dockerfile)
        self.assertEqual(dockerfile.count("go build " + candidate["build_flags"] + " -o /out/gosu"), 2)
        self.assertIn("cmp /out/gosu /out/gosu.repeat", dockerfile)
        self.assertIn("'" + candidate["binary"]["sha256"] + "  /out/gosu'", dockerfile)
        self.assertIn("sha256sum -c /out/gosu.sha256", dockerfile)
        self.assertIn("COPY --from=gosu-builder --chmod=0755 /out/gosu /usr/local/bin/gosu", dockerfile)


class GosuProvenanceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "source"
        self.reviewed = self.root / "reviewed"
        self.evidence = self.root / "evidence"
        for directory in (self.source, self.reviewed, self.evidence):
            directory.mkdir()
        self.candidate = copy.deepcopy(json.loads(POLICY.CANDIDATE.read_text()))
        (self.source / "main.go").write_text("package main\nfunc main() {}\n")
        (self.source / "setup-user.go").write_text("package main\n")
        (self.source / ".gitignore").write_text("ignored.go\n")
        (self.source / ".dockerignore").symlink_to(".gitignore")
        (self.source / "govulncheck-with-excludes.sh").write_text("#!/bin/sh\nexit 0\n")
        (self.source / "govulncheck-with-excludes.sh").chmod(0o755)
        (self.source / "go.mod").write_text("module github.com/tianon/gosu\nrequire github.com/moby/sys/user v0.1.0\n")
        (self.source / "go.sum").write_text("upstream release checksum fixture\n")
        self.git("init", "--quiet")
        self.git("add", ".")
        self.commit("reviewed upstream fixture")
        self.candidate["source"]["commit"] = self.git("rev-parse", "HEAD").strip()
        self.candidate["source"]["archive_url"] = (
            "https://codeload.github.com/tianon/gosu/tar.gz/" + self.candidate["source"]["commit"]
        )
        lockfiles = {
            "go.mod": "module github.com/tianon/gosu\nrequire github.com/moby/sys/user v0.4.1\n",
            "go.sum": "github.com/moby/sys/user v0.4.1 h1:RgjRlaDKi/Xmyrz4t8lyzXT6v2ooFeO/7xtchmhVWE0=\n",
        }
        for name, value in lockfiles.items():
            (self.reviewed / name).write_text(value)
            (self.source / name).write_text(value)
            self.candidate["lockfiles"][name] = POLICY.sha256(value.encode())
        binary = bytearray(64)
        binary[:7] = b"\x7fELF\x02\x01\x01"
        struct.pack_into("<HHI", binary, 16, 2, 62, 1)
        self.payload = bytes(binary)
        (self.evidence / "gosu").write_bytes(self.payload)
        (self.evidence / "buildinfo.txt").write_text(BUILDINFO)
        self.candidate["binary"]["sha256"] = POLICY.sha256(self.payload)

    def git(self, *args):
        return subprocess.run(["git", "-C", str(self.source), *args], check=True,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True).stdout

    def commit(self, message):
        self.git("-c", "user.name=Security fixture", "-c", "user.email=security@example.invalid",
                 "commit", "--quiet", "-m", message)

    def validate(self):
        POLICY.validate(self.candidate, self.evidence, self.source, self.reviewed)

    def test_reviewed_binary_and_only_patched_lockfiles_pass(self):
        self.validate()

    def test_executable_digest_change_fails(self):
        (self.evidence / "gosu").write_bytes(self.payload + b"different executable")
        with self.assertRaisesRegex(ValueError, "digest mismatch"):
            self.validate()

    def test_wrong_elf_class_endianness_and_machine_fail_even_with_matching_digest(self):
        for offset, value in ((4, 1), (5, 2), (18, 183)):
            with self.subTest(offset=offset):
                payload = bytearray(self.payload)
                payload[offset] = value
                (self.evidence / "gosu").write_bytes(payload)
                self.candidate["binary"]["sha256"] = POLICY.sha256(payload)
                with self.assertRaisesRegex(ValueError, "ELF64|Linux/amd64"):
                    self.validate()

    def test_stale_user_dependency_metadata_fails(self):
        (self.evidence / "buildinfo.txt").write_text(BUILDINFO.replace("v0.4.1", "v0.1.0"))
        with self.assertRaisesRegex(ValueError, "dependency identities"):
            self.validate()

    def test_changed_dependency_checksum_fails(self):
        (self.evidence / "buildinfo.txt").write_text(BUILDINFO.replace("RgjRlaDK", "WgjRlaDK"))
        with self.assertRaisesRegex(ValueError, "dependency identities"):
            self.validate()

    def test_extra_missing_and_duplicate_dependencies_fail(self):
        dependency = "\tdep\texample.invalid/extra\tv1.0.0\th1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\n"
        variants = (
            BUILDINFO + dependency,
            "\n".join(line for line in BUILDINFO.splitlines() if "golang.org/x/sys" not in line) + "\n",
            BUILDINFO + next(line for line in BUILDINFO.splitlines() if "golang.org/x/sys" in line) + "\n",
        )
        for buildinfo in variants:
            with self.subTest(buildinfo=buildinfo):
                (self.evidence / "buildinfo.txt").write_text(buildinfo)
                with self.assertRaises(ValueError):
                    self.validate()

    def test_module_replacement_and_fake_vcs_claims_fail(self):
        for record in ("\t=>\texample.invalid/replacement\tv1.0.0\th1:checksum\n",
                       "\tbuild\tvcs.revision=6456aaa0f3c854d199d0f037f068eb97515b7513\n"):
            with self.subTest(record=record):
                (self.evidence / "buildinfo.txt").write_text(BUILDINFO + record)
                with self.assertRaisesRegex(ValueError, "replacement|VCS"):
                    self.validate()

    def test_compiler_main_module_and_build_flag_changes_fail(self):
        for old, new in (("go1.26.9", "go1.24.6"), ("(devel)", "v1.19.0"),
                         ("CGO_ENABLED=0", "CGO_ENABLED=1"), ("GOARCH=amd64", "GOARCH=arm64"),
                         ("-compiler=gc", "-compiler=gccgo"), ("-trimpath=true", "-trimpath=false")):
            with self.subTest(old=old):
                (self.evidence / "buildinfo.txt").write_text(BUILDINFO.replace(old, new))
                with self.assertRaises(ValueError):
                    self.validate()

    def test_changed_source_bytes_fail_even_when_git_index_assumes_unchanged(self):
        self.git("update-index", "--assume-unchanged", "main.go")
        (self.source / "main.go").write_text("package main\nfunc main() { panic(\"changed\") }\n")
        with self.assertRaisesRegex(ValueError, "source bytes changed: main.go"):
            self.validate()

    def test_changed_source_commit_fails(self):
        self.git("add", "go.mod", "go.sum")
        self.commit("unexpected source HEAD")
        with self.assertRaisesRegex(ValueError, "immutable commit"):
            self.validate()

    def test_untracked_ignored_go_source_is_rejected(self):
        (self.source / "ignored.go").write_text("package main\nfunc init() { panic(\"unreviewed\") }\n")
        with self.assertRaisesRegex(ValueError, "Untracked gosu source"):
            self.validate()

    def test_source_wrapper_mode_and_symlink_changes_are_rejected(self):
        (self.source / "govulncheck-with-excludes.sh").chmod(0o644)
        with self.assertRaisesRegex(ValueError, "executable mode"):
            self.validate()
        (self.source / "govulncheck-with-excludes.sh").chmod(0o755)
        (self.source / ".dockerignore").unlink()
        (self.source / ".dockerignore").symlink_to("main.go")
        with self.assertRaisesRegex(ValueError, "source symlink changed"):
            self.validate()

    def test_source_or_reviewed_lockfile_mutation_fails(self):
        for directory in (self.source, self.reviewed):
            with self.subTest(directory=directory):
                path = directory / "go.sum"
                original = path.read_bytes()
                path.write_bytes(original + b"unreviewed checksum\n")
                with self.assertRaisesRegex(ValueError, "lockfile digest mismatch"):
                    self.validate()
                path.write_bytes(original)

    def test_lockfile_symlink_is_rejected(self):
        (self.source / "go.mod").unlink()
        (self.source / "go.mod").symlink_to(self.reviewed / "go.mod")
        with self.assertRaisesRegex(ValueError, "Expected regular file"):
            self.validate()

    def test_stale_dependency_receipt_and_unpinned_builder_fail(self):
        self.candidate["binary"]["dependencies"]["github.com/moby/sys/user"]["version"] = "v0.1.0"
        with self.assertRaisesRegex(ValueError, "patched user parser"):
            self.validate()
        self.candidate["binary"]["dependencies"]["github.com/moby/sys/user"]["version"] = "v0.4.1"
        self.candidate["builder"]["image"] = "golang:latest"
        with self.assertRaisesRegex(ValueError, "immutable official image"):
            self.validate()


if __name__ == "__main__":
    unittest.main()
