import base64
import contextlib
import copy
import io
import importlib.util
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).parents[1] / 'build-tool-inventory.py'
SPEC = importlib.util.spec_from_file_location('build_tool_inventory', SCRIPT)
INVENTORY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(INVENTORY)


class BuildToolInventoryTest(unittest.TestCase):
    def setUp(self):
        self.contract = INVENTORY.read_json(INVENTORY.CONTRACT)
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)

    def stream(self, records):
        rows = [['begin', '2', 'verify'], *records, ['session', '0']]
        rows.append(['end', '2', str(len(rows))])
        path = self.directory / 'events.tsv'
        path.write_text('\n'.join('\t'.join(x) for x in rows) + '\n')
        return path

    def artifact(self):
        path = self.directory / 'opaque.jar'
        path.write_bytes(b'fixture')
        return path

    def processors(self, values):
        xml = '<annotationProcessorPaths>'
        for value in values:
            g, a, t, c, v = value.split(':')
            xml += f'<path><groupId>{g}</groupId><artifactId>{a}</artifactId><type>{t}</type><classifier>{c}</classifier><version>{v}</version></path>'
        xml += '</annotationProcessorPaths>'
        encoded = base64.b64encode(xml.encode()).decode()
        return [['processor-config', goal, encoded] for goal in ('compile', 'testCompile')]

    def test_current_contract_represents_effective_and_direct_plugin_roots(self):
        expected = {'maven-clean-plugin', 'maven-compiler-plugin', 'maven-deploy-plugin',
                    'maven-enforcer-plugin', 'maven-failsafe-plugin', 'maven-install-plugin',
                    'maven-jar-plugin', 'maven-resources-plugin', 'maven-site-plugin',
                    'maven-surefire-plugin', 'spring-boot-maven-plugin', 'jacoco-maven-plugin'}
        current = self.contract['inventory']
        self.assertEqual(expected, {x.split(':')[1] for x in current['model_plugins']})
        self.assertEqual(set(current['plugins']), set(current['model_plugins']) |
                         {'org.apache.maven.plugins:maven-dependency-plugin:3.10.0'})
        for root, graph in current['plugins'].items():
            g, a, v = root.split(':')
            self.assertIn(f'{g}:{a}:jar::{v}', graph['realm'])
            self.assertLessEqual(set(graph['realm']), set(graph['resolved']))

    def test_required_processors_and_every_resolved_processor_component_are_owned(self):
        current = self.contract['inventory']
        self.assertLessEqual(INVENTORY.REQUIRED_PROCESSORS, set(current['processor_roots']))
        self.assertLessEqual(set(current['processor_roots']), set(current['processors']))
        self.assertLessEqual(set(current['processors']), set(current['components']))
        self.assertEqual(INVENTORY.REQUIRED_PROCESSORS,
                         INVENTORY.processor_roots(self.processors(INVENTORY.REQUIRED_PROCESSORS)))

    def test_beanutils_remediation_is_owned_in_both_realms(self):
        current = self.contract['inventory']
        for name in ('maven-site-plugin', 'maven-dependency-plugin'):
            roots = [k for k in current['plugins'] if k.split(':')[1] == name]
            self.assertEqual(1, len(roots))
            self.assertIn('commons-beanutils:commons-beanutils:jar::1.11.0',
                          current['plugins'][roots[0]]['realm'])
        self.assertFalse(any(x.startswith('commons-beanutils:commons-beanutils:')
                             and x.endswith(':1.9.4') for x in current['components']))

    def test_new_plugin_requires_reviewed_inventory_change(self):
        changed = copy.deepcopy(self.contract['inventory'])
        root = 'example:new-plugin:1'
        changed['model_plugins'].append(root)
        changed['plugins'][root] = {'resolved': ['example:new-plugin:jar::1'],
                                    'realm': ['example:new-plugin:jar::1']}
        with self.assertRaisesRegex(ValueError, 'graph changed'):
            INVENTORY.validate_inventory(changed, self.contract)

    def test_new_processor_requires_reviewed_inventory_change(self):
        changed = copy.deepcopy(self.contract['inventory'])
        value = 'example:new-processor:jar::1'
        for key in ('processor_roots', 'processors', 'components'):
            changed[key].append(value)
        with self.assertRaisesRegex(ValueError, 'graph changed'):
            INVENTORY.validate_inventory(changed, self.contract)

    def test_removing_either_processor_fails_before_scanning(self):
        for missing in INVENTORY.REQUIRED_PROCESSORS:
            with self.subTest(missing=missing), self.assertRaisesRegex(ValueError, 'Required processor absent'):
                INVENTORY.processor_roots(self.processors(INVENTORY.REQUIRED_PROCESSORS - {missing}))

    def test_changed_transitive_and_removed_expected_component_fail(self):
        for key in ('components', 'processors', 'agents'):
            changed = copy.deepcopy(self.contract['inventory'])
            changed[key].pop()
            with self.subTest(key=key), self.assertRaises(ValueError):
                INVENTORY.validate_inventory(changed, self.contract)

    def test_failed_empty_or_truncated_resolution_stream_fails(self):
        path = self.artifact()
        complete = self.stream([['artifact', 'example', 'library', 'jar', '', '1', str(path)]])
        text = complete.read_text()
        for malformed in ('', text.rsplit('end', 1)[0], text.replace('session\t0', 'session\t1'),
                          text.replace('artifact\texample', 'artifact\texample\textra')):
            complete.write_text(malformed)
            with self.subTest(input=malformed[:30]), self.assertRaises(ValueError):
                INVENTORY.evidence(complete, 'verify')

    def test_resolution_metadata_is_authoritative_even_with_opaque_filename(self):
        path = self.artifact()
        stream = self.stream([['artifact', 'example', 'library', 'jar', 'tests', '2', str(path)]])
        _, artifacts = INVENTORY.evidence(stream, 'verify')
        self.assertEqual('example:library:jar:tests:2', artifacts[str(path)])

    def test_conflicting_metadata_for_same_file_fails(self):
        path = self.artifact()
        stream = self.stream([['artifact', 'example', 'library', 'jar', '', '1', str(path)],
                              ['artifact', 'example', 'other', 'jar', '', '1', str(path)]])
        with self.assertRaisesRegex(ValueError, 'Ambiguous artifact'):
            INVENTORY.evidence(stream, 'verify')

    def test_plugin_url_without_resolution_identity_fails(self):
        path = self.artifact()
        rows = [['configured', 'example:plugin:1'],
                ['configured-url', 'example:plugin:1', path.as_uri()]]
        with self.assertRaisesRegex(ValueError, 'resolver identity'):
            INVENTORY.realm_graphs(rows, {}, 'configured')

    def test_malformed_processor_resolution_configuration_fails(self):
        for text in ('not-base64', base64.b64encode(b'<annotationProcessorPaths>').decode(),
                     base64.b64encode(b'<annotationProcessorPaths/>').decode()):
            with self.subTest(text=text), self.assertRaises((ValueError, INVENTORY.ET.ParseError)):
                INVENTORY.processor_roots([['processor-config', 'compile', text]])

    def test_unresolved_processor_version_cannot_be_inferred(self):
        values = INVENTORY.REQUIRED_PROCESSORS | {'example:processor:jar::${version}'}
        with self.assertRaisesRegex(ValueError, 'unresolved'):
            INVENTORY.processor_roots(self.processors(values))

    def test_current_cyclonedx_is_deterministic_and_has_unique_purls(self):
        document = INVENTORY.bom(self.contract['inventory'])
        INVENTORY.validate_bom(document, self.contract)
        self.assertEqual(document, INVENTORY.bom(copy.deepcopy(self.contract['inventory'])))
        self.assertEqual('enterprise-shop-build-tools', document['metadata']['component']['name'])
        self.assertEqual(len(document['components']), len({x['purl'] for x in document['components']}))

    def test_incomplete_wrong_root_and_duplicate_cyclonedx_fail(self):
        for mutation in ('remove', 'root', 'duplicate', 'beanutils'):
            document = INVENTORY.bom(self.contract['inventory'])
            if mutation == 'remove':
                document['components'].pop()
            elif mutation == 'root':
                document['metadata']['component']['name'] = 'application-image'
            elif mutation == 'duplicate':
                document['components'].append(document['components'][0])
            else:
                item = next(x for x in document['components'] if x['name'] == 'commons-beanutils')
                item['version'] = '1.9.4'
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                INVENTORY.validate_bom(document, self.contract)

    def test_malformed_and_duplicate_key_json_fail(self):
        path = self.directory / 'input.json'
        for text in ('', '{', '{"components":[],"components":[]}'):
            path.write_text(text)
            with self.subTest(text=text), self.assertRaises(ValueError):
                INVENTORY.read_json(path)

    def test_cyclonedx_numeric_version_rejects_boolean_and_float(self):
        for value in (True, 1.0):
            document = INVENTORY.bom(self.contract['inventory'])
            document['version'] = value
            with self.subTest(value=value), self.assertRaises(ValueError):
                INVENTORY.validate_bom(document, self.contract)

    def test_classifiers_and_types_are_explicit(self):
        self.assertEqual('pkg:maven/org.jacoco/org.jacoco.agent@0.8.15?classifier=runtime&type=jar',
                         INVENTORY.purl('org.jacoco:org.jacoco.agent:jar:runtime:0.8.15'))
        with self.assertRaises(ValueError):
            INVENTORY.purl('example:executable:zip::1')

    def test_maven_distribution_package_has_reviewed_pom_identity(self):
        self.assertEqual(INVENTORY.DISTRIBUTION_PACKAGE,
                         self.contract['distribution_package']['coordinate'])
        document = INVENTORY.bom(self.contract['inventory'])
        items = [x for x in document['components'] if x['name'] == 'apache-maven']
        self.assertEqual(1, len(items))
        self.assertEqual('application', items[0]['type'])
        self.assertEqual('pkg:maven/org.apache.maven/apache-maven@3.10.0?type=pom', items[0]['purl'])
        with self.assertRaises(ValueError):
            INVENTORY.purl('example:unreviewed:pom::1')

    def test_distribution_unknown_changed_and_missing_artifacts_fail(self):
        lib = self.directory / 'lib'; lib.mkdir()
        jar = lib / 'opaque.jar'
        with zipfile.ZipFile(jar, 'w') as archive:
            archive.writestr('example.class', b'fixture')
        item = {'coordinate': 'example:library:jar:classes:1', 'sha256': INVENTORY.digest(jar),
                'authority': 'distribution-pom-resolution-and-byte-match'}
        reviewed = {'lib/opaque.jar': item}
        self.assertEqual({item['coordinate']}, INVENTORY.bootstrap(self.directory, reviewed))
        for change in ('authority', 'bytes', 'missing', 'extra'):
            candidate = copy.deepcopy(reviewed)
            if change == 'authority':
                candidate['lib/opaque.jar']['authority'] = 'filename'
            elif change == 'bytes':
                candidate['lib/opaque.jar']['sha256'] = '0' * 64
            elif change == 'missing':
                candidate['lib/missing.jar'] = item
            else:
                (lib / 'extra.jar').write_bytes(b'extra')
            with self.subTest(change=change), self.assertRaises(ValueError):
                INVENTORY.bootstrap(self.directory, candidate)

    def test_missing_dynamic_provider_and_processor_execution_logs_fail(self):
        path = self.directory / 'verify.log'
        path.write_text('[INFO] BUILD SUCCESS\n')
        with self.assertRaisesRegex(ValueError, 'processor path'):
            INVENTORY.execution_paths(path, {}, self.directory)

    def test_failed_recollection_removes_previous_scan_ready_outputs(self):
        previous = self.directory / 'previous.cdx.json'
        previous.write_text('{}')
        (self.directory / 'inventory.json').write_text('{}')
        (self.directory / 'execution-scope.json').write_text('{}')
        with patch('sys.argv', ['inventory', 'collect', '--directory', str(self.directory),
                               '--bom', str(previous)]), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(1, INVENTORY.main())
        self.assertFalse(previous.exists())
        self.assertFalse((self.directory / 'inventory.json').exists())
        self.assertFalse((self.directory / 'execution-scope.json').exists())


if __name__ == '__main__':
    unittest.main()
