#!/usr/bin/env python3
"""Run the real non-UI Swift package targets on macOS without iOS-only packages.

Creates an isolated manifest from Package.swift, symlinks production sources and
tests, and runs every test target except UIFlowTests and UISnapshotTests.
CaptureRuntimeTests use selected, unchanged production files instead of the
iOS-only UI target. No production or test sources are substituted.
Pass --output to retain the harness; --prepare-only only generates the manifest.
Set DEVELOPER_DIR to a full Xcode installation before running this command.
"""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile


CAPTURE_SOURCES = (
    'App/AppSettings.swift', 'App/Country.swift', 'App/RegionalSpecies.swift',
    'App/MeasurePhotoStore.swift', 'App/TreeNameSequence.swift',
    'App/CaptureReadingMatch.swift', 'App/SpeciesCatalogStore.swift',
    'App/DBHEpochRecompute.swift', 'App/FieldCaptureCoordinator.swift',
    'App/FieldQuickResultProofReader.swift', 'App/FieldStudyStore.swift',
    'App/QuickMeasureHistory.swift', 'App/ResearchLog.swift', 'App/ResearchExport.swift',
    'App/TruthBackfill.swift', 'App/TruthUnitRepair.swift',
    'ViewModels/DBHScanViewModel.swift', 'ViewModels/HeightScanViewModel.swift',
)
CAPTURE_DEPENDENCIES = ['Common', 'Models', 'Persistence', 'Sensors', 'AR', 'Basemap']
MANIFEST_PREFIX = '// swift-tools-version: 6.0\nimport PackageDescription\n'
MANIFEST_NAME = 'let package = Package(name: "ForestixHostValidation", '


def ensure_source_link(link, source):
    if link.is_symlink() and link.resolve() == source.resolve():
        return
    # Refuse both substituted sources and destructive replacement.
    if link.exists() or link.is_symlink():
        raise FileExistsError(f'Harness source is not the expected symlink: {link}')
    link.symlink_to(source, target_is_directory=source.is_dir())


def validate_output(out, root):
    if out == root or out in root.parents:
        raise ValueError('Harness output must not replace the repository or its parent manifest')
    manifest = out / 'Package.swift'
    if manifest.exists() and not manifest.read_text().startswith(MANIFEST_PREFIX + MANIFEST_NAME):
        raise FileExistsError(f'Output contains a non-harness Package.swift: {manifest}')


def host_targets(package):
    targets = []
    omitted = {'UI', 'UIFlowTests', 'UISnapshotTests'}
    for target in package['targets']:
        if target['name'] in omitted:
            continue
        deps = [d['byName'][0] for d in target['dependencies'] if 'byName' in d]
        if target['name'] == 'CaptureRuntimeTests':
            deps = ['CaptureRuntime' if d == 'UI' else d for d in deps]
        elif 'UI' in deps:
            raise ValueError(f'Unmapped iOS UI dependency in {target["name"]}')
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
    if any(t['name'] == 'CaptureRuntimeTests' for t in package['targets']):
        targets.append('.target(name: "CaptureRuntime", dependencies: ' +
                       json.dumps(CAPTURE_DEPENDENCIES) + ', path: "HostCaptureRuntime")')
    return targets


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--jobs', type=int, default=2)
    parser.add_argument('--prepare-only', action='store_true')
    args = parser.parse_args()
    if args.jobs < 1:
        parser.error('--jobs must be positive')
    root = Path(__file__).resolve().parents[2]
    out = args.output or Path(tempfile.mkdtemp(prefix='forestix-host-tests-'))
    out = out.resolve()
    validate_output(out, root)
    out.mkdir(parents=True, exist_ok=True)
    package = json.loads(subprocess.check_output(
        ['swift', 'package', 'dump-package'], cwd=root))
    targets = host_targets(package)
    if any(t['name'] == 'CaptureRuntimeTests' for t in package['targets']):
        runtime = out / 'HostCaptureRuntime'
        runtime.mkdir(exist_ok=True)
        for relative in CAPTURE_SOURCES:
            source = root / 'TimberCruisingApp' / relative
            if not source.is_file():
                raise FileNotFoundError(source)
            link = runtime / source.name
            ensure_source_link(link, source)
    for name in ('TimberCruisingApp', 'Tests', 'validation'):
        source = root / name
        link = out / name
        if source.exists():
            ensure_source_link(link, source)
    (out / 'Package.swift').write_text(
        MANIFEST_PREFIX + MANIFEST_NAME +
        'platforms: [.macOS(.v14)], targets: [\n' + ',\n'.join(targets) +
        '\n], swiftLanguageModes: [.v5])\n')
    print(f'Harness: {out}', flush=True)
    print('Excluded: iOS UI views, SnapshotTesting and iOS ONNX runtime. '
          'Production sources, including the capture runtime, are symlinked unchanged. '
          'This host suite does not automate a connected device.', flush=True)
    if args.prepare_only:
        return 0
    return subprocess.call(['swift', 'test', '--jobs', str(args.jobs)], cwd=out)


if __name__ == '__main__':
    raise SystemExit(main())
