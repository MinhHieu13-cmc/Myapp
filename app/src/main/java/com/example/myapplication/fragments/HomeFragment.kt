package com.example.myapplication.fragments

import android.Manifest
import android.graphics.Bitmap
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.cmcati.core.camera.CameraController
import vn.cmcati.core.detector.FaceDetector
import vn.cmcati.core.detector.FaceLivenessDetector
import vn.cmcati.core.models.BoundingBox
import vn.cmcati.core.models.LivenessResult
import vn.cmcati.core.tracking.BoundingBoxTracker
import vn.cmcati.core.tracking.TrackedObjectHelper
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class HomeFragment : Fragment(), CameraController.CameraListener, FaceLivenessDetector.FaceLivenessDetectorListener, FaceDetector.FaceDetectorListener {
    private var isFrontCamera = true
    private lateinit var faceCamera: CameraController

    private lateinit var binding: FragmentHomeBinding

    private lateinit var executorService: ExecutorService
    private var faceDetector: FaceDetector? = null
    private var livenessDetector: FaceLivenessDetector? = null

    private var tracker: BoundingBoxTracker? = null
    private var overlayView: OverlayView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tracker = BoundingBoxTracker(maxLostFrames = 50)
        executorService = ThreadPoolExecutor(
            2,
            4,
            60L, TimeUnit.SECONDS,
            LinkedBlockingQueue<Runnable>(10)
        )
        executorService.submit {
            try {
                faceDetector = FaceDetector(requireActivity(), Constants.FACE_DETECTION_MODEL, this)
                livenessDetector = FaceLivenessDetector(requireActivity(), Constants.LIVENESS_FACE_MODE, this)

            } catch (e: SecurityException) {
                e.printStackTrace()
                // Log.e(TAG, "SecurityException")
            } catch (e: Exception) {
                e.printStackTrace()
                //Log.e(TAG, "Exception: $e")
            }

        }

    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        faceCamera = CameraController(binding.viewFinder, requireActivity(), this)
        binding.cameraPosition.isChecked = isFrontCamera
        binding.cameraPosition.setOnCheckedChangeListener { _, b ->
            isFrontCamera = b
            binding.cameraPositionName.text = if (isFrontCamera) {
                resources.getText(R.string.front_camera)
            } else {
                resources.getText(R.string.back_camera)
            }
            faceCamera.startCamera(isFrontCamera)
        }
        if (allPermissionsGranted(requireActivity(), REQUIRED_PERMISSIONS)) {
            faceCamera.startCamera(isFrontCamera)
        } else {
            ActivityCompat.requestPermissions(requireActivity(), REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
        overlayView = binding.overlay
    }


    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (it[Manifest.permission.CAMERA] == true) {
            faceCamera.startCamera(isFrontCamera)
        }
    }

    override fun onEmptyLivenessDetected() {
        Log.d(TAG, "livenessScore: Null")
    }

    override fun onLivenessDetected(livenessResult: LivenessResult, inferenceTime: Long) {
        Log.d(TAG, "livenessScore: ${livenessResult.livenessScore}")
        handleListTrackedObject(livenessResult)
    }

    private fun handleListTrackedObject(trackedObj: LivenessResult) {
        val index = trackedObjetData.getListTrackedObj().indexOfFirst { existingObj ->
            existingObj.id == trackedObj.id
        }
        if (index != -1) {
            trackedObjetData.replaceTrackObjWithIndex(index, trackedObj)
        } else {
            trackedObjetData.addTrackObj(trackedObj)
        }
    }

    private var trackedObjetData: TrackedObjectHelper = TrackedObjectHelper()
    override fun onEmptyFaceDetected() {
        uiThreadOperations(
            requireActivity()
        ) {
            overlayView?.clear()
        }
    }

    override fun onFaceDetected(frame: Bitmap, boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        val livenessResults = tracker?.update(boundingBoxes)
        executorService.submit {
            livenessResults?.forEach { livenessResult ->
                livenessDetector?.inference(livenessResult, frame) ?: Log.e(TAG, "Liveness detector is null")
            }
        }
        if (livenessResults != null) {
            trackedObjetData.setListTrackedObj(livenessResults)
        }
        drawBoundBox(trackedObjetData.getListTrackedObj().filter { it.lostFrames == 0 }, inferenceTime)

    }


    private fun drawBoundBox(trackedObjects: List<LivenessResult>, inferenceTime: Long) {
        uiThreadOperations(requireActivity()) {
            binding.inferenceTime.text = "${inferenceTime}ms"
            overlayView?.apply {
                setResults(trackedObjects)
                invalidate()
            }
        }
    }

    override fun onCameraResult(frame: Bitmap) {
        try {
            faceDetector?.detect(frame) ?: Log.e(TAG, "Face detector is null")
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
        }
    }

    override fun onCameraFailed(e: Exception) {
        Log.e(TAG, "Use case binding failed", e)
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted(requireActivity(), REQUIRED_PERMISSIONS)) {
            faceCamera.startCamera(isFrontCamera)
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        livenessDetector?.close()
        faceDetector?.close()
        executorService.shutdown()
        faceCamera.closeCamera()
    }

    companion object {
        val TAG = HomeFragment::class.simpleName
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA
        ).toTypedArray()
    }
}
