package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutionException

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CAMERA = 101
        private const val INPUT_SIZE = 224
        private const val NUM_CHANNELS = 3
        private const val BYTES_PER_FLOAT = 4
        private const val MODEL_FILE = "face_antispoofing_fp16.tflite"  // Đảm bảo file này có trong assets
    }

    private lateinit var previewView: PreviewView
    private lateinit var btnRecord: Button
    private lateinit var btnCapture: Button
    private lateinit var tvPrediction: TextView

    private var imageAnalysis: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var tflite: Interpreter

    // Biến để thu thập kết quả inference từ từng frame (cho chức năng record)
    private var isRecording = false
    private var outputSum = FloatArray(2)
    private var frameCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)  // Layout chứa PreviewView, btnRecord, btnCapture, tvPrediction

        previewView = findViewById(R.id.previewView)
        btnRecord = findViewById(R.id.btnRecord)
        btnCapture = findViewById(R.id.btnCapture)
        tvPrediction = findViewById(R.id.tvPrediction)

        // Load mô hình TFLite từ assets
        try {
            val modelBuffer = TFLiteModelLoader.loadModelFile(this, MODEL_FILE)
            tflite = Interpreter(modelBuffer)
            Log.d("TFLite", "Model loaded successfully")
        } catch (e: Exception) {
            Log.e("TFLite", "Error loading model", e)
        }

        // Kiểm tra quyền camera
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA),
                PERMISSION_REQUEST_CAMERA
            )
        } else {
            startCamera()
        }

        btnRecord.setOnClickListener {
            if (!isRecording) {
                startRecordingInference()
            }
        }

        btnCapture.setOnClickListener {
            capturePhoto()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                imageCapture = ImageCapture.Builder()
                    .setTargetResolution(android.util.Size(INPUT_SIZE, INPUT_SIZE))
                    .build()
                // Bind cả Preview và ImageCapture cùng với vòng đời của Activity
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
            } catch (e: InterruptedException) {
                Log.e("CameraX", "Error starting camera", e)
            } catch (e: ExecutionException) {
                Log.e("CameraX", "Error starting camera", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // Chức năng record 5s: thu thập các frame và tính trung bình kết quả inference
    private fun startRecordingInference() {
        isRecording = true
        outputSum[0] = 0f
        outputSum[1] = 0f
        frameCount = 0

        imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(INPUT_SIZE, INPUT_SIZE))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysis?.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
            try {
                val inputBuffer = convertImageProxyToByteBuffer(imageProxy)
                if (inputBuffer != null) {
                    val output = Array(1) { FloatArray(2) }
                    tflite.run(inputBuffer, output)
                    tflite.run {
                        imageProxy.toBitmap()
                    }
                    outputSum[0] += output[0][0]
                    outputSum[1] += output[0][1]
                    frameCount++
                } else {
                    Log.e("TFLite", "Input buffer is null")
                }
            } catch (e: Exception) {
                Log.e("TFLite", "Error in analyzer", e)
            } finally {
                imageProxy.close()
            }
        }


        // Bind ImageAnalysis use-case
        cameraProvider?.bindToLifecycle(
            this,
            CameraSelector.DEFAULT_BACK_CAMERA,
            imageAnalysis
        )

        object : CountDownTimer(5000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                tvPrediction.text = "Recording... ${millisUntilFinished / 1000}s"
            }
            override fun onFinish() {
                cameraProvider?.unbind(imageAnalysis)
                isRecording = false
                if (frameCount > 0) {
                    val avg0 = outputSum[0] / frameCount
                    val avg1 = outputSum[1] / frameCount
                    val finalPrediction = if (avg0 > avg1) 0 else 1
                    val confidence = if (finalPrediction == 0) avg0 else avg1
                    if (finalPrediction == 0) {
                        tvPrediction.setTextColor(resources.getColor(android.R.color.holo_red_dark))
                        tvPrediction.text = "Prediction: FAKE ($confidence)"
                    } else {
                        tvPrediction.setTextColor(resources.getColor(android.R.color.holo_green_dark))
                        tvPrediction.text = "Prediction: REAL ($confidence)"
                    }
                } else {
                    tvPrediction.text = "No frames analyzed"
                }
            }
        }.start()
    }

    // Chức năng capture: sử dụng ImageCapture để chụp ảnh và inference
    private fun capturePhoto() {
        imageCapture?.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    val inputBuffer = convertImageProxyToByteBuffer(imageProxy)
                    imageProxy.close()
                    if (inputBuffer != null) {
                        val output = Array(1) { FloatArray(2) }
                        tflite.run(inputBuffer, output)
                        val prediction = if (output[0][0] > output[0][1]) 0 else 1
                        val confidence = if (prediction == 0) output[0][0] else output[0][1]
                        runOnUiThread {
                            if (prediction == 0) {
                                tvPrediction.setTextColor(resources.getColor(android.R.color.holo_red_dark))
                                tvPrediction.text = "Prediction: FAKE ($confidence)"
                            } else {
                                tvPrediction.setTextColor(resources.getColor(android.R.color.holo_green_dark))
                                tvPrediction.text = "Prediction: REAL ($confidence)"
                            }
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("CameraX", "Photo capture failed: ${exception.message}", exception)
                }
            }
        )
    }

    // Chuyển đổi ImageProxy sang ByteBuffer cho inference.
    private fun convertImageProxyToByteBuffer(imageProxy: ImageProxy): ByteBuffer? {
        val bitmap = imageProxyToBitmap(imageProxy) ?: return null
        return convertBitmapToByteBuffer(bitmap)
    }

    // Chuyển ImageProxy (YUV_420_888) sang Bitmap mà không sử dụng RenderScript.
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val nv21 = imageProxyToNV21(imageProxy)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 100, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    // Chuyển ImageProxy sang NV21 ByteArray.
    private fun imageProxyToNV21(imageProxy: ImageProxy): ByteArray {
        val yBuffer = imageProxy.planes[0].buffer // Y
        val uBuffer = imageProxy.planes[1].buffer // U
        val vBuffer = imageProxy.planes[2].buffer // V

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        // Copy Y.
        yBuffer.get(nv21, 0, ySize)

        // NV21 format: VU interleaved.
        val pixelStride = imageProxy.planes[1].pixelStride
        val rowStride = imageProxy.planes[1].rowStride
        var offset = ySize
        val width = imageProxy.width / 2
        val height = imageProxy.height / 2
        for (row in 0 until height) {
            for (col in 0 until width) {
                val vIndex = row * rowStride + col * pixelStride
                nv21[offset++] = vBuffer.get(vIndex)
                val uIndex = row * rowStride + col * pixelStride
                nv21[offset++] = uBuffer.get(uIndex)
            }
        }
        return nv21
    }

    // Chuyển Bitmap sang ByteBuffer với kiểu float32 và Normalize theo chuẩn ImageNet.
    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(BYTES_PER_FLOAT * INPUT_SIZE * INPUT_SIZE * NUM_CHANNELS)
        byteBuffer.order(ByteOrder.nativeOrder())
        val intValues = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val meanR = 0.485f; val meanG = 0.456f; val meanB = 0.406f
        val stdR = 0.229f; val stdG = 0.224f; val stdB = 0.225f
        for (pixel in intValues) {
            var r = ((pixel shr 16) and 0xFF) / 255.0f
            var g = ((pixel shr 8) and 0xFF) / 255.0f
            var b = (pixel and 0xFF) / 255.0f
            r = (r - meanR) / stdR
            g = (g - meanG) / stdG
            b = (b - meanB) / stdB
            byteBuffer.putFloat(r)
            byteBuffer.putFloat(g)
            byteBuffer.putFloat(b)
        }
        return byteBuffer
    }
}
