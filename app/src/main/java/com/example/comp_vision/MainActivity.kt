package com.example.comp_vision

import ai.onnxruntime.*
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.graphics.YuvImage

class MainActivity : AppCompatActivity() {
    private var ortSession: OrtSession? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var imgData: FloatBuffer
    private val inputSize = 640
    private var isYoloEnabled = false
    private var isCameraEnabled = false
    private lateinit var viewFinder: PreviewView
    private lateinit var detectionOverlay: ImageView
    private val PICK_IMAGE_REQUEST = 1

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val buttonToggleYolo: Button = findViewById(R.id.buttonEnableYolo)
        val buttonToggleCamera: Button = findViewById(R.id.buttonToggleCamera)
        val buttonLoadImage: Button = findViewById(R.id.buttonLoadImage)
        viewFinder = findViewById(R.id.viewFinder)
        detectionOverlay = findViewById(R.id.detectionOverlay)

        buttonToggleYolo.setOnClickListener { toggleYoloModel() }
        buttonToggleCamera.setOnClickListener {
            if (isCameraEnabled) stopCamera() else startCamera()
            isCameraEnabled = !isCameraEnabled
            buttonToggleCamera.text = if (isCameraEnabled) "Выключить камеру" else "Включить камеру"
        }
        buttonLoadImage.setOnClickListener { openGallery() }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun toggleYoloModel() {
        if (!isYoloEnabled) enableYoloModel() else disableYoloModel()
    }

    private fun enableYoloModel() {
        isYoloEnabled = true
        try {
            loadOnnxModel("last_ir8.onnx")
            imgData = FloatBuffer.allocate(inputSize * inputSize * 3)
            logMessage("YOLO модель загружена и подготовлена к использованию")
        } catch (e: Exception) {
            logMessage("Ошибка запуска модели YOLO: ${e.message}")
        }
    }


