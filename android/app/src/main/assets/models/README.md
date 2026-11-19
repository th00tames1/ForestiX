# Android Auto alignment model

The current Auto DBH path uses `yolo26_alignment.onnx` through
`YoloBoundaryAligner`. A local copy belongs in this directory; model binaries
remain excluded from Git. A build without that model keeps the existing
unavailable/fallback behavior.

`android/app/production-assets.txt` explicitly lists the allowed application
assets. Only this README and `yolo26_alignment.onnx` are permitted in `models/`.
The build stages approved assets in its generated output directory; it never
copies the local model directory wholesale or deletes local research files.

The retired private BSI model `tree_seg_640.onnx`, experimental MobileSAM model
`mobilesam_alignment.onnx`, and experimental license/provenance files are not
packaged. `android/app/production-sensor-sources.txt` also explicitly lists the
production sensor code, so `MobileBoundaryAligner.kt` and new local sensor
experiments are not compiled. Add new production files to the relevant list.

Before publishing an APK, verify that `assets/models/yolo26_alignment.onnx`
is its only ONNX model and that its SHA-256 matches the intended local model.
