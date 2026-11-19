"""Application packaging regressions. No field datasets or device are accessed.

First run Gradle's writePublicationInputs task via publication-inputs.init.gradle,
and assembleDebug plus mergeReleaseAssets. These tests inspect their real outputs.
"""
import hashlib
import json
from pathlib import Path
import subprocess
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / 'android/app'
SENSORS = 'android/app/src/main/java/com/hcjeong/forestix/sensors/'


def approved(name):
    return [line.strip() for line in (APP / name).read_text().splitlines()
            if line.strip() and not line.lstrip().startswith('#')]


def tracked(path):
    return subprocess.check_output(['git', 'ls-files', path], cwd=ROOT,
                                   text=True).splitlines()


class PublicationBuildTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.inputs = json.loads((APP / 'build/reports/publication-inputs.json').read_text())

    def test_allowlists_have_no_duplicates_or_path_escapes(self):
        for name in ['production-assets.txt', 'production-sensor-sources.txt']:
            paths = approved(name)
            self.assertTrue(paths)
            self.assertEqual(len(paths), len(set(paths)))
            self.assertTrue(all(not Path(path).is_absolute() and '..' not in Path(path).parts
                                and '*' not in path for path in paths))

    def test_every_tracked_production_sensor_is_still_allowed(self):
        expected = {path.removeprefix(SENSORS) for path in tracked(SENSORS)
                    if path.endswith(('.kt', '.java'))}
        self.assertEqual(set(approved('production-sensor-sources.txt')), expected)

    def test_actual_debug_and_release_compiler_inputs_preserve_production_code(self):
        expected = {path for path in tracked('android/app/src/main')
                    if path.endswith(('.kt', '.java'))}
        self.assertTrue(expected)
        for variant, paths in self.inputs['sources'].items():
            with self.subTest(variant=variant):
                self.assertEqual(set(paths), expected)
                self.assertEqual(len(paths), len(expected))
                self.assertFalse(any('MobileBoundaryAligner' in path for path in paths))
                self.assertTrue(self.inputs['syntheticProbesExcluded'][variant])

    def test_only_approved_existing_assets_are_staged_without_changing_bytes(self):
        expected = {path for path in approved('production-assets.txt')
                    if (APP / 'src/main/assets' / path).is_file()}
        self.assertEqual(set(self.inputs['assets']), expected)
        for path in expected:
            self.assertEqual((APP / 'src/main/assets' / path).read_bytes(),
                             (APP / 'build/generated/publication/assets' / path).read_bytes())

    def test_debug_apk_has_no_experiments_and_preserves_all_app_assets(self):
        with zipfile.ZipFile(APP / 'build/outputs/apk/debug/app-debug.apk') as apk:
            names = apk.namelist()
            models = [name for name in names if name.endswith('.onnx')]
            self.assertEqual(models, ['assets/models/yolo26_alignment.onnx'])
            self.assertFalse(any(term in name.lower() for name in names
                                 for term in ['mobilesam', 'tree_seg_640', 'manuscript/', 'raw-captures/']))
            for path in self.inputs['assets']:
                self.assertEqual(apk.read('assets/' + path),
                                 (APP / 'src/main/assets' / path).read_bytes())
            dex = b''.join(apk.read(name) for name in names if name.endswith('.dex'))
            self.assertNotIn(b'MobileBoundaryAligner', dex)

    def test_release_merged_assets_also_exclude_research_models(self):
        directory = APP / 'build/intermediates/assets/release/mergeReleaseAssets'
        self.assertTrue(directory.is_dir())
        models = list(directory.rglob('*.onnx'))
        self.assertEqual([path.name for path in models], ['yolo26_alignment.onnx'])
        self.assertFalse(any('mobilesam' in path.name.lower() for path in directory.rglob('*')))
        self.assertEqual(models[0].read_bytes(),
                         (APP / 'src/main/assets/models/yolo26_alignment.onnx').read_bytes())

    def test_production_model_is_the_previously_approved_model(self):
        model = APP / 'src/main/assets/models/yolo26_alignment.onnx'
        self.assertEqual(hashlib.sha256(model.read_bytes()).hexdigest(),
                         'c8ef0b54ac0db572205a5dd5da78047fc4ea0863632ff90a9369f3851f4c99d8')


if __name__ == '__main__':
    unittest.main()
