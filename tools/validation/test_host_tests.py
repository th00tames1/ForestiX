"""Host-harness coverage and device-safety checks; no device is contacted."""
import importlib.util
from pathlib import Path
import unittest
import tempfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('host_tests', Path(__file__).with_name('run_host_tests.py'))
host = importlib.util.module_from_spec(spec)
spec.loader.exec_module(host)


class HostHarnessTests(unittest.TestCase):
    def test_capture_runtime_is_not_silently_omitted_or_left_depending_on_ui(self):
        package = {'targets': [
            {'name': 'UI', 'path': 'TimberCruisingApp', 'type': 'regular', 'dependencies': []},
            {'name': 'CaptureRuntimeTests', 'path': 'Tests/CaptureRuntimeTests', 'type': 'test',
             'dependencies': [{'byName': ['UI', None]}, {'byName': ['Sensors', None]}]},
        ]}
        targets = host.host_targets(package)
        self.assertEqual(len(targets), 2)
        self.assertIn('dependencies: ["CaptureRuntime", "Sensors"]', targets[0])
        self.assertIn('name: "CaptureRuntime"', targets[1])
        self.assertNotIn('dependencies: ["UI"', '\n'.join(targets))

    def test_unknown_ui_dependency_fails_instead_of_reducing_coverage(self):
        with self.assertRaises(ValueError):
            host.host_targets({'targets': [{'name': 'NewTests', 'path': 'Tests/NewTests',
                'type': 'test', 'dependencies': [{'byName': ['UI', None]}]}]})

    def test_runtime_sources_are_real_unique_production_files(self):
        names = [Path(p).name for p in host.CAPTURE_SOURCES]
        self.assertEqual(len(names), len(set(names)))
        for relative in host.CAPTURE_SOURCES:
            self.assertTrue((ROOT/'TimberCruisingApp'/relative).is_file(), relative)

    def test_sources_are_linked_unchanged_and_substitution_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, link = root/'actual.swift', root/'linked.swift'
            source.write_text('synthetic original')
            host.ensure_source_link(link, source)
            host.ensure_source_link(link, source)  # Idempotent reuse.
            self.assertTrue(link.is_symlink())
            self.assertEqual(link.read_bytes(), source.read_bytes())
            other = root/'substituted.swift'
            other.write_text('synthetic replacement')
            with self.assertRaises(FileExistsError):
                host.ensure_source_link(link, other)
            with self.assertRaises(FileExistsError):
                host.ensure_source_link(other, source)
            self.assertEqual(other.read_text(), 'synthetic replacement')

    def test_repository_and_foreign_manifests_cannot_be_overwritten(self):
        with self.assertRaises(ValueError):
            host.validate_output(ROOT, ROOT)
        with self.assertRaises(ValueError):
            host.validate_output(ROOT.parent, ROOT)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root/'Package.swift'
            manifest.write_text('synthetic user manifest')
            with self.assertRaises(FileExistsError):
                host.validate_output(root, ROOT)
            self.assertEqual(manifest.read_text(), 'synthetic user manifest')

    def test_rotation_setters_require_separate_authorization(self):
        source = (ROOT/'ForestixUITests/iPadDeviceSmokeTests.swift').read_text()
        setup = source.split('override func setUpWithError()', 1)[1].split('@MainActor', 1)[0]
        self.assertIn('FORESTIX_ALLOW_ROTATION_UNLOCK', setup)
        self.assertIn('XCTSkipUnless', setup)
        settings = (ROOT/'ForestixUITests/iPadFieldStudySmokeTests.swift').read_text()
        self.assertNotIn('XCUIDevice.shared.orientation =', settings)


if __name__ == '__main__':
    unittest.main()
