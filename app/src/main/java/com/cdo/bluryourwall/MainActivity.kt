package com.cdo.BlurTool

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import jp.wasabeef.blurry.Blurry
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private var selectedImageUri: Uri? = null
    private var currentBlurredBitmap: Bitmap? = null
    private var currentBlurRadius: Int? = null
    private var blurJob: Job? = null
    private var backPressedTime = 0L
    private val BACK_PRESS_INTERVAL = 2000L

    private lateinit var imageView: ImageView
    private lateinit var selectButton: Button
    private lateinit var blurControlsLayout: LinearLayout
    private lateinit var seekBar: SeekBar
    private lateinit var saveButton: Button
    private lateinit var blurPercentageText: TextView

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let {
            selectedImageUri = it
            currentBlurredBitmap = null
            currentBlurRadius = null
            imageView.setImageURI(it)
            toggleControls(true)
        } ?: Toast.makeText(this, "No image selected", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imageView = findViewById(R.id.imageView)
        selectButton = findViewById(R.id.selectButton)
        blurControlsLayout = findViewById(R.id.blurControlsLayout)
        seekBar = findViewById(R.id.blurSeekBar)
        saveButton = findViewById(R.id.saveButton)
        blurPercentageText = findViewById(R.id.blurPercentageText)

        selectButton.setOnClickListener {
            imagePickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        seekBar.max = 150
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                blurPercentageText.text = "Blur: $progress%"
                if (selectedImageUri != null && fromUser) {
                    blurJob?.cancel()
                    blurJob = CoroutineScope(Dispatchers.Main).launch {
                        delay(200) // Debounce
                        applyAndShowBlur(progress)
                        currentBlurRadius = progress
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        saveButton.setOnClickListener {
            if (currentBlurredBitmap != null) {
                saveBlurredImage(currentBlurredBitmap!!)
            } else {
                Toast.makeText(this, "No image to save", Toast.LENGTH_SHORT).show()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val currentTime = System.currentTimeMillis()
                if (currentTime - backPressedTime < BACK_PRESS_INTERVAL) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                } else {
                    backPressedTime = currentTime
                    Toast.makeText(this@MainActivity, "Press again to exit", Toast.LENGTH_SHORT).show()
                }
            }
        })

        toggleControls(false)
    }

    private fun toggleControls(show: Boolean) {
        blurControlsLayout.visibility = if (show) View.VISIBLE else View.GONE
        selectButton.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun resizeBitmapIfNeeded(bitmap: Bitmap, maxSize: Int = 2160): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val maxDimension = maxOf(width, height)

        if (maxDimension <= maxSize) {
            return if (bitmap.config != Bitmap.Config.ARGB_8888) {
                bitmap.copy(Bitmap.Config.ARGB_8888, true)
            } else bitmap
        }

        val scale = maxSize.toFloat() / maxDimension
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)

        return scaledBitmap.copy(Bitmap.Config.ARGB_8888, true)
    }

    private fun applyStrongerBlur(context: MainActivity, bitmap: Bitmap, radius: Int, passes: Int = 2): Bitmap {
        var currentBitmap = if (bitmap.config == Bitmap.Config.HARDWARE)
            bitmap.copy(Bitmap.Config.ARGB_8888, true)
        else bitmap

        repeat(passes) {
            val tempImageView = ImageView(context).apply {
                setImageBitmap(currentBitmap)
                measure(
                    View.MeasureSpec.makeMeasureSpec(currentBitmap.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(currentBitmap.height, View.MeasureSpec.EXACTLY)
                )
                layout(0, 0, currentBitmap.width, currentBitmap.height)
            }

            currentBitmap = Blurry.with(context)
                .radius(radius)
                .sampling(1)
                .capture(tempImageView)
                .get()
        }

        return currentBitmap
    }

    private fun applyAndShowBlur(radius: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val source = ImageDecoder.createSource(contentResolver, selectedImageUri!!)
                val originalBitmap = ImageDecoder.decodeBitmap(source) {
                        decoder, _, _ ->
                    decoder.isMutableRequired = true
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }

                val resizedBitmap = resizeBitmapIfNeeded(originalBitmap, 2160)

                val blurredBitmap = if (radius <= 0) {
                    // Si radius es 0 o menos, devolver la imagen original redimensionada sin blur
                    resizedBitmap
                } else {
                    applyStrongerBlur(this@MainActivity, resizedBitmap, radius, 2)
                }

                currentBlurredBitmap = blurredBitmap

                withContext(Dispatchers.Main) {
                    imageView.setImageBitmap(blurredBitmap)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }


    private fun saveBlurredImage(bitmap: Bitmap) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val filename = "blurred_${System.currentTimeMillis()}.png"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                }

                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                val outputStream = uri?.let { contentResolver.openOutputStream(it) }

                val success = outputStream?.use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                } ?: false

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        if (success) "Image saved in Gallery" else "Error saving image",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
