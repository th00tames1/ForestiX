"""Synthetic packaging checks; no research inputs or device access required."""
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


def sensor_manifest(files):
    """Evaluate the real manifest with disposable, synthetic local files."""
    with tempfile.TemporaryDirectory(prefix="forestix-packaging-") as directory:
        fixture = Path(directory)
        (fixture / "Package.swift").write_bytes((ROOT / "Package.swift").read_bytes())
        sensors = fixture / "TimberCruisingApp/Sensors"
        for name in files:
            path = sensors / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"synthetic packaging fixture, not a model")
        before = {name: hashlib.sha256((sensors / name).read_bytes()).hexdigest()
                  for name in files}
        result = subprocess.run(["swift", "package", "dump-package"], cwd=fixture,
                                capture_output=True, text=True, check=True)
        after = {name: hashlib.sha256((sensors / name).read_bytes()).hexdigest()
                 for name in files}
        if before != after:
            raise AssertionError("Manifest evaluation changed local files")
        return next(target for target in json.loads(result.stdout)["targets"]
                    if target["name"] == "Sensors")


class ReleasePackagingTests(unittest.TestCase):
    def test_model_free_clone_keeps_readme_without_missing_model_resource(self):
        target = sensor_manifest(["Models/README.md"])
        self.assertEqual([item["path"] for item in target["resources"]],
                         ["Models/README.md"])
        self.assertEqual(target["exclude"], [])

    def test_private_experimental_and_unknown_assets_are_not_resources(self):
        excluded = ["Models/tree_seg_640.onnx", "Models/mobilesam_alignment.onnx",
                    "Models/MobileSAM-LICENSE.txt", "Models/MobileSAM-PROVENANCE.txt",
                    "Models/unapproved_future_model.onnx", "Models/research_notes.txt",
                    "Models/experimental_nested", "MobileBoundaryAligner.swift"]
        files = ["Models/README.md", "Models/yolo26_alignment.onnx"] + excluded
        target = sensor_manifest(files)
        self.assertEqual(set(target["exclude"]), set(excluded))
        self.assertEqual({item["path"] for item in target["resources"]},
                         {"Models/README.md", "Models/yolo26_alignment.onnx"})
        self.assertNotIn("MobileBoundaryAligner.swift", target["sources"])

    def test_unknown_experimental_swift_files_cannot_become_production_sources(self):
        target = sensor_manifest(["Models/README.md", "NewResearchExperiment.swift",
                                  "PointCloud/Experimental.swift"])
        self.assertNotIn("NewResearchExperiment.swift", target["sources"])
        self.assertNotIn("PointCloud/Experimental.swift", target["sources"])
        self.assertTrue(all(source.endswith(".swift") for source in target["sources"]))

    def test_all_existing_tracked_sensor_sources_remain_included(self):
        target = sensor_manifest(["Models/README.md"])
        tracked = subprocess.check_output(
            ["git", "ls-files", "TimberCruisingApp/Sensors"], cwd=ROOT, text=True)
        expected = {str(Path(path).relative_to("TimberCruisingApp/Sensors"))
                    for path in tracked.splitlines() if path.endswith(".swift")}
        self.assertEqual(set(target["sources"]), expected)
        self.assertEqual(len(target["sources"]), len(expected))

    def test_app_debug_and_release_version_settings_agree(self):
        document = json.loads(subprocess.check_output(
            ["plutil", "-convert", "json", "-o", "-", "Forestix.xcodeproj/project.pbxproj"],
            cwd=ROOT))
        objects = document["objects"]
        app = next(obj for obj in objects.values()
                   if obj.get("isa") == "PBXNativeTarget" and obj.get("name") == "Forestix")
        settings = [objects[key]["buildSettings"] for key in
                    objects[app["buildConfigurationList"]]["buildConfigurations"]]
        self.assertEqual(len(settings), 2)
        self.assertEqual(len({item["MARKETING_VERSION"] for item in settings}), 1)
        self.assertEqual(len({item["CURRENT_PROJECT_VERSION"] for item in settings}), 1)
        self.assertTrue(all(int(item["CURRENT_PROJECT_VERSION"]) > 0 for item in settings))


if __name__ == "__main__":
    unittest.main()
