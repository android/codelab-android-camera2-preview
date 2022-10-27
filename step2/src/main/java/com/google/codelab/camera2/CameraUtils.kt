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

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.util.Size
import android.view.TextureView
import kotlin.math.max

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
     * Computes the relative rotation between the sensor orientation and the display rotation
     */
    private fun computeRelativeRotation(
        characteristics: CameraCharacteristics,
        deviceOrientationDegrees: Int
    ): Int {
        val sensorOrientationDegrees =
            characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

        // Reverse device orientation for front-facing cameras
        val sign = if (characteristics.get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_FRONT
        ) 1 else -1

        return (sensorOrientationDegrees - (deviceOrientationDegrees * sign) + 360) % 360
    }

    /**
     * Returns a new SurfaceTexture with optimized transformation
     */
    fun buildTargetTexture(
        containerView: TextureView,
        characteristics: CameraCharacteristics,
        surfaceRotation: Int
    ): SurfaceTexture? {

        val surfaceRotationDegrees = surfaceRotation * 90
        val windowSize = Size(containerView.width, containerView.height)
        val previewSize = findBestPreviewSize(windowSize, characteristics)
        val sensorOrientation =
            characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val isRotationRequired =
            computeRelativeRotation(characteristics, surfaceRotationDegrees) % 180 != 0

        /* Scale factor required to scale the preview to its original size on the x-axis */
        var scaleX = 1f
        /* Scale factor required to scale the preview to its original size on the y-axis */
        var scaleY = 1f

        if (sensorOrientation == 0) {
            scaleX =
                if (!isRotationRequired) {
                    windowSize.width.toFloat() / previewSize.height
                } else {
                    windowSize.width.toFloat() / previewSize.width
                }

            scaleY =
                if (!isRotationRequired) {
                    windowSize.height.toFloat() / previewSize.width
                } else {
                    windowSize.height.toFloat() / previewSize.height
                }
        } else {
            scaleX =
                if (isRotationRequired) {
                    windowSize.width.toFloat() / previewSize.height
                } else {
                    windowSize.width.toFloat() / previewSize.width
                }

            scaleY =
                if (isRotationRequired) {
                    windowSize.height.toFloat() / previewSize.width
                } else {
                    windowSize.height.toFloat() / previewSize.height
                }
        }

        /* Scale factor required to fit the preview to the TextureView size */
        val finalScale = max(scaleX, scaleY)
        val halfWidth = windowSize.width / 2f
        val halfHeight = windowSize.height / 2f

        val matrix = Matrix()

        if (isRotationRequired) {
            matrix.setScale(
                1 / scaleX * finalScale,
                1 / scaleY * finalScale,
                halfWidth,
                halfHeight
            )
        } else {
            matrix.setScale(
                windowSize.height / windowSize.width.toFloat() / scaleY * finalScale,
                windowSize.width / windowSize.height.toFloat() / scaleX * finalScale,
                halfWidth,
                halfHeight
            )
        }

        // Rotate to compensate display rotation
        matrix.postRotate(
            -surfaceRotationDegrees.toFloat(),
            halfWidth,
            halfHeight
        )

        containerView.setTransform(matrix)

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
