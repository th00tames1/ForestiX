import copy
import unittest
from configure_field_test_run import configure_run, KEYS


def fixture(ui_first=False, simulator=False):
    unit = {"BlueprintName": "ForestixTests", "EnvironmentVariables": {"KEEP": "unit", "FORESTIX_IPAD_SMOKE": "wrong"}}
    ui = {"BlueprintName": "ForestixUITests", "IsUITestBundle": True,
          "TestHostPath": "__TESTROOT__/Debug-" + ("iphonesimulator" if simulator else "iphoneos") + "/Runner.app",
          "EnvironmentVariables": {"KEEP": "ui"}, "TestingEnvironmentVariables": {"DYLD_LIBRARY_PATH": "keep"}}
    return {"TestConfigurations": [{"IsEnabled": True, "Name": "Synthetic fixture", "TestTargets": [ui, unit] if ui_first else [unit, ui]}]}


class ConfigurationTests(unittest.TestCase):
    def test_physical_opt_in_follows_bundle_name_in_both_orders(self):
        for first in (False, True):
            with self.subTest(ui_first=first):
                source = fixture(first)
                original = copy.deepcopy(source)
                result, summary = configure_run(source, "physical", 1032, 1376)
                self.assertEqual(source, original)
                targets = result["TestConfigurations"][0]["TestTargets"]
                for target in targets:
                    for group in ("EnvironmentVariables", "TestingEnvironmentVariables"):
                        environment = target[group]
                        if target["BlueprintName"] == "ForestixUITests":
                            self.assertEqual(environment["FORESTIX_IPAD_SMOKE"], "1")
                            self.assertEqual(environment["FORESTIX_IPAD_WIDTH"], "1032")
                            self.assertEqual(environment["FORESTIX_IPAD_HEIGHT"], "1376")
                        else:
                            self.assertFalse(KEYS.intersection(environment))
                self.assertEqual(summary[0]["target"], "ForestixUITests")
                self.assertEqual(next(t for t in targets if t["BlueprintName"] == "ForestixUITests")["TestingEnvironmentVariables"]["DYLD_LIBRARY_PATH"], "keep")

    def test_synthetic_opt_in_has_no_physical_flags(self):
        result, _ = configure_run(fixture(simulator=True), "synthetic")
        target = result["TestConfigurations"][0]["TestTargets"][1]
        for group in ("EnvironmentVariables", "TestingEnvironmentVariables"):
            self.assertEqual(target[group]["FORESTIX_REMOTE_PREFLIGHT"], "1")
            self.assertFalse((KEYS - {"FORESTIX_REMOTE_PREFLIGHT"}).intersection(target[group]))

    def test_wrong_platform_is_refused_in_either_direction(self):
        with self.assertRaises(ValueError): configure_run(fixture(simulator=True), "physical", 1032, 1376)
        with self.assertRaises(ValueError): configure_run(fixture(), "synthetic")

    def test_missing_or_duplicate_ui_bundle_is_refused(self):
        for targets in ([{"BlueprintName": "ForestixTests"}], fixture()["TestConfigurations"][0]["TestTargets"] * 2):
            document = {"TestConfigurations": [{"TestTargets": targets}]}
            with self.assertRaises(ValueError): configure_run(document, "physical", 1032, 1376)

    def test_non_ui_bundle_is_not_trusted_by_name(self):
        document = fixture()
        document["TestConfigurations"][0]["TestTargets"][1]["IsUITestBundle"] = False
        with self.assertRaises(ValueError): configure_run(document, "physical", 1032, 1376)

    def test_empty_disabled_or_invalid_portrait_configuration_is_refused(self):
        for document in ({}, {"TestConfigurations": [{"IsEnabled": False}]}):
            with self.assertRaises(ValueError): configure_run(document, "physical", 1032, 1376)
        for dimensions in ((None, None), (0, 1376), (1376, 1032), (1032, 1032)):
            with self.assertRaises(ValueError): configure_run(fixture(), "physical", *dimensions)


if __name__ == "__main__":
    unittest.main()
