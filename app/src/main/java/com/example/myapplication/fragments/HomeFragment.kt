package com.example.myapplication.fragments

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import com.example.myapplication.R
import com.example.myapplication.constants.Constants
import com.example.myapplication.databinding.FragmentHomeBinding
import com.example.myapplication.fragments.views.OverlayView
import com.example.myapplication.ultils.PermissionUtil.allPermissionsGranted
import com.example.myapplication.ultils.uiThreadOperations
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import vn.cmcati.core.camera.CameraController
import vn.cmcati.core.detector.FaceDetector
import vn.cmcati.core.detector.FaceLivenessDetector
import vn.cmcati.core.models.BoundingBox
import vn.cmcati.core.models.LivenessResult
import vn.cmcati.core.tracking.BoundingBoxTracker
import vn.cmcati.core.tracking.TrackedObjectHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import android.media.ThumbnailUtils

class HomeFragment : Fragment(), CameraController.CameraListener, FaceLivenessDetector.FaceLivenessDetectorListener, FaceDetector.FaceDetectorListener {
    private var isFrontCamera = true
    private lateinit var faceCamera: CameraController
    private lateinit var binding: FragmentHomeBinding
    private lateinit var faceExecutor: ThreadPoolExecutor
    private lateinit var livenessExecutor: ThreadPoolExecutor
    private var faceDetector: FaceDetector? = null
    private var livenessDetector: FaceLivenessDetector? = null
    private var tracker: BoundingBoxTracker? = null
    private var overlayView: OverlayView? = null

    private lateinit var tfliteInterpreter: Interpreter
    private var nnApiDelegate: NnApiDelegate? = null
    private var gpuDelegate: GpuDelegate? = null

    private var inputScale = 4.464608f
    private var inputZeroPoint = 0
    private var outputScale = 0.00390625f
    private var outputZeroPoint = 0

    private val outputBuffer = ByteBuffer.allocateDirect(1).apply { order(ByteOrder.nativeOrder()) }
    private val imageProcessor = ImageProcessor.Builder()
        .add(ResizeOp(224, 224, ResizeOp.ResizeMethod.BILINEAR))
        .build()

    private var frameCounter = 0
    private val INFERENCE_FREQUENCY = 15 // Giảm xuống để kiểm tra detect thường xuyên hơn

    private var frameCount = 0
    private var lastFpsUpdateTime = System.currentTimeMillis()
    private var realTimeFps = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate called")
        tracker = BoundingBoxTracker(maxLostFrames = 50)

        faceExecutor = ThreadPoolExecutor(1, 1, 60L, TimeUnit.SECONDS, LinkedBlockingQueue())
        livenessExecutor = ThreadPoolExecutor(1, 2, 60L, TimeUnit.SECONDS, LinkedBlockingQueue())

