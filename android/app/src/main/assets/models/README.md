# On-device segmentation weights

`tree_seg_640.onnx` is **not in git** and this directory ships empty. The app
builds and runs without it; the Auto diameter path simply falls back to the
depth walk and says so (see `TreeSegmenter.availability`).

The file is a YOLO11n-seg export, 640x640, batch 1, classes `{0: soot, 1: tree}`
— the BSI bark-scorch study's trained weights. That manuscript is unpublished
and this repository is public, so the weights are carried in the working tree
and in the app bundle but never committed.

To rebuild with segmentation:

    cp <BSI>/Resources/models/yolo11n-seg_640_b16.onnx \
       TimberCruisingApp/Sensors/Models/tree_seg_640.onnx

Android takes the same file at `android/app/src/main/assets/models/tree_seg_640.onnx`.
