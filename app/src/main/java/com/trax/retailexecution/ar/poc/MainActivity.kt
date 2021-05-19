package com.trax.retailexecution.ar.poc

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.hardware.camera2.*
import android.hardware.camera2.CameraCaptureSession.CaptureCallback
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.*
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicYuvToRGB
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.getSystemService
import com.google.ar.core.*
import com.google.ar.core.ArCoreApk.Availability
import com.google.ar.core.ArCoreApk.InstallStatus
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import com.trax.retailexecution.ar.poc.helpers.DisplayRotationHelper
import com.trax.retailexecution.ar.poc.helpers.TapHelper
import com.trax.retailexecution.ar.poc.helpers.TrackingStateHelper
import com.trax.retailexecution.ar.poc.renderers.BackgroundRenderer
import com.trax.retailexecution.ar.poc.renderers.ObjectRenderer
import com.trax.retailexecution.ar.poc.renderers.PlaneRenderer
import com.trax.retailexecution.ar.poc.renderers.PointCloudRenderer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.Math.abs
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : AppCompatActivity(),
    GLSurfaceView.Renderer,
    ImageReader.OnImageAvailableListener {

    private var TAG = "ARPOC"

    private var thumbnailView: ImageView? = null
    private var surfaceView: GLSurfaceView? = null
    private var cameraDevice: CameraDevice? = null
    private var sharedSession: Session? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraManager: CameraManager? = null
    private var sharedCamera: SharedCamera? = null
    private var cameraId: String? = null
    private var cpuImageReader: ImageReader? = null

    private var previewCaptureRequestBuilder: CaptureRequest.Builder? = null

    private var arCoreActive = false

    private var surfaceCreated = false
    private var captureSessionChangesPossible = true
    private var isGlAttached = false

    private val backgroundRenderer = BackgroundRenderer()
    private val pointCloudRenderer = PointCloudRenderer()
    private val planeRenderer = PlaneRenderer()
    private val virtualObject = ObjectRenderer()

    private val anchors: ArrayList<ColoredAnchor> = ArrayList()
    private class ColoredAnchor(val anchor: Anchor, val color: FloatArray)

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var displayRotationHelper: DisplayRotationHelper? = null
    private var trackingStateHelper: TrackingStateHelper? = null
    private var tapHelper: TapHelper? = null

    private val shouldUpdateSurfaceTexture = AtomicBoolean(false)

    private val safeToExitApp = ConditionVariable()

    private var galleryFolder: File? = null

    private var shouldTakePicture = false

    private val anchorMatrix = FloatArray(16)

    private var latestImageFile: File? = null
    private var latestImageFilePath: String? = null

    private var frame: Frame? = null

    // region Callbacks

    private val cameraDeviceCallback: CameraDevice.StateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(cameraDeviceFromCallback: CameraDevice) {
            cameraDevice = cameraDeviceFromCallback
            createCameraPreviewSession()
        }

        override fun onClosed(cameraDeviceFromCallback: CameraDevice) {
            cameraDevice = null
            safeToExitApp.open()
        }

        override fun onDisconnected(cameraDeviceFromCallback: CameraDevice) {
            cameraDeviceFromCallback.close()
            cameraDevice = null
        }

        override fun onError(cameraDeviceFromCallback: CameraDevice, error: Int) {
            cameraDeviceFromCallback.close()
            cameraDevice = null
            finish()
        }
    }

    private var cameraSessionStateCallback: CameraCaptureSession.StateCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            captureSession = session
            setRepeatingCaptureRequest()
        }

        override fun onSurfacePrepared(session: CameraCaptureSession, surface: Surface) { }

        override fun onReady(session: CameraCaptureSession) { }

        override fun onActive(session: CameraCaptureSession) {
            resumeARCore()
            captureSessionChangesPossible = true
        }

        override fun onCaptureQueueEmpty(session: CameraCaptureSession) { }

        override fun onClosed(session: CameraCaptureSession) { }

        override fun onConfigureFailed(session: CameraCaptureSession) { }
    }

    private val cameraCaptureCallback: CaptureCallback = object : CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            shouldUpdateSurfaceTexture.set(true)
        }

        override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) { }

        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) { }

        override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) { }
    }

    // endregion

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.glsurfaceview)
        thumbnailView = findViewById(R.id.iv_thumbnail)
        surfaceView?.preserveEGLContextOnPause = true
        surfaceView?.setEGLContextClientVersion(2)
        surfaceView?.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surfaceView?.setRenderer(this)
        surfaceView?.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        displayRotationHelper = DisplayRotationHelper(this)
        trackingStateHelper = TrackingStateHelper(this)
        tapHelper = TapHelper(this)
        surfaceView?.setOnTouchListener(tapHelper)

        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE), 0)
        createImageGallery()
    }

    override fun onResume() {
        super.onResume()

        waitUntilCameraCaptureSessionIsActive()
        startBackgroundThread()
        surfaceView?.onResume()

        if (surfaceCreated) {
            openCamera()
        }

        displayRotationHelper?.onResume()
    }

    override fun onPause() {
        shouldUpdateSurfaceTexture?.set(false)
        surfaceView!!.onPause()
        waitUntilCameraCaptureSessionIsActive()
        displayRotationHelper?.onPause()
        pauseARCore()
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    override fun onDestroy() {
        if (sharedSession != null) {
            sharedSession!!.close()
            sharedSession = null
        }

        super.onDestroy()
    }

    // region GLSurfaceView.Renderer Implementation

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        if (!shouldUpdateSurfaceTexture.get()) {
            return
        }

        displayRotationHelper?.updateSessionIfNeeded(sharedSession!!);

        try {
            onDrawFrameARCore()
        } catch (t: Throwable) { }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        displayRotationHelper?.onSurfaceChanged(width, height);
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        surfaceCreated = true
        GLES20.glClearColor(0f, 0f, 0f, 1.0f)

        try {
            backgroundRenderer.createOnGlThread(this)
            planeRenderer.createOnGlThread(this, "models/trigrid.png")
            pointCloudRenderer.createOnGlThread(this)
            virtualObject.createOnGlThread(this, "models/andy.obj", "models/andy.png")
            openCamera()
        } catch (e: IOException) { }
    }

    // endregion

    // region ImageReader.OnImageAvailableListener Implementation

    override fun onImageAvailable(reader: ImageReader?) {
        val image: Image = reader!!.acquireLatestImage() ?: return

        if (shouldTakePicture) {
            shouldTakePicture = false
            processImageAndShowThumbnail(image)
            getCameraPose()
            getPhotoEdgesOnPlane()
            getPointCloudPoints()
        }

        image.close()
    }

    // endregion

    private fun openCamera() {
        if (cameraDevice != null) {
            return
        }

        if (!isARCoreSupportedAndUpToDate()) {
            return
        }

        if (sharedSession == null) {
            try {
                sharedSession = Session(this, EnumSet.of(Session.Feature.SHARED_CAMERA))
            } catch (e: UnavailableException) {
                Log.e(TAG, e.toString())
                return
            }

            val config: Config = sharedSession!!.config
            config.focusMode = Config.FocusMode.AUTO
            config.planeFindingMode = Config.PlaneFindingMode.VERTICAL
            sharedSession?.configure(config)
        }

        sharedCamera = sharedSession?.sharedCamera
        cameraId = sharedSession?.cameraConfig?.cameraId

        try {
            cameraManager = this.getSystemService(Context.CAMERA_SERVICE) as CameraManager

            val imageCaptureSize = getCameraCaptureSize()
            cpuImageReader = ImageReader.newInstance(imageCaptureSize.width, imageCaptureSize.height, ImageFormat.YUV_420_888, 2)
            cpuImageReader?.setOnImageAvailableListener(this, backgroundHandler)
            sharedCamera?.setAppSurfaces(cameraId, listOf(cpuImageReader?.surface))

            val wrappedCallback: CameraDevice.StateCallback = sharedCamera!!.createARDeviceStateCallback(cameraDeviceCallback, backgroundHandler)

            captureSessionChangesPossible = false
            cameraManager!!.openCamera(cameraId ?: "", wrappedCallback, backgroundHandler)
        } catch (e: SecurityException) { }
    }

    private fun closeCamera() {
        if (captureSession != null) {
            captureSession!!.close()
            captureSession = null
        }
        if (cameraDevice != null) {
            waitUntilCameraCaptureSessionIsActive()
            safeToExitApp.close()
            cameraDevice!!.close()
            safeToExitApp.block()
        }
        if (cpuImageReader != null) {
            cpuImageReader!!.close()
            cpuImageReader = null
        }
    }

    private fun createCameraPreviewSession() {
        try {
            sharedSession!!.setCameraTextureName(backgroundRenderer.textureId)

            previewCaptureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)

            val surfaceList = sharedCamera!!.arCoreSurfaces
            surfaceList.add(cpuImageReader!!.surface)

            for (surface in surfaceList) {
                previewCaptureRequestBuilder?.addTarget(surface)
            }

            val wrappedCallback = sharedCamera!!.createARSessionStateCallback(cameraSessionStateCallback, backgroundHandler)

            cameraDevice!!.createCaptureSession(surfaceList, wrappedCallback, backgroundHandler)
        } catch (e: CameraAccessException) { }
    }

    @Synchronized
    private fun waitUntilCameraCaptureSessionIsActive() {
        while (!captureSessionChangesPossible) {
            try {
                Thread.sleep(4000)
            } catch (e: InterruptedException) { }
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("sharedCameraHandler")
        backgroundThread?.start()
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread!!.quitSafely()
            try {
                backgroundThread!!.join()
                backgroundThread = null
                backgroundHandler = null
            } catch (e: InterruptedException) { }
        }
    }

    private fun resumeARCore() {
        if (sharedSession == null) {
            return
        }
        if (!arCoreActive) {
            try {
                backgroundRenderer.suppressTimestampZeroRendering(false)

                sharedSession!!.resume()
                arCoreActive = true

                sharedCamera!!.setCaptureCallback(cameraCaptureCallback, backgroundHandler)
            } catch (e: CameraNotAvailableException) {
                return
            }
        }
    }

    private fun pauseARCore() {
        if (arCoreActive) {
            sharedSession!!.pause()
            arCoreActive = false
        }
    }

    private fun setRepeatingCaptureRequest() {
        try {
            previewCaptureRequestBuilder?.set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_SEPIA)
            captureSession?.setRepeatingRequest(previewCaptureRequestBuilder!!.build(), cameraCaptureCallback, backgroundHandler)
        } catch (e: CameraAccessException) { }
    }

    private fun onDrawFrameARCore() {
        if (!arCoreActive) {
            return
        }

        frame = sharedSession!!.update()
        frame?.let {
            val camera = it.camera

            isGlAttached = true

            handleTap(it, camera)

            backgroundRenderer.draw(it)
            trackingStateHelper?.updateKeepScreenOnFlag(camera.trackingState)

            if (camera.trackingState == TrackingState.PAUSED) {
                return
            }

            val projmtx = FloatArray(16)
            camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f)

            val viewmtx = FloatArray(16)
            camera.getViewMatrix(viewmtx, 0)

            val colorCorrectionRgba = FloatArray(4)
            it.lightEstimate.getColorCorrection(colorCorrectionRgba, 0)
            it.acquirePointCloud()?.use { it ->
                pointCloudRenderer.update(it)
                // Comment out to hide the pointcloud / don't draw it.
                pointCloudRenderer.draw(viewmtx, projmtx)
            }

            // Comment out to hide the plane / don't draw it.
            planeRenderer.drawPlanes(sharedSession!!.getAllTrackables(Plane::class.java), camera.displayOrientedPose, projmtx)

            val scaleFactor = 1.0f
            for (coloredAnchor in anchors) {
                if (coloredAnchor.anchor.getTrackingState() != TrackingState.TRACKING) {
                    continue
                }

                coloredAnchor.anchor.pose.toMatrix(anchorMatrix, 0)

                virtualObject.updateModelMatrix(anchorMatrix, scaleFactor)
                virtualObject.draw(viewmtx, projmtx, colorCorrectionRgba, coloredAnchor.color)
            }
        }
    }

    private fun isARCoreSupportedAndUpToDate(): Boolean {
        when (ArCoreApk.getInstance().checkAvailability(this)) {
            Availability.SUPPORTED_INSTALLED -> { }
            Availability.SUPPORTED_APK_TOO_OLD, Availability.SUPPORTED_NOT_INSTALLED -> try {
                val installStatus = ArCoreApk.getInstance().requestInstall(this, true)
                when (installStatus) {
                    InstallStatus.INSTALL_REQUESTED -> {
                        return false
                    }
                    InstallStatus.INSTALLED -> { }
                }
            } catch (e: UnavailableException) {
                runOnUiThread {
                    Toast.makeText(applicationContext, "ARCore not installed\n$e", Toast.LENGTH_LONG).show()
                }
                finish()
                return false
            }
            Availability.UNKNOWN_ERROR, Availability.UNKNOWN_CHECKING, Availability.UNKNOWN_TIMED_OUT, Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> {
                runOnUiThread {
                    Toast.makeText(applicationContext,"ARCore is not supported on this device",Toast.LENGTH_LONG).show()
                }
                return false
            }
        }
        return true
    }

    fun takePicture(v: View) {
        shouldTakePicture = true
    }

    @Throws(IOException::class)
    private fun createImageFile(galleryFolder: File): File? {
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val imageFileName = "image_" + timeStamp + "_"
        latestImageFile = File.createTempFile(imageFileName, ".jpg", galleryFolder)
        latestImageFilePath = latestImageFile?.absolutePath
        return latestImageFile
    }

    private fun createImageGallery() {
        val storageDirectory: File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        galleryFolder = File(storageDirectory, resources.getString(R.string.app_name))
        if (!galleryFolder?.exists()!!) {
            val wasCreated: Boolean = galleryFolder?.mkdirs() ?: false
            if (!wasCreated) {
                Log.e(TAG, "Failed to create directory")
            }
        }
    }

    private fun processImageAndShowThumbnail(image: Image) {
        var outputPhoto: FileOutputStream? = null
        try {
            outputPhoto = FileOutputStream(createImageFile(galleryFolder!!))

            val yuvBytes = imageToByteBuffer(image)

            val rs = RenderScript.create(this)

            val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            val allocationRgb = Allocation.createFromBitmap(rs, bitmap)

            val allocationYuv = Allocation.createSized(rs, Element.U8(rs), yuvBytes!!.array().size)
            allocationYuv.copyFrom(yuvBytes.array())

            val scriptYuvToRgb = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))
            scriptYuvToRgb.setInput(allocationYuv)
            scriptYuvToRgb.forEach(allocationRgb)

            allocationRgb.copyTo(bitmap)

            allocationYuv.destroy()
            allocationRgb.destroy()
            rs.destroy()

            bitmap?.compress(Bitmap.CompressFormat.JPEG, 100, outputPhoto)

            val bitmapWithCorrectRotation = rotateBitmap(bitmap)

            this@MainActivity.runOnUiThread {
                thumbnailView?.setImageBitmap(bitmapWithCorrectRotation!!)
            }


        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                outputPhoto?.close()
            } catch (e: IOException) { }
        }
    }

    private fun imageToByteBuffer(image: Image): ByteBuffer? {
        val crop: Rect = image.cropRect
        val width: Int = crop.width()
        val height: Int = crop.height()
        val planes = image.planes
        val rowData = ByteArray(planes[0].rowStride)
        val bufferSize = width * height * ImageFormat.getBitsPerPixel(ImageFormat.YUV_420_888) / 8
        val output = ByteBuffer.allocateDirect(bufferSize)
        var channelOffset = 0
        var outputStride = 0
        for (planeIndex in 0..2) {
            if (planeIndex == 0) {
                channelOffset = 0
                outputStride = 1
            } else if (planeIndex == 1) {
                channelOffset = width * height + 1
                outputStride = 2
            } else if (planeIndex == 2) {
                channelOffset = width * height
                outputStride = 2
            }
            val buffer = planes[planeIndex].buffer
            val rowStride = planes[planeIndex].rowStride
            val pixelStride = planes[planeIndex].pixelStride
            val shift = if (planeIndex == 0) 0 else 1
            val widthShifted = width shr shift
            val heightShifted = height shr shift
            buffer.position(rowStride * (crop.top shr shift) + pixelStride * (crop.left shr shift))
            for (row in 0 until heightShifted) {
                val length: Int
                if (pixelStride == 1 && outputStride == 1) {
                    length = widthShifted
                    buffer[output.array(), channelOffset, length]
                    channelOffset += length
                } else {
                    length = (widthShifted - 1) * pixelStride + 1
                    buffer[rowData, 0, length]
                    for (col in 0 until widthShifted) {
                        output.array()[channelOffset] = rowData[col * pixelStride]
                        channelOffset += outputStride
                    }
                }
                if (row < heightShifted - 1) {
                    buffer.position(buffer.position() + rowStride - length)
                }
            }
        }
        return output
    }

    private fun rotateBitmap(bitmap: Bitmap): Bitmap? {
        if (latestImageFilePath != null) {

            val ei = ExifInterface(latestImageFilePath!!)
            val orientation: Int = ei.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)

            var angle = 0f
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> angle = 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> angle = 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> angle = 270f
                ExifInterface.ORIENTATION_NORMAL -> angle = 0f
                ExifInterface.ORIENTATION_UNDEFINED -> angle = 90f
            }

            val matrix = Matrix()
            matrix.postRotate(angle)
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            return null
        }
    }

    private fun handleTap(frame: Frame, camera: Camera) {
        val tap: MotionEvent? = tapHelper?.poll()
        if (tap != null && camera.trackingState == TrackingState.TRACKING) {
            for (hit in frame.hitTest(tap)) {
                val trackable = hit.trackable
                if ((trackable is Plane && trackable.isPoseInPolygon(hit.hitPose) && planeRenderer.calculateDistanceToPlane(hit.hitPose, camera.pose) > 0) || (trackable is Point && trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL)) {
                    if (anchors.size >= 20) {
                        anchors.get(0).anchor.detach()
                        anchors.removeAt(0)
                    }

                    val objColor: FloatArray = if (trackable is Point) {
                        floatArrayOf(66.0f, 133.0f, 244.0f, 255.0f)
                    } else if (trackable is Plane) {
                        floatArrayOf(139.0f, 195.0f, 74.0f, 255.0f)
                    } else {
                        floatArrayOf(0f, 0f, 0f, 0f)
                    }

                    anchors.add(ColoredAnchor(hit.createAnchor(), objColor))
                    break
                }
            }
        }
    }

    // region Get 3D data.

    private fun getCameraPose() {
        frame?.let {
            val cameraPose = it.camera.pose
            Log.i(TAG,
                    "CameraPoseX: ${cameraPose.tx()}, " +
                    "CameraPoseY: ${cameraPose.ty()}, " +
                    "CameraPoseZ: ${cameraPose.tz()}, " +
                    "Rotation: ${cameraPose.qx()} - ${cameraPose.qy()} - ${cameraPose.qz()} - ${cameraPose.qw()}")

            val cameraOrientedPose = it.camera.displayOrientedPose
            Log.i(TAG,
                    "CameraOrientedPoseX: ${cameraOrientedPose.tx()}, " +
                            "CameraOrientedPoseY: ${cameraOrientedPose.ty()}, " +
                            "CameraOrientedPoseZ: ${cameraOrientedPose.tz()}, " +
                            "Rotation: ${cameraOrientedPose.qx()} - ${cameraOrientedPose.qy()} - ${cameraOrientedPose.qz()} - ${cameraOrientedPose.qw()}")


            // For checking the result:
            // Puts and object in front of the camera (center of the screen)
            // The extractTranslation() results that the anchor is oriented based on the device orientation instead of the camera orientation.
            if (it.camera.trackingState == TrackingState.TRACKING) {
                var newAnchor = sharedSession?.createAnchor(cameraPose.extractTranslation())
                newAnchor?.let { anchor ->
                    anchors.add(ColoredAnchor(anchor, floatArrayOf(209.0f, 91.0f, 23.0f, 255.0f)))
                }
            }
        }
    }

    private fun getPhotoEdgesOnPlane() {
        frame?.let {
            val displayMetrics = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(displayMetrics)
            val screenHeight = displayMetrics.heightPixels
            val screenWidth = displayMetrics.widthPixels
            for (hit in it.hitTest(0f, 0f)) {
                val trackable = hit.trackable
                // Could add additional condition: && planeRenderer.calculateDistanceToPlane(hit.hitPose, camera.pose).
                if ((trackable is Plane && trackable.isPoseInPolygon(hit.hitPose))) {
                    val upperLeftPose = hit.hitPose
                    Log.i(TAG,
                            "UpperLeftCornerPoseX: ${upperLeftPose.tx()}, " +
                                    "UpperLeftCornerPoseY: ${upperLeftPose.ty()}, " +
                                    "UpperLeftCornerPoseZ: ${upperLeftPose.tz()}, " +
                                    "Rotation: ${upperLeftPose.qx()} - ${upperLeftPose.qy()} - ${upperLeftPose.qz()} - ${upperLeftPose.qw()}")

                    // For checking the result:
                    // Puts and object to the upper left corner.
                    anchors.add(ColoredAnchor(hit.createAnchor(), floatArrayOf(14.0f, 57.0f, 230.0f, 255.0f)))
                    break
                }
            }

            for (hit in it.hitTest(screenWidth.toFloat(), 0f)) {
                val trackable = hit.trackable
                // Could add additional condition: && planeRenderer.calculateDistanceToPlane(hit.hitPose, camera.pose).
                if ((trackable is Plane && trackable.isPoseInPolygon(hit.hitPose))) {
                    val upperRightPose = hit.hitPose
                    Log.i(TAG,
                            "UpperRightCornerPoseX: ${upperRightPose.tx()}, " +
                                    "UpperRightCornerPoseY: ${upperRightPose.ty()}, " +
                                    "UpperRightCornerPoseZ: ${upperRightPose.tz()}, " +
                                    "Rotation: ${upperRightPose.qx()} - ${upperRightPose.qy()} - ${upperRightPose.qz()} - ${upperRightPose.qw()}")

                    // For checking the result:
                    // Puts and object to the upper right corner.
                    anchors.add(ColoredAnchor(hit.createAnchor(), floatArrayOf(14.0f, 57.0f, 230.0f, 255.0f)))
                    break
                }
            }

            for (hit in it.hitTest(0f, screenHeight.toFloat())) {
                val trackable = hit.trackable
                // Could add additional condition: && planeRenderer.calculateDistanceToPlane(hit.hitPose, camera.pose).
                if ((trackable is Plane && trackable.isPoseInPolygon(hit.hitPose))) {
                    val lowerLeftPose = hit.hitPose
                    Log.i(TAG,
                            "LowerLeftCornerPoseX: ${lowerLeftPose.tx()}, " +
                                    "LowerUpperLeftCornerPoseY: ${lowerLeftPose.ty()}, " +
                                    "LowerUpperLeftCornerPoseZ: ${lowerLeftPose.tz()}, " +
                                    "Rotation: ${lowerLeftPose.qx()} - ${lowerLeftPose.qy()} - ${lowerLeftPose.qz()} - ${lowerLeftPose.qw()}")

                    // For checking the result:
                    // Puts and object to the lower left corner.
                    anchors.add(ColoredAnchor(hit.createAnchor(), floatArrayOf(14.0f, 57.0f, 230.0f, 255.0f)))
                    break
                }
            }

            for (hit in it.hitTest(screenWidth.toFloat(), screenHeight.toFloat())) {
                val trackable = hit.trackable
                // Could add additional condition: && planeRenderer.calculateDistanceToPlane(hit.hitPose, camera.pose).
                if ((trackable is Plane && trackable.isPoseInPolygon(hit.hitPose))) {
                    val lowerRightPose = hit.hitPose
                    Log.i(TAG,
                            "LowerRightCornerPoseX: ${lowerRightPose.tx()}, " +
                                    "LowerRightCornerPoseY: ${lowerRightPose.ty()}, " +
                                    "LowerRightCornerPoseZ: ${lowerRightPose.tz()}, " +
                                    "Rotation: ${lowerRightPose.qx()} - ${lowerRightPose.qy()} - ${lowerRightPose.qz()} - ${lowerRightPose.qw()}")

                    // For checking the result:
                    // Puts and object to the lower right corner.
                    anchors.add(ColoredAnchor(hit.createAnchor(), floatArrayOf(14.0f, 57.0f, 230.0f, 255.0f)))
                    break
                }
            }
        }
    }

    private fun getPointCloudPoints() {
        frame?.let {
            it.acquirePointCloud()?.use { pointCloud ->
                val floatBuffer = pointCloud.points
                try {
                    while (floatBuffer.hasRemaining()) {
                        val x: Float = floatBuffer.get()
                        val y: Float = floatBuffer.get()
                        val z: Float = floatBuffer.get()
                        val c: Float = floatBuffer.get()
                        Log.i(TAG, "X: $x, Y: $y, Z: $z, Confidence: $c ")
                    }
                } catch (e: Exception) {
                    Log.i(TAG, "Exception: $e")
                }
            }
        }
    }

    // endregion

    private fun getCameraCaptureSize(): Size {
        fun Size.getResolution(): Int {
            return width * height
        }

        fun Size.getAspectRatio(): Float {
            return if (width == 0 || height == 0) 0f
            else height.toFloat() / width.toFloat()
        }

        val cameraCharacteristics = cameraManager!!.getCameraCharacteristics(cameraId!!)
        val configurationMap = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val outputSizes = configurationMap.getOutputSizes(ImageFormat.YUV_420_888).toMutableList()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            configurationMap.getHighResolutionOutputSizes(ImageFormat.YUV_420_888)?.let {
                outputSizes.addAll(it)
            }
        }

        var shouldFlip = false
        for (outputSize in outputSizes) {
            if (outputSize.width > outputSize.height) {
                shouldFlip = true
            }
        }
        val availableSizes = if (shouldFlip) {
            var myList = mutableListOf<Size>()
            for (outputSize in outputSizes) {
                myList.add(Size(outputSize.height, outputSize.width))
            }
            myList
        } else {
            outputSizes
        }

        val screenSizePoint = android.graphics.Point()
        getSystemService<WindowManager>()!!.defaultDisplay.getSize(screenSizePoint)
        val screenSize = Size(screenSizePoint.x, screenSizePoint.y)

        var selectedSize = Size(0, 0)
        availableSizes.filter { it.getResolution() > PREFERRED_RESOLUZION }
            .forEach {
                if (it.getAspectRatio() <= ASPECT_RATIO_LIMIT) {
                    val currentAspectRatioDelta = abs(screenSize.getAspectRatio() - it.getAspectRatio())
                    val selectedAspectRatioDelta = abs(screenSize.getAspectRatio() - selectedSize.getAspectRatio())

                    // if the current aspect ratio is closer to the screen's aspect ratio
                    if (currentAspectRatioDelta < selectedAspectRatioDelta) {
                        selectedSize = it

                        // if the current aspect ratio is identical to the selected one
                    } else if (currentAspectRatioDelta == selectedAspectRatioDelta) {
                        val currentResolutionDelta = abs(PREFERRED_RESOLUZION - it.getResolution())
                        val selectedResolutionDelta = abs(PREFERRED_RESOLUZION - selectedSize.getResolution())

                        if (currentResolutionDelta < selectedResolutionDelta) {
                            selectedSize = it
                        }
                    }
                }
            }

        if (selectedSize.getResolution() == 0) {
            selectedSize = availableSizes.maxBy { it.getResolution() } ?: selectedSize
        }

        return selectedSize
    }

    companion object {
        private const val ASPECT_RATIO_LIMIT = 16F/9F
        private const val PREFERRED_RESOLUZION = 8 * 1000 * 1000
    }
}