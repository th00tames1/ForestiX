# iOS Auto alignment model

The current Auto DBH path uses `yolo26_alignment.onnx` through
`YoloBoundaryAligner` and `YoloModelIdentity`. Model binaries remain excluded
from Git. A local model belongs in this directory; a clone without the current
model keeps the existing unavailable/fallback behavior.

`tree_seg_640.onnx` is a retired private BSI research model. It is not used by
the current Auto path and must not be distributed with ForestiX. The Sensors
target excludes that file and processes individual model resources instead of
copying this directory wholesale. Local research copies are not deleted.

Processed ONNX files are copied without conversion into the Sensors resource
bundle's root. The unchanged `YoloModelIdentity` lookup already supports both
the previous `Models/` directory and the bundle root.

Before distributing an app, verify that no `tree_seg_640.onnx` appears anywhere
inside it and that the current Auto model's SHA-256 matches the local source.
