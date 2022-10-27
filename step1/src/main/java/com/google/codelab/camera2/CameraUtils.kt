/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.codelab.camera2

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.util.Size
import android.view.TextureView

object CameraUtils {

    /** Return the biggest preview size available which is smaller than the window */
    private fun findBestPreviewSize(windowSize: Size, characteristics: CameraCharacteristics):
        Size {
            val supportedPreviewSizes: List<Size> =
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(SurfaceTexture::class.java)
                    ?.filter { SizeComparator.compare(it, windowSize) >= 0 }
                    ?.sortedWith(SizeComparator)
                    ?: emptyList()

            return supportedPreviewSizes.getOrElse(0) { Size(0, 0) }
        }

    /**
     * Returns a new SurfaceTexture that will be the target for the camera preview
     */
    fun buildTargetTexture(
        containerView: TextureView,
        characteristics: CameraCharacteristics
    ): SurfaceTexture? {

        /*** Codelab --> Change this function to handle viewfinder rotation and scaling ***/

        val windowSize = Size(containerView.width, containerView.height)
        val previewSize = findBestPreviewSize(windowSize, characteristics)

        return containerView.surfaceTexture?.apply {
            setDefaultBufferSize(previewSize.width, previewSize.height)
        }
    }
}

internal object SizeComparator : Comparator<Size> {
    override fun compare(a: Size, b: Size): Int {
        return b.height * b.width - a.width * a.height
    }
}
