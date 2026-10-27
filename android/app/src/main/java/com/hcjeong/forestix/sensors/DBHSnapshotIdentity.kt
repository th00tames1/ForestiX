package com.hcjeong.forestix.sensors

/** Same AR frame association, not a claim of simultaneous sensor exposures. */
object DBHSnapshotIdentity {
    val processingWorker = com.hcjeong.forestix.common.DeadlineWorkExecutor()
    fun matches(frame:ArDepthFrame,image:RawCapturedImage?):Boolean = image!=null &&
        DbhDepthInputPolicy.acceptsFrame(frame) &&
        frame.frameTimestampNanos>0 && image.sourceFrameId=="arcore_frame_ns:${frame.frameTimestampNanos}" &&
        image.cameraFrameSeconds==frame.frameTimestampNanos/1e9 && frame.depthFromViewAffine!=null &&
        image.viewToImage?.let{it.size==6&&it.all{v->v.isFinite()}}==true
}
