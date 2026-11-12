# Android Auto alignment model

The current Auto DBH path uses `yolo26_alignment.onnx` through
`YoloBoundaryAligner`. A local copy belongs in this directory; model binaries
remain excluded from Git. A build without that model keeps the existing
unavailable/fallback behavior.

`tree_seg_640.onnx` is a retired private BSI research model. It is not used by
the current Auto path and must not be distributed with ForestiX. The Android
build excludes that filename from every variant even if a local research copy
is present. The exclusion does not delete the local file.

Before publishing an APK, check that `assets/models/tree_seg_640.onnx` is absent
and that the intended `assets/models/yolo26_alignment.onnx` is present.