        faceExecutor.submit {
            try {
                Log.d(TAG, "Initializing models...")
                faceDetector = FaceDetector(requireActivity(), Constants.FACE_DETECTION_MODEL, this)
                livenessDetector = FaceLivenessDetector(requireActivity(), Constants.LIVENESS_FACE_MODE, this)
                initializeInterpreter()
                Log.d(TAG, "Models initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing models: ${e.message}", e)
            }
        }
    }

    private fun initializeInterpreter() {
        try {
            Log.d(TAG, "Loading model file: ${Constants.LIVENESS_FACE_MODE}")
            val model = FileUtil.loadMappedFile(requireActivity(), Constants.LIVENESS_FACE_MODE)
            val options = Interpreter.Options()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    val nnApiOptions = NnApiDelegate.Options().apply { allowFp16 = true }
                    nnApiDelegate = NnApiDelegate(nnApiOptions)
                    options.addDelegate(nnApiDelegate)
                    Log.d(TAG, "NNAPI Delegate added")
                } catch (e: Exception) {
                    Log.w(TAG, "NNAPI unavailable: ${e.message}")
                    gpuDelegate = GpuDelegate(GpuDelegate.Options().setPrecisionLossAllowed(true))
                    options.addDelegate(gpuDelegate)
                    Log.d(TAG, "GPU Delegate added")
                }
            }

            if (nnApiDelegate == null && gpuDelegate == null) {
                options.setNumThreads(4).setUseXNNPACK(true)
                Log.d(TAG, "Using 4 CPU threads with XNNPACK")
            }

            tfliteInterpreter = Interpreter(model, options)
            Log.d(TAG, "Interpreter initialized")

            val inputTensor = tfliteInterpreter.getInputTensor(0)
            val outputTensor = tfliteInterpreter.getOutputTensor(0)
            inputScale = inputTensor.quantizationParams().scale
            inputZeroPoint = inputTensor.quantizationParams().zeroPoint.toInt()
            outputScale = outputTensor.quantizationParams().scale
            outputZeroPoint = outputTensor.quantizationParams().zeroPoint.toInt()
            Log.d(TAG, "Quantization params: inputScale=$inputScale, inputZeroPoint=$inputZeroPoint, outputScale=$outputScale, outputZeroPoint=$outputZeroPoint")

            warmupInterpreter()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing interpreter: ${e.message}", e)
        }
    }

    private fun warmupInterpreter() {
        try {
            val dummyImage = TensorImage.fromBitmap(Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888))
            val processedImage = imageProcessor.process(dummyImage)
            for (i in 0 until 5) {
                outputBuffer.rewind()
                tfliteInterpreter.run(processedImage.buffer, outputBuffer)
            }
            Log.d(TAG, "Interpreter warmup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error during warmup: ${e.message}", e)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        Log.d(TAG, "onCreateView called")
        binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated called")
        faceCamera = CameraController(binding.viewFinder, requireActivity(), this)
        binding.cameraPosition.isChecked = isFrontCamera
        binding.cameraPosition.setOnCheckedChangeListener { _, b ->
            isFrontCamera = b
            binding.cameraPositionName.text = if (isFrontCamera) resources.getText(R.string.front_camera) else resources.getText(R.string.back_camera)
            faceCamera.startCamera(isFrontCamera)
        }
        if (allPermissionsGranted(requireActivity(), REQUIRED_PERMISSIONS)) {
            Log.d(TAG, "Permissions granted, starting camera")
            faceCamera.startCamera(isFrontCamera)
        } else {
            Log.d(TAG, "Requesting permissions")
            ActivityCompat.requestPermissions(requireActivity(), REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
        overlayView = binding.overlay
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it[Manifest.permission.CAMERA] == true) {
            Log.d(TAG, "Permission granted via launcher, starting camera")
            faceCamera.startCamera(isFrontCamera)
        } else {
            Log.e(TAG, "Camera permission denied")
        }
    }

    override fun onEmptyLivenessDetected() {
        Log.d(TAG, "onEmptyLivenessDetected: No liveness detected")
    }

    override fun onLivenessDetected(livenessResult: LivenessResult, inferenceTime: Long) {
        Log.d(TAG, "onLivenessDetected: Score=${livenessResult.livenessScore}, Time=${inferenceTime}ms")
        handleListTrackedObject(livenessResult)
    }

    private fun handleListTrackedObject(trackedObj: LivenessResult) {
        val index = trackedObjetData.getListTrackedObj().indexOfFirst { it.id == trackedObj.id }
        if (index != -1) {
            trackedObjetData.replaceTrackObjWithIndex(index, trackedObj)
        } else {
            trackedObjetData.addTrackObj(trackedObj)
        }
    }

    private var trackedObjetData: TrackedObjectHelper = TrackedObjectHelper()

    override fun onEmptyFaceDetected() {
        Log.d(TAG, "onEmptyFaceDetected: No faces detected")
        uiThreadOperations(requireActivity()) {
            overlayView?.clear()
        }
    }

    override fun onFaceDetected(frame: Bitmap, boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        Log.d(TAG, "onFaceDetected: ${boundingBoxes.size} faces detected, Time=${inferenceTime}ms")
        val livenessResults = tracker?.update(boundingBoxes)
        livenessExecutor.submit {
            livenessResults?.forEach { livenessResult ->
                val startTime = System.currentTimeMillis()
                val score = runLivenessInference(frame, livenessResult.boundingBox)
                livenessResult.livenessScore = score
                Log.d(TAG, "Liveness score: $score, Inference time: ${System.currentTimeMillis() - startTime}ms")
            }
        }
        if (livenessResults != null) {
            trackedObjetData.setListTrackedObj(livenessResults)
        }
        drawBoundBox(trackedObjetData.getListTrackedObj().filter { it.lostFrames == 0 }, inferenceTime)
    }

    private fun runLivenessInference(frame: Bitmap, box: BoundingBox): Float {
        try {
            val startTime = System.currentTimeMillis()
            val x = (box.x1 * frame.width).toInt().coerceIn(0, frame.width)
            val y = (box.y1 * frame.height).toInt().coerceIn(0, frame.height)
            val width = ((box.x2 - box.x1) * frame.width).toInt().coerceIn(0, frame.width - x)
            val height = ((box.y2 - box.y1) * frame.height).toInt().coerceIn(0, frame.height - y)

            val croppedBitmap = Bitmap.createBitmap(frame, x, y, width, height)
            val tensorImage = TensorImage.fromBitmap(croppedBitmap)
            val processedImage = imageProcessor.process(tensorImage)

            outputBuffer.rewind()
            tfliteInterpreter.run(processedImage.buffer, outputBuffer)
            outputBuffer.rewind()

            val rawOutput = outputBuffer.get().toInt() and 0xFF
            val livenessScore = (rawOutput - outputZeroPoint) * outputScale

            Log.d(TAG, "runLivenessInference: Score=$livenessScore, Time=${System.currentTimeMillis() - startTime}ms")
            return livenessScore
        } catch (e: Exception) {
            Log.e(TAG, "Error in runLivenessInference: ${e.message}", e)
            return 0f
        }
    }

    private fun drawBoundBox(trackedObjects: List<LivenessResult>, inferenceTime: Long) {
        uiThreadOperations(requireActivity()) {
            Log.d(TAG, "drawBoundBox: Drawing ${trackedObjects.size} objects")
            binding.inferenceTime.text = "FPS: ${String.format("%.1f", realTimeFps)} | INF: ${inferenceTime}ms"
            overlayView?.setResults(trackedObjects.take(5))
        }
    }

    override fun onCameraResult(frame: Bitmap) {
        Log.d(TAG, "onCameraResult: Frame received, size=${frame.width}x${frame.height}")
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsUpdateTime > 1000) {
            realTimeFps = frameCount * 1000.0 / (now - lastFpsUpdateTime)
            frameCount = 0
            lastFpsUpdateTime = now
            Log.d(TAG, "FPS updated: $realTimeFps")
        }

        val resizedFrame = ThumbnailUtils.extractThumbnail(frame, 640, 480)
        frameCounter++
        if (frameCounter % INFERENCE_FREQUENCY == 0) {
            faceExecutor.submit {
                try {
                    Log.d(TAG, "Submitting face detection task")
                    faceDetector?.detect(resizedFrame) ?: Log.e(TAG, "Face detector is null")
                } catch (e: Exception) {
                    Log.e(TAG, "Error in face detection: ${e.message}", e)
                }
            }
        } else if (trackedObjetData.getListTrackedObj().isNotEmpty()) {
            drawBoundBox(
                trackedObjetData.getListTrackedObj().filter { it.lostFrames < INFERENCE_FREQUENCY },
                0
            )
        }
    }

    override fun onCameraFailed(e: Exception) {
        Log.e(TAG, "onCameraFailed: ${e.message}", e)
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume called")
        if (allPermissionsGranted(requireActivity(), REQUIRED_PERMISSIONS)) {
            faceCamera.startCamera(isFrontCamera)
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called")
        livenessDetector?.close()
        faceDetector?.close()
        tfliteInterpreter.close()
        nnApiDelegate?.close()
        gpuDelegate?.close()
        faceExecutor.shutdown()
        livenessExecutor.shutdown()
        faceCamera.closeCamera()
    }

    companion object {
        val TAG = "HomeFragment"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}