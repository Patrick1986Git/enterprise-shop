import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "validate-github-actions-policy.py"
SPEC = importlib.util.spec_from_file_location("github_actions_policy", SCRIPT)
POLICY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(POLICY)
SHA = "0123456789abcdef0123456789abcdef01234567"


class CodeQLWorkflowPolicyTest(unittest.TestCase):
    def setUp(self):
        self.workflow = (SCRIPT.parents[1] / ".github/workflows/codeql.yml").read_text()

    def validate(self, contents):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "codeql.yml"
            path.write_text(contents, encoding="utf-8")
            return POLICY.validate_workflows(Path(directory))

    def reject(self, before, after, diagnostic):
        self.assertIn(before, self.workflow)
        errors = self.validate(self.workflow.replace(before, after))
        self.assertTrue(any(diagnostic in error for error in errors), errors)

    def test_reviewed_dual_language_workflow_passes(self):
        self.assertEqual([], self.validate(self.workflow))

    def test_either_language_job_cannot_disappear(self):
        for job in ("analyze", "analyze-python"):
            with self.subTest(job=job):
                self.reject(f"  {job}:\n", f"  removed-{job}:\n", "independent Java/Kotlin and Python")

    def test_whole_codeql_workflow_cannot_disappear_from_cli_policy(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertTrue(POLICY.validate_workflows(Path(directory), require_codeql=True))

    def test_java_build_cannot_move_before_initialization(self):
        self.reject("      - name: Initialize CodeQL\n",
                    "      - name: Early build\n        run: ./mvnw -B clean verify\n\n"
                    "      - name: Initialize CodeQL\n", "between initialization and analysis")

    def test_java_manual_and_python_no_build_modes_cannot_change(self):
        for mode in ("manual", "none"):
            with self.subTest(mode=mode):
                self.reject(f"build-mode: {mode}", "build-mode: autobuild", "mode, queries")

    def test_security_queries_and_languages_cannot_be_reduced(self):
        for before, after in (("queries: security-extended", "queries: security-and-quality"),
                              ("languages: python", "languages: javascript-typescript"),
                              ("languages: java-kotlin", "languages: python")):
            with self.subTest(before=before):
                self.reject(before, after, "mode, queries")

    def test_java_jdk_build_and_ryuk_procedure_remain_required(self):
        for before in ("java-version: '21'", "run: ./mvnw -B clean verify",
                       "run: python scripts/build-ryuk-candidate.py", "SPRING_PROFILES_ACTIVE: test"):
            with self.subTest(before=before):
                self.reject(before, "removed: true", "full Java 21 Maven verification")

    def test_pull_request_target_cannot_be_introduced(self):
        self.reject("  pull_request:", "  pull_request_target:", "triggers")

    def test_master_push_pr_schedule_and_dispatch_cannot_disappear(self):
        for before in ("  push:", "  pull_request:", "  schedule:", "  workflow_dispatch:", "      - master"):
            with self.subTest(before=before):
                self.reject(before, "  removed:", "triggers")

    def test_persisted_credentials_and_mutable_action_references_fail(self):
        self.reject("persist-credentials: false", "persist-credentials: true", "persist-credentials")
        self.reject(POLICY.CODEQL_SHA, "v4", "full 40-character")

    def test_permissions_cannot_be_broadened_or_sarif_disabled(self):
        for before, after in (("contents: read", "contents: write"),
                              ("security-events: write", "security-events: read"),
                              ("upload: always", "upload: never")):
            with self.subTest(before=before):
                self.reject(before, after, "least privilege")
        self.reject("      security-events: write\n", "      security-events: write\n      id-token: write\n",
                    "least privilege")
        self.reject("permissions:\n  contents: read\n", "permissions:\n  contents: read\n  id-token: write\n",
                    "default workflow permissions")

    def test_java_and_python_uploads_remain_distinct(self):
        self.reject("category: /language:python", "category: /language:java-kotlin", "distinct processed SARIF")
        self.reject("      - name: Analyze with CodeQL\n",
                    "      - name: Analyze with CodeQL\n        with:\n          category: /language:python\n",
                    "existing SARIF category")

    def test_python_extraction_and_processed_upload_are_required(self):
        for before in ("python scripts/validate-codeql-python-coverage.py", "git ls-files -z -- '*.py'",
                       "wait-for-processing: true", "name: python-codeql-evidence", "if-no-files-found: error",
                       "bqrs decode --format=json --entities=string,url"):
            with self.subTest(before=before):
                self.reject(before, "removed: true", "source-extraction evidence")

    def test_job_cannot_be_skipped_or_given_privileged_services(self):
        for extra in ("    if: false", "    needs: analyze", "    container: ubuntu:latest",
                      "    services:", "    secrets: inherit"):
            with self.subTest(extra=extra):
                self.reject("  analyze-python:\n", "  analyze-python:\n" + extra + "\n", "unconditional and isolated")

    def test_source_filters_and_query_suppression_cannot_hide_owned_files(self):
        for extra in ("          source-root: scripts", "          config-file: reduced.yml",
                      "          paths-ignore: scripts", "          skip-queries: true"):
            with self.subTest(extra=extra):
                self.reject("          languages: python\n", "          languages: python\n" + extra + "\n",
                            "coverage cannot be restricted")

    def test_python_database_population_cannot_execute_candidate_code(self):
        for command in ("python scripts/build-ryuk-candidate.py", "./mvnw -B clean verify", "pip install -r requirements.txt"):
            with self.subTest(command=command):
                self.reject("      - name: Initialize Python CodeQL\n",
                            "      - name: Unreviewed execution\n        run: " + command +
                            "\n\n      - name: Initialize Python CodeQL\n", "must not build")

    def test_pr_sha_and_dispatch_checkout_semantics_remain_required(self):
        self.reject("github.event.pull_request.head.sha", "github.sha", "permissions and checkout")


class GitHubActionsPolicyTest(unittest.TestCase):
    def validate(self, contents, filename="fixture.yml"):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / filename
            path.write_text(contents, encoding="utf-8")
            return POLICY.validate_workflows(Path(directory))

    def workflow(self, reference, extra=""):
        return f"jobs:\n  test:\n    steps:\n      - uses: {reference}\n{extra}"

    def ci_workflow(self, ref, container_ref="${{ github.event_name == 'schedule' && 'master' || github.event.pull_request.head.sha || github.ref }}"):
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
        expression = "${{ github.event_name == 'schedule' && 'master' || github.event.pull_request.head.sha || github.ref }}"
        restore_expression = "${{ github.event_name == 'schedule' && 'master' || github.event_name == 'pull_request' && github.sha || github.ref }}"
        self.assertEqual([], self.validate(self.ci_workflow(restore_expression, expression), "ci.yml"))

        refs = {
            "schedule": ("refs/heads/ignored", "master"),
            "workflow_dispatch": ("refs/heads/ratchet", "refs/heads/ratchet"),
            "pull_request": ("refs/pull/294/merge", "candidate-head-sha"),
            "push": ("refs/heads/master", "refs/heads/master"),
        }
        for event_name, (github_ref, expected) in refs.items():
            with self.subTest(event_name=event_name):
                self.assertEqual(
                    expected,
                    POLICY.resolve_container_security_checkout_ref(
                        expression, event_name, github_ref, "candidate-head-sha"
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
