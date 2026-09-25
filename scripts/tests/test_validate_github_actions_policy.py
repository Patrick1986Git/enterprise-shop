import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "validate-github-actions-policy.py"
SPEC = importlib.util.spec_from_file_location("github_actions_policy", SCRIPT)
POLICY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(POLICY)
SHA = "0123456789abcdef0123456789abcdef01234567"


class GitHubActionsPolicyTest(unittest.TestCase):
    def validate(self, contents, filename="fixture.yml"):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / filename
            path.write_text(contents, encoding="utf-8")
            return POLICY.validate_workflows(Path(directory))

    def workflow(self, reference, extra=""):
        return f"jobs:\n  test:\n    steps:\n      - uses: {reference}\n{extra}"

    def ci_workflow(self, ref, container_ref="${{ github.event_name == 'schedule' && 'master' || github.ref }}"):
        dependency_review = (
            "  dependency-review:\n"
            "    if: github.event_name == 'pull_request'\n"
            "    steps:\n"
            "      - name: Checkout candidate source\n"
            f"        uses: actions/checkout@{SHA}\n"
            "        with:\n"
            "          ref: ${{ github.event.pull_request.head.sha }}\n"
            "          persist-credentials: false\n"
            "      - name: Checkout exact protected-base source\n"
            f"        uses: actions/checkout@{SHA}\n"
            "        with:\n"
            "          repository: ${{ github.event.pull_request.base.repo.full_name }}\n"
            "          ref: ${{ github.event.pull_request.base.sha }}\n"
            "          path: target/dependency-review-base\n"
            "          persist-credentials: false\n"
            "      - name: Validate dependency-review provenance\n"
            "        env:\n"
            "          BASE_REPOSITORY: ${{ github.event.pull_request.base.repo.full_name }}\n"
            "          BASE_SHA: ${{ github.event.pull_request.base.sha }}\n"
            "          HEAD_SHA: ${{ github.event.pull_request.head.sha }}\n"
            "        run: |\n"
            "          test \"$BASE_REPOSITORY\" = \"${{ github.repository }}\"\n"
            "          test \"$(git -C target/dependency-review-base rev-parse HEAD)\" = \"$BASE_SHA\"\n"
            "          test \"$(git rev-parse HEAD)\" = \"$HEAD_SHA\"\n"
            "      - name: Review dependency changes\n"
            "        uses: actions/dependency-review-action@a1d282b36b6f3519aa1f3fc636f609c47dddb294\n"
            "        with:\n"
            "          fail-on-severity: high\n"
            "          fail-on-scopes: runtime, development, unknown\n"
            "          license-check: false\n"
            "          show-openssf-scorecard: false\n"
        )
        build = self.workflow(
            f"actions/checkout@{SHA}",
            "        with:\n"
            "          persist-credentials: false\n"
            "      - name: Validate live protected-master policy\n"
            "        run: python scripts/validate-master-protection.py --repository \"${{ github.repository }}\"\n"
            "  container-security:\n"
            "    steps:\n"
            "      - name: Checkout\n"
            f"        uses: actions/checkout@{SHA}\n"
            "        with:\n"
            "          persist-credentials: false\n"
            f"          ref: {container_ref}\n"
            "      - name: Checkout protected-master OpenAPI baseline source\n"
            "        if: github.event_name == 'pull_request'\n"
            f"        uses: actions/checkout@{SHA}\n"
            "        with:\n"
            "          repository: ${{ github.event.pull_request.base.repo.full_name }}\n"
            "          ref: ${{ github.event.pull_request.base.sha }}\n"
            "          path: target/openapi-baseline-source\n"
            "          persist-credentials: false\n"
            "      - name: Generate protected-master OpenAPI baseline\n"
            "        if: github.event_name == 'pull_request'\n"
            "        env:\n"
            "          BASE_SHA: ${{ github.event.pull_request.base.sha }}\n"
            "          SPRING_PROFILES_ACTIVE: test\n"
            "        run: echo generate\n"
            "      - name: Enforce Flyway rolling-compatibility decision boundary\n"
            "        if: github.event_name == 'pull_request'\n"
            "        env:\n"
            "          BASE_REPOSITORY: ${{ github.event.pull_request.base.repo.full_name }}\n"
            "          BASE_SHA: ${{ github.event.pull_request.base.sha }}\n"
            "        run: |\n"
            "          test \"$BASE_REPOSITORY\" = \"${{ github.repository }}\"\n"
            "          test \"$(git -C target/openapi-baseline-source rev-parse HEAD)\" = \"$BASE_SHA\"\n"
            "          python scripts/validate_migration_compatibility.py \\\n"
            "            --base target/openapi-baseline-source \\\n"
            "            --candidate .\n",
        ).removeprefix("jobs:\n").replace("  test:\n", "  build:\n", 1)
        workflow = "permissions:\n  contents: read\njobs:\n" + dependency_review + build
        return workflow + (
            "  restore-pr-scope:\n"
            "    if: github.event_name == 'pull_request'\n"
            "    outputs:\n"
            "      run_restore: ${{ steps.scope.outputs.run_restore }}\n"
            "    steps:\n"
            "      - name: Checkout candidate source for restore scope detection\n"
            f"        uses: actions/checkout@{SHA}\n"
            "        with:\n"
            "          persist-credentials: false\n"
            "          fetch-depth: 0\n"
            "          ref: ${{ github.event.pull_request.head.sha }}\n"
            "      - name: Determine whether the PR affects restore execution\n"
            "        id: scope\n"
            "        env:\n"
            "          BASE_SHA: ${{ github.event.pull_request.base.sha }}\n"
            "          HEAD_SHA: ${{ github.event.pull_request.head.sha }}\n"
            "        run: |\n"
            "          python scripts/restore_pr_scope.py \\\n"
            "            --base-sha \"$BASE_SHA\" \\\n"
            "            --head-sha \"$HEAD_SHA\"\n"
            "  restore-rehearsal:\n"
            "    needs: restore-pr-scope\n"
            "    if: always() && (github.event_name == 'push' || github.event_name == 'schedule' || github.event_name == 'workflow_dispatch' || (github.event_name == 'pull_request' && needs.restore-pr-scope.outputs.run_restore == 'true'))\n"
            "    steps:\n"
            "      - name: Checkout restore rehearsal source\n"
            f"        uses: actions/checkout@{SHA}\n"
            "        with:\n"
            "          persist-credentials: false\n"
            f"          ref: {ref}\n"
            "      - name: Run synthetic PostgreSQL logical restore rehearsal\n"
            "        run: ./scripts/restore-rehearsal.sh\n"
            "      - name: Run historical-forward PostgreSQL restore rehearsal\n"
            "        run: ./scripts/historical-forward-restore-rehearsal.sh\n"
        )

    def test_accepts_external_action_pinned_to_full_sha(self):
        self.assertEqual([], self.validate(self.workflow(f"owner/action@{SHA}")))

    def test_rejects_mutable_major_tag(self):
        self.assertTrue(self.validate(self.workflow("owner/action@v4")))

    def test_rejects_release_tag(self):
        self.assertTrue(self.validate(self.workflow("owner/action@v4.2.2")))

    def test_rejects_branch(self):
        self.assertTrue(self.validate(self.workflow("owner/action@main")))

    def test_rejects_short_sha(self):
        self.assertTrue(self.validate(self.workflow("owner/action@0123456")))

    def test_allows_local_action(self):
        self.assertEqual([], self.validate(self.workflow("./.github/actions/example")))

    def test_rejects_checkout_without_disabled_credentials(self):
        violations = self.validate(self.workflow(f"actions/checkout@{SHA}"))
        self.assertIn("persist-credentials: false", violations[0])

    def test_accepts_checkout_with_disabled_credentials(self):
        workflow = self.workflow(
            f"actions/checkout@{SHA}",
            "        with:\n          persist-credentials: false\n",
        )
        self.assertEqual([], self.validate(workflow))

    def test_reports_workflow_filename_and_offending_action(self):
        violations = self.validate(self.workflow("owner/action@main"), "named.yaml")
        self.assertIn("named.yaml", violations[0])
        self.assertIn("owner/action@main", violations[0])

    def test_scans_yml_and_yaml_files(self):
        with tempfile.TemporaryDirectory() as directory:
            workflows = Path(directory)
            (workflows / "one.yml").write_text(
                self.workflow("owner/one@main"), encoding="utf-8"
            )
            (workflows / "two.yaml").write_text(
                self.workflow("owner/two@main"), encoding="utf-8"
            )
            violations = POLICY.validate_workflows(workflows)
        self.assertEqual(2, len(violations))
        self.assertTrue(any("one.yml" in violation for violation in violations))
        self.assertTrue(any("two.yaml" in violation for violation in violations))

    def test_ignores_action_like_text_in_run_block(self):
        workflow = "jobs:\n  test:\n    steps:\n      - run: |\n          uses: image@example:tag\n"
        self.assertEqual([], self.validate(workflow))

    def test_container_security_checkout_preserves_event_ref_semantics(self):
        expression = "${{ github.event_name == 'schedule' && 'master' || github.ref }}"
        restore_expression = "${{ github.event_name == 'schedule' && 'master' || github.event_name == 'pull_request' && github.sha || github.ref }}"
        self.assertEqual([], self.validate(self.ci_workflow(restore_expression, expression), "ci.yml"))

        refs = {
            "schedule": ("refs/heads/ignored", "master"),
            "workflow_dispatch": ("refs/heads/ratchet", "refs/heads/ratchet"),
            "pull_request": ("refs/pull/294/merge", "refs/pull/294/merge"),
            "push": ("refs/heads/master", "refs/heads/master"),
        }
        for event_name, (github_ref, expected) in refs.items():
            with self.subTest(event_name=event_name):
                self.assertEqual(
                    expected,
                    POLICY.resolve_container_security_checkout_ref(
                        expression, event_name, github_ref
                    ),
                )

    def test_rejects_container_security_checkout_that_anchors_dispatch_to_master(self):
        expression = (
            "${{ (github.event_name == 'schedule' || "
            "github.event_name == 'workflow_dispatch') && 'master' || github.ref }}"
        )
        restore_expression = "${{ github.event_name == 'schedule' && 'master' || github.event_name == 'pull_request' && github.sha || github.ref }}"
        violations = self.validate(self.ci_workflow(restore_expression, expression), "ci.yml")
        self.assertTrue(any("workflow_dispatch" in violation for violation in violations))

    def test_restore_rehearsal_preserves_event_and_candidate_ref_semantics(self):
        expression = "${{ github.event_name == 'schedule' && 'master' || github.event_name == 'pull_request' && github.sha || github.ref }}"
        self.assertEqual([], self.validate(self.ci_workflow(expression), "ci.yml"))
        refs = {
            "schedule": "master",
            "pull_request": "candidate-sha",
            "workflow_dispatch": "refs/heads/selected",
            "push": "refs/heads/master",
        }
        for event_name, expected in refs.items():
            with self.subTest(event_name=event_name):
                self.assertEqual(expected, POLICY.resolve_restore_checkout_ref(
                    expression, event_name, "refs/heads/selected" if event_name == "workflow_dispatch" else "refs/heads/master", "candidate-sha"
                ))

    def test_rejects_unconditional_restore_rehearsal_on_pull_requests(self):
        expression = "${{ github.event_name == 'schedule' && 'master' || github.event_name == 'pull_request' && github.sha || github.ref }}"
        workflow = self.ci_workflow(expression).replace(
            "needs.restore-pr-scope.outputs.run_restore == 'true'",
            "github.event_name == 'pull_request'",
        )
        violations = self.validate(workflow, "ci.yml")
        self.assertTrue(any("restore-relevant PRs" in violation for violation in violations))

    def test_rejects_shallow_restore_scope_detection(self):
        expression = "${{ github.event_name == 'schedule' && 'master' || github.event_name == 'pull_request' && github.sha || github.ref }}"
        workflow = self.ci_workflow(expression).replace("          fetch-depth: 0\n", "")
        violations = self.validate(workflow, "ci.yml")
        self.assertTrue(any("complete immutable base/head history" in violation for violation in violations))

    def test_rejects_restore_scope_detection_using_mutable_base_ref(self):
        expression = "${{ github.event_name == 'schedule' && 'master' || github.event_name == 'pull_request' && github.sha || github.ref }}"
        workflow = self.ci_workflow(expression).replace(
            "BASE_SHA: ${{ github.event.pull_request.base.sha }}",
            "BASE_SHA: ${{ github.base_ref }}",
        )
        violations = self.validate(workflow, "ci.yml")
        self.assertTrue(any("complete immutable base/head history" in violation for violation in violations))

    def test_rejects_workflow_without_read_only_default_permissions(self):
        expression = "${{ github.event_name == 'schedule' && 'master' || github.event_name == 'pull_request' && github.sha || github.ref }}"
        workflow = self.ci_workflow(expression).replace("permissions:\n  contents: read\n", "")
        violations = self.validate(workflow, "ci.yml")
        self.assertTrue(any("default workflow permissions" in violation for violation in violations))

    def test_rejects_build_without_live_master_protection_validation(self):
        expression = "${{ github.event_name == 'schedule' && 'master' || github.event_name == 'pull_request' && github.sha || github.ref }}"
        workflow = self.ci_workflow(expression).replace(
            "      - name: Validate live protected-master policy\n"
            "        run: python scripts/validate-master-protection.py --repository \"${{ github.repository }}\"\n",
            "",
        )
        violations = self.validate(workflow, "ci.yml")
        self.assertTrue(any("live protected-master policy" in violation for violation in violations))

    def test_rejects_dependency_review_without_exact_base_repository(self):
        expression = "${{ github.event_name == 'schedule' && 'master' || github.event_name == 'pull_request' && github.sha || github.ref }}"
        workflow = self.ci_workflow(expression).replace(
            "repository: ${{ github.event.pull_request.base.repo.full_name }}",
            "repository: ${{ github.repository }}",
            1,
        )
        violations = self.validate(workflow, "ci.yml")
        self.assertTrue(any("dependency review" in violation for violation in violations))

    def test_rejects_dependency_review_that_omits_development_scope(self):
        expression = "${{ github.event_name == 'schedule' && 'master' || github.event_name == 'pull_request' && github.sha || github.ref }}"
        workflow = self.ci_workflow(expression).replace(
            "fail-on-scopes: runtime, development, unknown",
            "fail-on-scopes: runtime",
        )
        violations = self.validate(workflow, "ci.yml")
        self.assertTrue(any("dependency review" in violation for violation in violations))

    def test_rejects_dependency_review_license_policy(self):
        expression = "${{ github.event_name == 'schedule' && 'master' || github.event_name == 'pull_request' && github.sha || github.ref }}"
        workflow = self.ci_workflow(expression).replace(
            "license-check: false", "license-check: true"
        )
        violations = self.validate(workflow, "ci.yml")
        self.assertTrue(any("license policy disabled" in violation for violation in violations))

    def test_rejects_restore_rehearsal_without_historical_forward_scenario(self):
        expression = "${{ github.event_name == 'schedule' && 'master' || github.event_name == 'pull_request' && github.sha || github.ref }}"
        workflow = self.ci_workflow(expression).replace(
            "      - name: Run historical-forward PostgreSQL restore rehearsal\n"
            "        run: ./scripts/historical-forward-restore-rehearsal.sh\n",
            "",
        )
        violations = self.validate(workflow, "ci.yml")
        self.assertTrue(any("both repository-owned restore scripts" in violation for violation in violations))

    def test_rejects_restore_rehearsal_schedule_from_candidate_ref(self):
        expression = "${{ github.event_name == 'schedule' && 'master' || github.event_name == 'pull_request' && github.sha || github.ref }}"
        workflow = self.ci_workflow(expression).replace(
            f"          ref: {expression}\n      - name: Run synthetic PostgreSQL logical restore rehearsal",
            "          ref: ${{ github.ref }}\n      - name: Run synthetic PostgreSQL logical restore rehearsal",
        )
        violations = self.validate(workflow, "ci.yml")
        self.assertTrue(any("restore-rehearsal checkout" in violation for violation in violations))

    def test_rejects_openapi_baseline_checkout_from_candidate_ref(self):
        workflow = self.ci_workflow("${{ github.event_name == 'schedule' && 'master' || github.event_name == 'pull_request' && github.sha || github.ref }}")
        workflow = workflow.replace(
            "ref: ${{ github.event.pull_request.base.sha }}",
            "ref: ${{ github.event.pull_request.head.sha }}",
        )
        violations = self.validate(workflow, "ci.yml")
        self.assertTrue(any("OpenAPI baseline checkout" in violation for violation in violations))

    def test_rejects_openapi_baseline_generation_without_test_profile(self):
        expression = "${{ github.event_name == 'schedule' && 'master' || github.event_name == 'pull_request' && github.sha || github.ref }}"
        workflow = self.ci_workflow(expression).replace(
            "          SPRING_PROFILES_ACTIVE: test\n", ""
        )
        violations = self.validate(workflow, "ci.yml")
        self.assertTrue(any("canonical test Spring profile" in violation for violation in violations))

    def test_rejects_openapi_baseline_generation_with_different_profile(self):
        expression = "${{ github.event_name == 'schedule' && 'master' || github.event_name == 'pull_request' && github.sha || github.ref }}"
        workflow = self.ci_workflow(expression).replace(
            "SPRING_PROFILES_ACTIVE: test", "SPRING_PROFILES_ACTIVE: default"
        )
        violations = self.validate(workflow, "ci.yml")
        self.assertTrue(any("canonical test Spring profile" in violation for violation in violations))

    def test_rejects_missing_flyway_compatibility_boundary(self):
        expression = "${{ github.event_name == 'schedule' && 'master' || github.event_name == 'pull_request' && github.sha || github.ref }}"
        workflow = self.ci_workflow(expression)
        start = workflow.index("      - name: Enforce Flyway rolling-compatibility decision boundary\n")
        end = workflow.index("  restore-pr-scope:\n", start)
        violations = self.validate(workflow[:start] + workflow[end:], "ci.yml")
        self.assertTrue(any("Flyway compatibility policy" in violation for violation in violations))

    def test_rejects_flyway_compatibility_against_mutable_base(self):
        expression = "${{ github.event_name == 'schedule' && 'master' || github.event_name == 'pull_request' && github.sha || github.ref }}"
        workflow = self.ci_workflow(expression).replace(
            "BASE_SHA: ${{ github.event.pull_request.base.sha }}\n",
            "BASE_SHA: ${{ github.base_ref }}\n",
        )
        violations = self.validate(workflow, "ci.yml")
        self.assertTrue(any("Flyway compatibility policy" in violation for violation in violations))


if __name__ == "__main__":
    unittest.main()
