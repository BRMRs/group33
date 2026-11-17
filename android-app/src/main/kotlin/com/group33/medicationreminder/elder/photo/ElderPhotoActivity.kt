package com.group33.medicationreminder.elder.photo

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.group33.medicationreminder.R
import com.group33.medicationreminder.databinding.ActivityElderPhotoBinding
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ElderPhotoActivity : AppCompatActivity() {
    private lateinit var binding: ActivityElderPhotoBinding
    private var cameraPath: String? = null
    private val dependencies get() = (application as? ElderPhotoDependenciesOwner)?.elderPhotoDependencies
    private val model: ElderPhotoSubmitViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val activeDependencies = dependencies
                val store = PrivatePhotoDraftStore(applicationContext) { activeDependencies?.scope?.value?.toString() ?: "local-preview" }
                return ElderPhotoSubmitViewModel(store, activeDependencies) as T
            }
        }
    }
    private val choose = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.select(uri.toString())
    }
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val path = cameraPath
        cameraPath = null
        if (success && path != null) model.select(FileProvider.getUriForFile(this, "$packageName.elderphotos", File(path)).toString())
        else if (path != null) File(path).delete()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraPath = savedInstanceState?.getString("cameraPath")
        binding = ActivityElderPhotoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        androidx.core.view.WindowInsetsControllerCompat(window, binding.root).isAppearanceLightStatusBars = true
        binding.photoBack.setOnClickListener { finish() }
        binding.photoChoose.setOnClickListener { openPicker() }
        binding.photoCamera.setOnClickListener { openCamera() }
        binding.photoReplace.setOnClickListener { openCamera() }
        binding.photoUse.setOnClickListener { model.submit() }
        binding.photoBoundary.visibility = if (dependencies == null) View.VISIBLE else View.GONE
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collectLatest { state ->
                    binding.photoStatus.setText(state.stage.message())
                    if (state.stage in setOf(PhotoStage.RESTORED_PENDING, PhotoStage.AWAITING, PhotoStage.CONFIRMED_REFRESH)) model.reconcile()
                    binding.photoUse.visibility = if (state.draft != null) View.VISIBLE else View.GONE
                    binding.photoUse.isEnabled = state.canSubmit
                    binding.photoCamera.isEnabled = !state.busy
                    binding.photoChoose.isEnabled = !state.busy
                    binding.photoReplace.visibility = if (state.draft != null) View.VISIBLE else View.GONE
                    binding.photoReplace.isEnabled = !state.busy
                    val photo = state.draft
                    binding.photoPreview.visibility = if (photo == null) View.GONE else View.VISIBLE
                    if (photo != null) {
                        val bitmap = withContext(Dispatchers.IO) { PhotoPreviewDecoder.decode(photo.previewPath) }
                        binding.photoPreview.setImageBitmap(bitmap)
                    } else binding.photoPreview.setImageDrawable(null)
                }
            }
        }
    }

    private fun openPicker() {
        try { choose.launch(arrayOf("image/jpeg", "image/png")) }
        catch (_: Exception) { Toast.makeText(this, R.string.photo_invalid, Toast.LENGTH_LONG).show() }
    }

    private fun openCamera() {
        try {
            val directory = File(cacheDir, "elder-camera").apply { mkdirs() }
            val file = File.createTempFile("capture-", ".jpg", directory)
            cameraPath = file.path
            camera.launch(FileProvider.getUriForFile(this, "$packageName.elderphotos", file))
        } catch (_: Exception) {
            cameraPath?.let { File(it).delete() }
            cameraPath = null
            Toast.makeText(this, R.string.photo_camera_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("cameraPath", cameraPath)
        super.onSaveInstanceState(outState)
    }


}

private fun PhotoStage.message(): Int = when (this) {
    PhotoStage.EMPTY -> R.string.photo_empty
    PhotoStage.LOADING -> R.string.photo_loading
    PhotoStage.PREVIEW -> R.string.photo_ready
    PhotoStage.SUBMITTING -> R.string.photo_submitting
    PhotoStage.CONFIRMED -> R.string.photo_confirmed
    PhotoStage.CONFIRMED_REFRESH -> R.string.photo_confirmed_refresh
    PhotoStage.NOT_SUBMITTED -> R.string.photo_not_submitted
    PhotoStage.REJECTED -> R.string.photo_rejected
    PhotoStage.AWAITING -> R.string.photo_awaiting
    PhotoStage.UNAVAILABLE -> R.string.photo_unavailable
    PhotoStage.INVALID_PHOTO -> R.string.photo_invalid
    PhotoStage.RESTORED_PENDING -> R.string.photo_restored
}
