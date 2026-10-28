#!/usr/bin/env python3
"""Opt in the named UI-test bundle, never an unstable target-array index.

Writes a new generated xctestrun next to its input so __TESTROOT__ remains
valid. This does not install an app, run tests or change device orientation.
"""
import argparse
import copy
import json
from pathlib import Path
import plistlib


KEYS = {"FORESTIX_IPAD_SMOKE", "FORESTIX_IPAD_WIDTH", "FORESTIX_IPAD_HEIGHT", "FORESTIX_REMOTE_PREFLIGHT"}


def configure_run(document, mode, width=None, height=None):
    if mode not in ("physical", "synthetic"):
        raise ValueError("Unknown field test mode")
    if mode == "physical" and not (isinstance(width, int) and isinstance(height, int) and 0 < width < height):
        raise ValueError("Supply the physical iPad's native portrait width and height in points")
    result = copy.deepcopy(document)
    selected = []
    for config in result.get("TestConfigurations", []):
        if config.get("IsEnabled", True) is False:
            continue
        targets = config.get("TestTargets", [])
        matches = [t for t in targets if t.get("BlueprintName") == "ForestixUITests"]
        if len(matches) != 1 or matches[0].get("IsUITestBundle") is not True:
            raise ValueError("Each enabled configuration must have exactly one ForestixUITests UI bundle")
        target = matches[0]
        platform = "Debug-iphoneos/" if mode == "physical" else "Debug-iphonesimulator/"
        if platform not in target.get("TestHostPath", ""):
            raise ValueError("Test-host platform does not match the requested safety mode")
        # Remove stale opt-in flags accidentally assigned to a unit-test target.
        for other in targets:
            for group in ("EnvironmentVariables", "TestingEnvironmentVariables"):
                environment = other.setdefault(group, {})
                for key in KEYS:
                    environment.pop(key, None)
        flags = ({"FORESTIX_IPAD_SMOKE": "1", "FORESTIX_IPAD_WIDTH": str(width), "FORESTIX_IPAD_HEIGHT": str(height)}
                 if mode == "physical" else {"FORESTIX_REMOTE_PREFLIGHT": "1"})
        for group in ("EnvironmentVariables", "TestingEnvironmentVariables"):
            target[group].update(flags)
        selected.append({"configuration": config.get("Name", ""), "target": target["BlueprintName"], "mode": mode})
    if not selected:
        raise ValueError("No enabled test configuration")
    return result, selected


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--mode", choices=("physical", "synthetic"), required=True)
    parser.add_argument("--width", type=int)
    parser.add_argument("--height", type=int)
    args = parser.parse_args()
    if args.source.resolve().parent != args.destination.resolve().parent:
        parser.error("Keep the generated run file in the same Products directory as its source")
    if args.destination.suffix != ".xctestrun":
        parser.error("Destination must be a new .xctestrun file")
    try:
        original = args.source.read_bytes()
        configured, selected = configure_run(plistlib.loads(original), args.mode, args.width, args.height)
        output = plistlib.dumps(configured, fmt=plistlib.FMT_BINARY if original.startswith(b"bplist") else plistlib.FMT_XML, sort_keys=False)
        with args.destination.open("xb") as stream:
            stream.write(output)
    except (ValueError, OSError, plistlib.InvalidFileException) as error:
        parser.error(str(error))
    print(json.dumps({"configured": selected, "output": str(args.destination)}))


if __name__ == "__main__":
    main()
