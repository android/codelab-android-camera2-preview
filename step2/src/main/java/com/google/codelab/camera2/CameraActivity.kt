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

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.google.codelab.camera2.databinding.ActivityCameraBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

const val TAG = "CAMERA_ACTIVITY"
const val CAMERA_PERMISSION_REQUEST_CODE = 1992

open class CameraActivity : AppCompatActivity() {

    /** Main Looper*/
    private val mainLooperHandler = Handler(Looper.getMainLooper())

    /** [HandlerThread] where all camera operations run */
    private val cameraThread = HandlerThread("MyCameraThread").apply { start() }

    /** [Handler] corresponding to [cameraThread] */
    private val cameraHandler = Handler(cameraThread.looper)

    /** hardcoded back camera*/
    private val cameraID = "0"

    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private val cameraManager: CameraManager by lazy {
        applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    /** The [CameraDevice] that will be opened in this Activity */
    private var cameraDevice: CameraDevice? = null

    /** [Surface] target of the CameraCaptureRequest*/
    private var surface: Surface? = null

    /** Where the camera preview is displayed */
    private lateinit var textureView: TextureView

    private lateinit var binding: ActivityCameraBinding

    /** [DisplayManager] to listen to display changes */
    private val displayManager: DisplayManager by lazy {
        applicationContext.getSystemService(DISPLAY_SERVICE) as DisplayManager
    }

    /** Keep track of display rotations*/
    private var displayRotation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)
        textureView = binding.textureView

        val windowParams: WindowManager.LayoutParams = window.attributes
        windowParams.rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_JUMPCUT
        window.attributes = windowParams
    }

    override fun onStart() {
        super.onStart()
        openCameraWithPermissionCheck()
    }

    override fun onDestroy() {
        super.onDestroy()
        release()
        cameraThread.quitSafely()
    }

    private fun openCameraWithPermissionCheck() {
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            openCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE
            )
        }
    }

    @SuppressLint("MissingPermission")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.getOrNull(0) == PackageManager.PERMISSION_GRANTED) {
                openCamera()
            } else {
                finish()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() = lifecycleScope.launch(Dispatchers.Main) {
        cameraManager.openCamera(cameraID, cameraStateCallback, cameraHandler)
    }

    /**
     * Before creating the [CameraCaptureSession] we apply a transformation to the TextureView to
     * avoid distortion in the preview
     */
    private fun createCaptureSession() {
        if (cameraDevice == null || !textureView.isAvailable) return

        val transformedTexture = CameraUtils.buildTargetTexture(
            textureView, cameraManager.getCameraCharacteristics(cameraID),
            displayManager.getDisplay(Display.DEFAULT_DISPLAY).rotation
        )

        this.surface = Surface(transformedTexture)
        try {
            cameraDevice?.createCaptureSession(
                listOf(surface), sessionStateCallback, cameraHandler
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create session.", t)
        }
    }

    private fun release() {
        try {
            surface?.release()
            cameraDevice?.close()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to release resources.", t)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        displayManager.registerDisplayListener(displayListener, mainLooperHandler)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        displayManager.unregisterDisplayListener(displayListener)
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            val difference = displayManager.getDisplay(displayId).rotation - displayRotation
            displayRotation = displayManager.getDisplay(displayId).rotation

            if (difference == 2 || difference == -2) {
                createCaptureSession()
            }
        }
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            try {
                mainLooperHandler.post {
                    if (textureView.isAvailable) {
                        createCaptureSession()
                    }
                    textureView.surfaceTextureListener = surfaceTextureListener
                }
            } catch (t: Throwable) {
                release()
                Log.e(TAG, "Failed to initialize camera.", t)
            }
        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraDevice = camera
            release()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            cameraDevice = camera
            Log.e(TAG, "on Error: $error")
        }
    }

    private val sessionStateCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
            try {
                val captureRequest = cameraDevice?.createCaptureRequest(
                    CameraDevice.TEMPLATE_PREVIEW
                )
                captureRequest?.addTarget(surface!!)
                cameraCaptureSession.setRepeatingRequest(
                    captureRequest?.build()!!, null, cameraHandler
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to open camera preview.", t)
            }
        }

        override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
            Log.e(TAG, "Failed to configure camera.")
        }
    }

    /**
     * Every time the size of the TextureSize changes,
     * we calculate the scale and create a new session
     */
    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            createCaptureSession()
        }

        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            createCaptureSession()
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = false
    }
}