    private fun disableYoloModel() {
        isYoloEnabled = false
        ortSession?.close()
        ortSession = null
        imgData = FloatBuffer.allocate(0)
        logMessage("YOLO модель остановлена")
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysisUseCase ->
                    analysisUseCase.setAnalyzer(cameraExecutor, ImageAnalysis.Analyzer { imageProxy ->
                        try {
                            val bitmap = imageProxyToBitmap(imageProxy)
                            if (bitmap != null) {
                                imgData = prepareInputData(bitmap)

                                val detections = runOnnxModel(imgData, bitmap)
                                displayDetectionsOverlay(detections)
                            } else {
                                logMessage("Не удалось преобразовать кадр в Bitmap")
                            }
                        } finally {
                            imageProxy.close()
                        }
                    })
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer)
            } catch (exc: Exception) {
                logMessage("Ошибка при запуске камеры: ${exc.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        ProcessCameraProvider.getInstance(this).get().unbindAll()
        logMessage("Камера остановлена")
    }
    private fun loadOnnxModel(modelPath: String) {
        try {
            val env = OrtEnvironment.getEnvironment()
            val modelFile = File(this.filesDir, modelPath)
            if (!modelFile.exists()) {
                copyAssetFile(modelPath, modelFile)
            }
            ortSession = env.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
            logMessage("Модель YOLO успешно загружена")
        } catch (e: Exception) {
            logMessage("Ошибка загрузки модели YOLO: ${e.message}")
        }
    }
    private fun copyAssetFile(assetFilePath: String, destinationFile: File) {
        try {
            assets.open(assetFilePath).use { inputStream ->
                FileOutputStream(destinationFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        } catch (e: Exception) {
            logMessage("Ошибка копирования файла: ${e.message}")
        }
    }
    private fun processImageAndDisplay(bitmap: Bitmap) {
        // Масштабируем исходное изображение до размера модели
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)

        // Преобразуем изображение для модели
        imgData = prepareInputData(scaledBitmap)
        val detections = runOnnxModel(imgData, scaledBitmap)

        // Отрисовываем детекции и отображаем результат
        val resultBitmap = drawDetectionRectangles(detections, scaledBitmap)
        runOnUiThread {
            detectionOverlay.setImageBitmap(resultBitmap)
        }
    }
    private fun prepareInputData(bitmap: Bitmap): FloatBuffer {
        // Изменение размера изображения до нужного размера модели (например, 640x640)
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)

        val floatBuffer = FloatBuffer.allocate(inputSize * inputSize * 3)
        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val pixel = resizedBitmap.getPixel(x, y)
                floatBuffer.put((Color.red(pixel) / 255.0f) - 0.5f)
                floatBuffer.put((Color.green(pixel) / 255.0f) - 0.5f)
                floatBuffer.put((Color.blue(pixel) / 255.0f) - 0.5f)
            }
        }
        floatBuffer.rewind()
        return floatBuffer
    }


    private fun runOnnxModel(inputData: FloatBuffer, bitmap: Bitmap): List<DetectionResult> {
        val detections = mutableListOf<DetectionResult>()

        try {
            ortSession?.let { session ->
                logMessage("Начало создания входного тензора для модели")
                val inputName = session.inputNames.iterator().next()
                val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
                val tensor = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), inputData, shape)
                logMessage("Входной тензор создан успешно: ${shape.joinToString()}")

                logMessage("Запуск модели YOLO")
                val results = session.run(mapOf(inputName to tensor))
                tensor.close()
                logMessage("Модель успешно запущена и вернула результаты")

                val resultTensor = results[0] as? OnnxTensor
                resultTensor?.let {
                    val outputArray = it.floatBuffer.array()
                    logMessage("Получен массив выходных данных, длина: ${outputArray.size}")

                    val outputShape = it.info.shape
                    logMessage("Форма выходных данных: ${outputShape.joinToString()}")

                    val cameraHeight = viewFinder.height.toFloat()
                    val cameraWidth = viewFinder.width.toFloat()
                    logMessage("Размеры камеры: width=$cameraWidth, height=$cameraHeight")

                    for (i in outputArray.indices step 9) {
                        val probability = outputArray[i]
                        if (probability > 0.0003 && probability <= 1.0) {
                            logMessage("Output array: ${outputArray.slice(i until i + 6).joinToString()}")

                            val xCenter = outputArray[i + 1] * bitmap.width
                            val yCenter = outputArray[i + 2] * bitmap.height
                            val boxWidth = outputArray[i + 3] * bitmap.width
                            val boxHeight = outputArray[i + 4] * bitmap.height
                            val classId = outputArray[i + 5].toInt()
                            val className = getClassLabel(classId)

                            // Масштабируем координаты рамок для отображения на overlay
                            val left = (xCenter - boxWidth / 2)
                            val top = (yCenter - boxHeight / 2)
                            val right = (xCenter + boxWidth / 2)
                            val bottom = (yCenter + boxHeight / 2)

                            val scaledLeft = left * detectionOverlay.width+100
                            val scaledTop = top * detectionOverlay.height+100
                            val scaledRight = right * detectionOverlay.width+100
                            val scaledBottom = bottom * detectionOverlay.height+100

                            detections.add(
                                DetectionResult(
                                    rect = RectF(scaledLeft, scaledTop, scaledRight, scaledBottom),
                                    probability = probability,
                                    className = className
                                )
                            )
                        }
                    }

                    logMessage("Обнаружено объектов: ${detections.size}")
                }
            }
        } catch (e: Exception) {
            logMessage("Ошибка выполнения детекции: ${e.message}")
        }

        return detections
    }



    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        startActivityForResult(intent, PICK_IMAGE_REQUEST)
    }

    @SuppressLint("SetTextI18n")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        logMessage("onActivityResult вызван")

        if (requestCode == PICK_IMAGE_REQUEST && resultCode == RESULT_OK && data != null) {
            logMessage("Получено изображение из галереи")
            val imageUri = data.data
            logMessage("Изображение выбрано: $imageUri")

            val bitmap = MediaStore.Images.Media.getBitmap(this.contentResolver, imageUri)
            if (bitmap != null) {
                logMessage("Изображение загружено успешно, ширина: ${bitmap.width}, высота: ${bitmap.height}")

                // Преобразование изображения в данные для модели
                imgData = prepareInputData(bitmap)
                logMessage("Изображение преобразовано для модели")

                // Запуск модели и получение результатов детекции
                val detections = runOnnxModel(imgData, bitmap)
                logMessage("Детекция выполнена, обнаружено объектов: ${detections.size}")

                // Рисуем квадраты на изображении с помощью drawDetectionRectangles и обновляем overlay
                val resultBitmap = drawDetectionRectangles(detections, bitmap)
                detectionOverlay.setImageBitmap(resultBitmap)
            } else {
                logMessage("Ошибка при загрузке изображения.")
            }
        }
    }



    private fun drawDetectionRectangles(detections: List<DetectionResult>, bitmap: Bitmap): Bitmap {
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)

        val paint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 30f
        }

        detections.forEach { detection ->
            canvas.drawRect(detection.rect, paint)
            canvas.drawText(detection.className, detection.rect.left, detection.rect.top - 10, textPaint)
        }

        return mutableBitmap
    }
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        if (image.format != ImageFormat.YUV_420_888) return null

        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    private fun displayDetectionsOverlay(detections: List<DetectionResult>) {
        val overlayBitmap = Bitmap.createBitmap(detectionOverlay.width, detectionOverlay.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(overlayBitmap)

        val paint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 30f
        }

        // Отображаем каждый обнаруженный объект на canvas
        detections.forEach { detection ->
            canvas.drawRect(detection.rect, paint) // Прямоугольник для объекта
            canvas.drawText(detection.className, detection.rect.left, detection.rect.top - 10, textPaint) // Название класса
        }

        runOnUiThread {
            detectionOverlay.setImageBitmap(overlayBitmap)
        }
    }
    private fun displayTestOverlay() {
        val testBitmap = Bitmap.createBitmap(detectionOverlay.width, detectionOverlay.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(testBitmap)

        val paint = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.FILL
        }

        // Заполняем тестовый Bitmap зеленым цветом
        canvas.drawRect(0f, 0f, testBitmap.width.toFloat(), testBitmap.height.toFloat(), paint)
        canvas.drawText("Тестовое сообщение", 50f, 50f, Paint().apply {
            color = Color.WHITE
            textSize = 40f
        })

        // Устанавливаем тестовый Bitmap на overlay
        runOnUiThread {
            detectionOverlay.setImageBitmap(testBitmap)
        }
    }


    private fun getClassLabel(classId: Int): String {
        return when (classId) {
            0 -> "obstructed_road_sign"
            1 -> "off traffic light"
            2 -> "pothole"
            3 -> "road_sign"
            4 -> "traffic light"
            else -> "Unknown"
        }
    }

    private fun logMessage(message: String) {
        runOnUiThread {
            val textViewLogs: TextView = findViewById(R.id.textViewLogs)
            textViewLogs.append("$message\n")
        }
    }




}

data class DetectionResult(val rect: RectF, val probability: Float, val className: String)
