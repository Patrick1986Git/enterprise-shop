import contextlib
import copy
import hashlib
import importlib.util
import io
import json
import os
import subprocess
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from test_github_api import TOKEN, limited, response


SCRIPT = Path(__file__).parents[1] / "verify-trivy-provenance.py"
SPEC = importlib.util.spec_from_file_location("verify_trivy_provenance", SCRIPT)
VERIFIER = importlib.util.module_from_spec(SPEC)
with patch.object(sys, "path", [str(SCRIPT.parent), *sys.path]):
    SPEC.loader.exec_module(VERIFIER)

VERSION = "0.75.0"
ASSET = f"trivy_{VERSION}_Linux-64bit.tar.gz"
RELEASE_URL = f"https://api.github.com/repos/aquasecurity/trivy/releases/tags/v{VERSION}"
ASSET_URL = f"https://github.com/aquasecurity/trivy/releases/download/v{VERSION}/{ASSET}"
CDN_URL = "https://release-assets.githubusercontent.com/fixture"
BINARY = b"synthetic executable bytes"
BINARY_SHA = hashlib.sha256(BINARY).hexdigest()


def archive_bytes(name="trivy"):
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w:gz") as archive:
        entry = tarfile.TarInfo(name)
        entry.size = len(BINARY)
        archive.addfile(entry, io.BytesIO(BINARY))
    return buffer.getvalue()


def attestation(source=VERIFIER.SOURCE_COMMIT):
    return [{"verificationResult": {"signature": {"certificate": {"sourceRepositoryDigest": source}}}}]


class TrivyProvenanceAcquisitionTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(self.enterContext(tempfile.TemporaryDirectory()))
        contract = self.root / ".github/security/auxiliary-container-scope.json"
        contract.parent.mkdir(parents=True)
        contract.write_text(json.dumps({
            "scanner_version": VERSION,
            "images": {"scanner": {"reference": "local/candidate", "candidate_build": True}},
        }))
        self.directory = self.root / ".tmp/container-security/auxiliary/scanner-provenance"
        self.archive = archive_bytes()
        self.release = {
            "id": 100, "tag_name": f"v{VERSION}", "draft": False, "prerelease": False,
            "assets": [{"id": 200, "name": ASSET, "digest": "sha256:" + hashlib.sha256(self.archive).hexdigest(),
                        "browser_download_url": ASSET_URL}],
        }
        self.copy_bytes = BINARY
        self.verified_attestation = attestation()
        self.attestation_error = None
        self.errors = io.StringIO()
        self.opened = self.enterContext(patch("urllib.request.HTTPSHandler.https_open"))
        self.enterContext(patch("urllib.request.getproxies", return_value={}))
        self.enterContext(patch("urllib.request._opener", None))
        self.sleep = self.enterContext(patch("github_api.sleep"))
        self.enterContext(patch.object(VERIFIER, "ROOT", self.root))
        # Compare deterministic fixture bytes using the production identity check.
        self.enterContext(patch.object(VERIFIER, "SOURCE_BINARY", BINARY_SHA))
        self.create = self.enterContext(patch.object(VERIFIER.subprocess, "check_output", return_value="container\n"))
        self.run_command = self.enterContext(patch.object(VERIFIER.subprocess, "run", side_effect=self.command))
        self.enterContext(patch.object(sys, "argv", [str(SCRIPT), "--upstream-only"]))
        self.enterContext(patch.dict(os.environ, {"GH_TOKEN": TOKEN}))
        self.enterContext(contextlib.redirect_stderr(self.errors))

    def command(self, args, **kwargs):
        if args[0] == "gh":
            if self.attestation_error:
                raise self.attestation_error
            json.dump(self.verified_attestation, kwargs["stdout"])
        elif args[:2] == ["docker", "cp"]:
            Path(args[-1]).write_bytes(self.copy_bytes)
        return subprocess.CompletedProcess(args, 0)

    def release_response(self, release=None):
        return response(data=json.dumps(self.release if release is None else release).encode(), url=RELEASE_URL)

    def download_responses(self, data=None):
        return [response(302, url=ASSET_URL, headers={"Location": CDN_URL}),
                response(url=CDN_URL, data=self.archive if data is None else data)]

    def assert_blocked(self, reason):
        self.errors.seek(0)
        self.errors.truncate()
        with self.assertRaises(SystemExit) as caught:
            VERIFIER.main()
        self.assertEqual(1, caught.exception.code)
        self.assertIn(reason, self.errors.getvalue())
        self.assertNotIn(TOKEN, self.errors.getvalue())
        self.assertFalse((self.directory / "upstream.json").exists())
        self.assertFalse((self.directory / "result.json").exists())

    def test_authenticated_rate_limit_recovery_keeps_archive_and_cdn_credential_free(self):
        throttle = limited(403, "You have exceeded a secondary rate limit.", {"Retry-After": "3"})
        self.opened.side_effect = [throttle, self.release_response(), *self.download_responses()]
        VERIFIER.main()
        requests = [call.args[0] for call in self.opened.call_args_list]
        self.assertEqual([RELEASE_URL, RELEASE_URL, ASSET_URL, CDN_URL], [req.full_url for req in requests])
        self.assertEqual(["Bearer " + TOKEN, "Bearer " + TOKEN, None, None],
                         [request.get_header("Authorization") for request in requests])
        self.sleep.assert_called_once_with(3)
        self.create.assert_called_once_with(
            ["docker", "create", "--platform", "linux/amd64", VERIFIER.SOURCE_IMAGE], text=True)
        self.assertEqual([
            "gh", "attestation", "verify", str(self.directory / ASSET), "--repo", "aquasecurity/trivy",
            "--signer-workflow", VERIFIER.SIGNER_WORKFLOW, "--source-ref", f"refs/tags/v{VERSION}",
            "--format", "json",
        ], self.run_command.call_args_list[0].args[0])
        upstream = json.loads((self.directory / "upstream.json").read_text())
        self.assertEqual((100, 200, BINARY_SHA, VERIFIER.SOURCE_COMMIT),
                         (upstream["release_id"], upstream["asset_id"], upstream["executable_sha256"],
                          upstream["source_commit"]))
        self.assertFalse((self.directory / "result.json").exists())

    def test_missing_or_failed_fresh_evidence_clears_previous_success(self):
        self.directory.mkdir(parents=True)
        for name in ("upstream.json", "result.json"):
            (self.directory / name).write_text('{"verified": true}')
        self.opened.side_effect = [response(503), response(503), response(503)]
        self.assert_blocked("release metadata could not be obtained: returned HTTP 503 after 3 attempts")
        self.assertEqual(3, self.opened.call_count)
        self.assertEqual([1, 2], [call.args[0] for call in self.sleep.call_args_list])
        self.run_command.assert_not_called()

    def test_missing_or_invalid_release_and_asset_evidence_is_terminal(self):
        wrong_tag = dict(self.release, tag_name="v0.0.0")
        missing_id = dict(self.release)
        del missing_id["id"]
        repeated_asset = dict(self.release, assets=self.release["assets"] * 2)
        wrong_digest = copy.deepcopy(self.release)
        wrong_digest["assets"][0]["digest"] = "sha256:" + TOKEN
        wrong_download = copy.deepcopy(self.release)
        wrong_download["assets"][0]["browser_download_url"] = f"https://example.test/{TOKEN}"
        for evidence, message in (
            ([], "release identity"), (wrong_tag, "release identity"), (missing_id, "release identity"),
            (dict(self.release, prerelease=True), "release identity"), (dict(self.release, assets=None), "release assets"),
            (dict(self.release, assets=[]), "single Linux amd64"), (repeated_asset, "single Linux amd64"),
            (wrong_digest, "asset identity or digest"), (wrong_download, "Unexpected Trivy release download URL"),
        ):
            with self.subTest(message=message):
                self.opened.reset_mock()
                self.opened.side_effect = [self.release_response(evidence)]
                self.assert_blocked(message)
                self.opened.assert_called_once()
        self.run_command.assert_not_called()
        self.sleep.assert_not_called()

    def test_archive_digest_mismatch_blocks_before_attestation(self):
        self.opened.side_effect = [self.release_response(), *self.download_responses(b"changed")]
        self.assert_blocked("archive digest mismatch")
        self.run_command.assert_not_called()

    def test_missing_or_untrusted_attestations_block(self):
        self.attestation_error = subprocess.CalledProcessError(1, ["gh"], stderr=TOKEN)
        self.opened.side_effect = [self.release_response(), *self.download_responses()]
        self.assert_blocked("archive attestation verification failed")
        self.create.assert_not_called()
        self.attestation_error = None
        for evidence, reason in (([], "missing the reviewed source commit"),
                                 (attestation("wrong-source"), "missing the reviewed source commit"),
                                 ([{}], "Malformed upstream Trivy attestation evidence")):
            with self.subTest(evidence=evidence):
                self.verified_attestation = evidence
                self.opened.side_effect = [self.release_response(), *self.download_responses()]
                self.assert_blocked(reason)

    def test_missing_archive_executable_or_wrong_image_executable_blocks(self):
        wrong_archive = archive_bytes(name="different")
        good_release = self.release_response()
        self.release["assets"][0]["digest"] = "sha256:" + hashlib.sha256(wrong_archive).hexdigest()
        self.opened.side_effect = [self.release_response(), *self.download_responses(wrong_archive)]
        self.assert_blocked("single Trivy executable")
        self.create.assert_not_called()
        self.copy_bytes = b"wrong executable"
        self.opened.side_effect = [good_release, *self.download_responses()]
        self.assert_blocked("executable identity mismatch")
        self.assertEqual(["docker", "rm", "container"], self.run_command.call_args.args[0])
