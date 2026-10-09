import copy
import importlib.util
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('execution_scan', ROOT / 'scripts/scan-build-tools.py')
SCAN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SCAN)
INVENTORY, POLICY = SCAN.INVENTORY, SCAN.POLICY


class BuildToolExecutionScopeTest(unittest.TestCase):
    def setUp(self):
        self.active = 'example:active-plugin:1'
        self.available = 'example:available-plugin:1'
        self.direct = 'example:docker-plugin:1'
        self.processor = 'example:processor:jar::1'
        self.provider = 'example:provider:jar::1'
        self.booter = 'example:booter:jar::1'
        self.agent = 'example:agent:jar:runtime:1'
        self.distribution = {'example:maven-library:jar::1'}
        self.graphs = {}
        for root in (self.active, self.available, self.direct):
            group, name, version = root.split(':')
            members = {f'{group}:{name}:jar::{version}', f'{group}:{name}-transitive:jar::{version}'}
            self.graphs[root] = {'realm': members, 'resolved': members}
        components = self.distribution | {INVENTORY.DISTRIBUTION_PACKAGE,
            self.processor, self.provider, self.booter, self.agent}
        for graph in self.graphs.values():
            components.update(graph['realm'])
        self.inventory = {'plugins': {r: {k: sorted(v) for k, v in g.items()}
                                      for r, g in self.graphs.items()},
                          'processors': [self.processor], 'agents': [self.agent],
                          'providers': {'surefire': [self.provider]},
                          'booters': {'surefire': [self.booter]}, 'components': sorted(components)}
        self.executions = {'verify': [self.active + ':run:default'],
                           'docker': [self.direct + ':offline:default-cli']}
        self.actual = {'verify': {self.active: self.graphs[self.active]},
                       'docker': {self.direct: self.graphs[self.direct]}}
        self.document = INVENTORY.bom(self.inventory)

    def scope(self):
        return INVENTORY.execution_scope(self.inventory, self.executions, self.actual, self.distribution)

    def result(self, coordinate, scope=None):
        identity = INVENTORY.purl(coordinate)
        group, name, _, _, version = coordinate.split(':')
        report = {'SchemaVersion': 2, 'ArtifactType': 'cyclonedx', 'Trivy': {'Version': '0.75.0'},
                  'Results': [{'Class': 'lang-pkgs', 'Type': 'jar', 'Packages': [
                      {'Name': x['group'] + ':' + x['name'], 'Version': x['version'],
                       'Identifier': {'PURL': x['purl']}} for x in self.document['components']],
                      'Vulnerabilities': [{'PkgName': group + ':' + name, 'InstalledVersion': version,
                          'PkgIdentifier': {'PURL': identity}, 'Severity': 'HIGH',
                          'VulnerabilityID': 'CVE-fixture-ownership'}]}]}
        scope = scope or self.scope()
        return POLICY.execution_policy(self.document, report, 0,
            [INVENTORY.purl(x) for x in scope['executed_components']])

    def test_high_in_executed_plugin_transitive_blocks(self):
        self.assertTrue(self.result('example:active-plugin-transitive:jar::1')['blocked'])

    def test_high_in_actual_processor_blocks(self):
        self.assertTrue(self.result(self.processor)['blocked'])

    def test_high_in_provider_booter_agent_and_distribution_blocks(self):
        for value in (self.provider, self.booter, self.agent, *self.distribution,
                      INVENTORY.DISTRIBUTION_PACKAGE):
            with self.subTest(component=value):
                self.assertTrue(self.result(value)['blocked'])

    def test_high_in_executed_docker_plugin_realm_blocks(self):
        self.assertTrue(self.result('example:docker-plugin-transitive:jar::1')['blocked'])

    def test_resolved_only_high_is_preserved_without_blocking(self):
        result = self.result('example:available-plugin-transitive:jar::1')
        self.assertFalse(result['blocked'])
        self.assertEqual(1, len(result['resolved_only']))

    def promote(self, mode, execution_id):
        before = copy.deepcopy(self.inventory)
        self.executions[mode].append(self.available + ':render:' + execution_id)
        self.actual[mode][self.available] = self.graphs[self.available]
        scope = self.scope()
        self.assertEqual(before, self.inventory)
        self.assertLessEqual(self.graphs[self.available]['realm'], set(scope['executed_components']))
        result = self.result('example:available-plugin-transitive:jar::1', scope)
        self.assertTrue(result['blocked'])
        self.assertFalse(result['resolved_only'])

    def test_binding_available_plugin_promotes_full_realm_without_inventory_edit(self):
        self.promote('verify', 'new-lifecycle-binding')

    def test_explicit_required_command_promotes_full_realm_without_inventory_edit(self):
        self.promote('docker', 'default-cli')

    def test_executed_plugin_cannot_be_omitted_from_actual_ownership(self):
        self.actual['verify'].clear()
        with self.assertRaisesRegex(ValueError, 'omitted'):
            self.scope()

    def test_new_processor_is_always_in_executable_scope(self):
        value = 'example:new-processor:jar::2'
        self.inventory['processors'].append(value)
        self.inventory['components'].append(value)
        self.assertIn(value, self.scope()['executed_components'])

    def test_missing_modes_and_malformed_execution_receipts_fail(self):
        for records in (None, [], ['not-a-goal'], [self.active + ':run:default'] * 2):
            self.executions['verify'] = records
            with self.subTest(records=records), self.assertRaises(ValueError):
                self.scope()
        self.executions.pop('verify')
        with self.assertRaises(ValueError):
            self.scope()

    def receipts(self, include_available=False):
        roots = [self.active, self.available] if include_available else [self.active]
        return [row for root in roots for row in (
            ['started', root, 'run', 'default'], ['executed', root, 'run', 'default'])]

    def plan(self, directory, include_available=False):
        path = Path(directory) / 'verify.log'
        roots = [self.active, self.available] if include_available else [self.active]
        path.write_text('\n'.join(f'[DEBUG] Goal: {root}:run (default)' for root in roots)
                        + '\n[INFO] BUILD SUCCESS\n')
        return path

    def test_missing_start_success_realm_or_independent_plan_fails(self):
        with tempfile.TemporaryDirectory() as temporary:
            log = self.plan(temporary)
            for rows, actual in ((self.receipts()[1:], self.actual['verify']),
                                 (self.receipts()[:1], self.actual['verify']),
                                 (self.receipts(), {})):
                with self.subTest(rows=rows), self.assertRaises(ValueError):
                    INVENTORY.execution_records(rows, actual, log)
            log.write_text('[INFO] BUILD SUCCESS\n')
            with self.assertRaisesRegex(ValueError, 'execution plan'):
                INVENTORY.execution_records(self.receipts(), self.actual['verify'], log)

    def test_dropped_collector_event_and_realm_pair_cannot_shrink_scope(self):
        with tempfile.TemporaryDirectory() as temporary:
            log = self.plan(temporary, include_available=True)
            with self.assertRaisesRegex(ValueError, 'execution plan'):
                INVENTORY.execution_records(self.receipts(), self.actual['verify'], log)

    def test_duplicate_components_and_conflicting_realms_fail(self):
        self.inventory['components'].append(self.processor)
        with self.assertRaisesRegex(ValueError, 'Duplicate/conflicting'):
            self.scope()
        self.inventory['components'].pop()
        self.actual['verify'][self.active] = {'realm': {'example:wrong-version:jar::2'},
                                             'resolved': {'example:wrong-version:jar::2'}}
        with self.assertRaisesRegex(ValueError, 'conflicts'):
            self.scope()

    def test_shared_component_is_executed_if_any_executed_owner_uses_it(self):
        shared = 'example:available-plugin-transitive:jar::1'
        self.graphs[self.active]['realm'].add(shared)
        self.graphs[self.active]['resolved'].add(shared)
        self.inventory['plugins'][self.active] = {k: sorted(v) for k, v in self.graphs[self.active].items()}
        self.assertTrue(self.result(shared)['blocked'])

    def test_tampered_subset_is_rejected_by_fresh_receipts_before_scanning(self):
        scope = self.scope()
        contract = {'schema': 2, 'inventory': self.inventory}
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            INVENTORY.write_json(directory / 'inventory.json', self.inventory)
            changed = copy.deepcopy(scope)
            removed = changed['executed_components'].pop()
            changed['resolved_only_components'].append(removed)
            INVENTORY.write_json(directory / 'execution-scope.json', changed)
            with patch.object(INVENTORY, 'collect', return_value=(self.inventory, scope)), \
                    self.assertRaisesRegex(ValueError, 'fresh authoritative receipts'):
                INVENTORY.validate_collected(directory, contract)

    def test_classification_uses_ownership_without_package_or_cve_special_cases(self):
        source = (ROOT / 'scripts/validate-build-tool-vulnerabilities.py').read_text()
        for marker in ('jetty', 'CVE-2026-2332', 'CVE-2026-10050', 'maven-site-plugin'):
            self.assertNotIn(marker, source)
        scope = self.scope()
        self.assertEqual(set(self.inventory['components']),
                         set(scope['executed_components']) | set(scope['resolved_only_components']))
        self.assertFalse(set(scope['executed_components']) & set(scope['resolved_only_components']))

    def test_scope_schema_boolean_or_float_cannot_match_a_valid_receipt(self):
        scope = self.scope()
        contract = {'schema': 2, 'inventory': self.inventory}
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            INVENTORY.write_json(directory / 'inventory.json', self.inventory)
            for value in (True, 1.0):
                supplied = {**scope, 'schema': value}
                INVENTORY.write_json(directory / 'execution-scope.json', supplied)
                with patch.object(INVENTORY, 'collect', return_value=(self.inventory, scope)), \
                        self.subTest(value=value), self.assertRaises(ValueError):
                    INVENTORY.validate_collected(directory, contract)

    def source_commands(self, directory, text, source='Dockerfile'):
        root = Path(directory)
        path = root / source
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return INVENTORY.required_commands(root)

    def test_required_source_discovery_covers_docker_workflows_and_scripts(self):
        with tempfile.TemporaryDirectory() as directory:
            self.source_commands(directory, 'RUN ./mvnw -B dependency:go-offline && true\n')
            self.source_commands(directory, 'run: ./mvnw available:render > evidence.log\n',
                                 '.github/workflows/check.yaml')
            commands = self.source_commands(directory, './mvnw install\n', 'scripts/nested/required.sh')
            self.assertEqual({'dependency:go-offline', 'available:render', 'install'},
                             {goal for command in commands for goal in command['goals']})

    def test_already_observed_lifecycle_options_comments_and_continuations(self):
        with tempfile.TemporaryDirectory() as directory:
            commands = self.source_commands(directory, '# ./mvnw ignored:goal\n'
                'RUN ./mvnw -B -DskipTests -f pom.xml clean package\n'
                './mvnw -s settings.xml available:render \\\n  available:help # comment\n')
            self.assertEqual([{'source': 'Dockerfile', 'goals': ['available:render', 'available:help']}],
                             commands)

    def test_dynamic_goals_or_additional_profiles_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            for command in ('./mvnw "$GOALS"', './mvnw -Pextra package',
                            './mvnw --activate-profiles=extra package', './mvnw --settings'):
                with self.subTest(command=command), self.assertRaises(ValueError):
                    self.source_commands(directory, command)

    def test_new_required_source_command_is_observed_automatically(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'source'
            evidence = Path(directory) / 'evidence'
            evidence.mkdir()
            self.source_commands(source, 'RUN ./mvnw docker:offline available:render\n')
            artifacts = {}
            with patch.object(INVENTORY, 'ROOT', source), \
                    patch.object(INVENTORY, 'evidence', return_value=([], artifacts)), \
                    patch.object(INVENTORY, 'realm_graphs', return_value=self.actual['verify']), \
                    patch.object(INVENTORY, 'execution_records', return_value=self.executions['verify']), \
                    patch.object(INVENTORY, 'executed_aliases', return_value={'active'}), \
                    patch.object(INVENTORY.subprocess, 'run') as run:
                INVENTORY.observe_commands(evidence, [])
            self.assertEqual(['docker:offline', 'available:render'], run.call_args.args[0][-2:])
            self.promote('docker', 'new-required-command')

    def test_new_required_command_cannot_pass_on_previous_receipts(self):
        with tempfile.TemporaryDirectory() as directory:
            commands = self.source_commands(directory, './mvnw available:render\n')
            with self.assertRaisesRegex(ValueError, 'lacks authoritative'):
                INVENTORY.command_coverage(commands, {'active', 'docker'})
            INVENTORY.command_coverage(commands, {'active', 'docker', 'available'})

    def test_failed_command_observation_removes_stale_receipts_and_scan_ready_outputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            names = ('docker.tsv', 'docker.log', 'required-commands.json', 'inventory.json',
                     'execution-scope.json', 'enterprise-shop-build-tools.cdx.json', 'policy-result.json')
            for name in names:
                (root / name).write_text('stale')
            with patch.object(INVENTORY, 'evidence', side_effect=ValueError('broken receipts')), \
                    self.assertRaises(ValueError):
                INVENTORY.observe_commands(root, [])
            self.assertFalse(any((root / name).exists() for name in names))

    def test_executed_prefix_comes_from_matching_embedded_plugin_descriptor(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'unrelated-name.jar'
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('META-INF/maven/plugin.xml', '<plugin><groupId>example</groupId>'
                    '<artifactId>active-plugin</artifactId><version>1</version>'
                    '<goalPrefix>authoritative</goalPrefix></plugin>')
            aliases = INVENTORY.executed_aliases({'verify': self.executions['verify']},
                                                 {path: 'example:active-plugin:jar::1'})
            self.assertTrue(INVENTORY.command_owned('authoritative:new-goal', aliases))
            self.assertTrue(INVENTORY.command_owned('example:active-plugin:1:new-goal', aliases))
            self.assertFalse(INVENTORY.command_owned('available:render', aliases))
            self.assertFalse(INVENTORY.command_owned('example:active-plugin:2:run', aliases))
            self.assertTrue(INVENTORY.command_owned('example:active-plugin:run', aliases))
            self.assertFalse(INVENTORY.command_owned('example:available-plugin:render', aliases))

    def test_missing_or_conflicting_plugin_descriptor_cannot_establish_command_ownership(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'artifact.jar'
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('META-INF/maven/plugin.xml', '<plugin><groupId>wrong</groupId>'
                    '<artifactId>active-plugin</artifactId><version>1</version>'
                    '<goalPrefix>active</goalPrefix></plugin>')
            for artifacts in ({}, {path: 'example:active-plugin:jar::1'}):
                with self.subTest(artifacts=artifacts), self.assertRaises(ValueError):
                    INVENTORY.executed_aliases({'verify': self.executions['verify']}, artifacts)


if __name__ == '__main__':
    unittest.main()
