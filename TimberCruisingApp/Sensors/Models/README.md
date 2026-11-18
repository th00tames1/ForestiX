# iOS Auto alignment model

The current Auto DBH path uses `yolo26_alignment.onnx` through
`YoloBoundaryAligner` and `YoloModelIdentity`. Model binaries remain excluded
from Git. A local model belongs in this directory; a clone without the current
model keeps the existing unavailable/fallback behavior.

The Sensors target permits only `README.md` and `yolo26_alignment.onnx` as
model resources. All other local model files, accompanying research licenses,
and experimental provenance files are excluded from app builds. In particular,
the retired private BSI model `tree_seg_640.onnx` and experimental MobileSAM
model `mobilesam_alignment.onnx` must not be distributed with ForestiX.
Local research copies are not deleted.

Production sensor source files are also explicitly listed in `Package.swift`.
Experimental local sources, including `MobileBoundaryAligner.swift`, are not
compiled into the app. New production sensor files must be added to that list.

Processed ONNX files are copied without conversion into the Sensors resource
bundle's root. The unchanged `YoloModelIdentity` lookup already supports both
the previous `Models/` directory and the bundle root.

Before distributing an app, verify that `yolo26_alignment.onnx` is the only
ONNX model inside it and that its SHA-256 matches the local source.
