#!/usr/bin/env python3
"""Run the real non-UI Swift package targets on macOS without iOS-only packages.

Creates an isolated manifest from Package.swift, symlinks production sources and
tests, and runs every test target except UIFlowTests and UISnapshotTests. No
production or test sources are substituted. Pass --output to retain the harness.
Set DEVELOPER_DIR to a full Xcode installation before running this command.
"""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    out = args.output or Path(tempfile.mkdtemp(prefix='forestix-host-tests-'))
    out = out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    package = json.loads(subprocess.check_output(
        ['swift', 'package', 'dump-package'], cwd=root))
    targets = []
    omitted = {'UI', 'UIFlowTests', 'UISnapshotTests'}
    for target in package['targets']:
        if target['name'] in omitted:
            continue
        deps = [d['byName'][0] for d in target['dependencies'] if 'byName' in d]
        fields = [f'name: {json.dumps(target["name"])}',
                  f'dependencies: {json.dumps(deps)}',
                  f'path: {json.dumps(target["path"])}']
        resources = []
        for resource in target.get('resources', []):
            rule = next(iter(resource['rule']))
            resources.append(f'.{rule}({json.dumps(resource["path"])})')
        if resources:
            fields.append('resources: [' + ', '.join(resources) + ']')
        kind = 'testTarget' if target['type'] == 'test' else 'target'
        targets.append(f'.{kind}(' + ', '.join(fields) + ')')
    for name in ('TimberCruisingApp', 'Tests', 'validation'):
        source = root / name
        link = out / name
        if source.exists() and not link.exists():
            link.symlink_to(source, target_is_directory=True)
    (out / 'Package.swift').write_text(
        '// swift-tools-version: 6.0\nimport PackageDescription\n'
        'let package = Package(name: "ForestixHostValidation", '
        'platforms: [.macOS(.v14)], targets: [\n' + ',\n'.join(targets) +
        '\n], swiftLanguageModes: [.v5])\n')
    print(f'Harness: {out}', flush=True)
    print('Excluded: iOS UI targets, SnapshotTesting and iOS ONNX runtime. '
          'Production non-UI sources are symlinked unchanged.', flush=True)
    return subprocess.call(['swift', 'test', '--parallel'], cwd=out)


if __name__ == '__main__':
    raise SystemExit(main())
