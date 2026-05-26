package cn.com.omnimind.uikit.view.overlay.cat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.widget.FrameLayout
import android.widget.ImageView
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.uikit.R
import cn.com.omnimind.uikit.loader.cat.DraggableViewState
import cn.com.omnimind.uikit.loader.cat.OnStateChangeListener
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.signature.ObjectKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.util.UUID

class CatView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        const val width = 60
        const val height = 60
        private val screenshotMutex = Mutex()
        private const val GENERATED_PET_PREVIEW_SUFFIX = ".omnibot-preview.png"
        private const val ATLAS_COLUMNS = 8
        private const val ATLAS_ROWS = 9
        private const val ATLAS_WIDTH = 1536
        private const val ATLAS_HEIGHT = 1872
        private const val FRAME_DELAY_MS = 120L
        private val PET_FILE_NAMES = listOf(
            "spritesheet.webp",
            "spritesheet.png",
            "current.webp",
            "current.png",
            "current.jpg",
            "current.gif",
            "spritesheet.webp$GENERATED_PET_PREVIEW_SUFFIX",
            "spritesheet.png$GENERATED_PET_PREVIEW_SUFFIX",
            "current.svg$GENERATED_PET_PREVIEW_SUFFIX"
        )
    }

    private lateinit var ivCat: ImageView
    private var currentState = DraggableViewState.COLLAPSED
    private var dialogState = DraggableViewState.COLLAPSED
    private var isAttachedToLeft = false
    private lateinit var flAnimation: FrameLayout
    private lateinit var onStateChangeListener: OnStateChangeListener
    private var petRefreshToken = ""
    private var isFirst = true
    private var mainJob: CoroutineScope = CoroutineScope(Dispatchers.Main)

    init {
        setupView()
    }

    private fun setupView() {
        inflate(context, R.layout.view_cat, this)
        flAnimation = findViewById(R.id.flAnimation)
        ivCat = findViewById(R.id.ivCat)
        flAnimation.layoutParams = LayoutParams(CatView.width.dpToPx(), CatView.height.dpToPx())
    }

    fun setOnStateChangeListener(listener: OnStateChangeListener) {
        onStateChangeListener = listener
    }

    private fun updateDraggableViewState() {
        updateViewForAttachment()
    }

    private fun updateViewForAttachment() {
        when (currentState) {
            DraggableViewState.COLLAPSED -> {
                if (isFirst) {
                    doAnimationOnce(R.raw.anim_cat_show, R.mipmap.ic_cat_normal, 1000)
                    isFirst = false
                } else {
                    showCollapsed()
                }
            }

            DraggableViewState.DRAGGING -> {
                showDragging()
            }

            DraggableViewState.DOING_TASK -> {
                setPetOrImageResource(R.mipmap.ic_cat_normal)
                doAnimationOnce(R.raw.anim_cat_start_doing, R.mipmap.ic_cat_doing_task, 1000)
            }

            else -> {
                setPetOrImageResource(R.mipmap.ic_cat_normal)
                doAnimationOnce(R.raw.anim_cat_doing_task, R.mipmap.ic_cat_normal, 3000)
            }
        }
        onStateChangeListener.onStateChange(currentState)
    }

    fun showCollapsed() {
        when (dialogState) {
            DraggableViewState.COLLAPSED -> {
                setPetOrImageResource(R.mipmap.ic_cat_normal)
            }

            DraggableViewState.DOING_TASK -> {
                setPetOrImageResource(R.mipmap.ic_cat_doing_task)
                doAnimationOnce(R.raw.anim_cat_end_doing, R.mipmap.ic_cat_normal, 1000)
            }

            DraggableViewState.DRAGGING -> {
                setPetOrImageResource(R.mipmap.ic_cat_normal)
            }

            else -> {
                setPetOrImageResource(R.mipmap.ic_cat_normal)
            }
        }
    }

    fun showDragging() {
        doAnimation(R.raw.anim_cat_dragging, R.mipmap.ic_cat_normal)
    }

    fun setViewState(viewState: DraggableViewState) {
        if (currentState == viewState && viewState == dialogState) {
            return
        }
        currentState = viewState
        updateDraggableViewState()
        dialogState = currentState
    }

    fun setViewStateToDialogState() {
        currentState = dialogState
        updateDraggableViewState()
    }

    fun getViewState(): DraggableViewState {
        return currentState
    }

    fun setAttachmentSideView(isLeft: Boolean) {
        isAttachedToLeft = isLeft
        flAnimation.scaleX = if (isAttachedToLeft) -1f else 1f
    }

    fun startDragging() {
        currentState = DraggableViewState.DRAGGING
        updateDraggableViewState()
    }

    fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    fun doAnimationOnce(animRes: Int, imageRes: Int, animTimes: Long, onAnimEnd: () -> Unit = {}) {
        mainJob.cancel()
        mainJob = CoroutineScope(Dispatchers.Main)
        mainJob.launch {
            playCatAnimationTimes(animRes, imageRes, 0)
            delay(animTimes)
            setPetOrImageResource(imageRes)
            onAnimEnd.invoke()
        }
    }

    fun doAnimation(animRes: Int, imageRes: Int) {
        mainJob.cancel()
        ivCat.setImageResource(imageRes)
    }

    var resource: AnimatedImageDrawable? = null
    var gifResource: GifDrawable? = null
    private var currentResourceRef: AnimatedImageDrawable? = null
    private var currentGifResourceRef: GifDrawable? = null
    private var currentAtlasBitmap: Bitmap? = null
    private var currentAtlasPath: String? = null
    private var currentAtlasModified: Long = 0L
    private var currentAtlasFrames: List<Bitmap> = emptyList()
    private var currentAtlasFrameKey: String? = null

    suspend fun playCatAnimationTimes(
        animation: Int, endImage: Int, count: Int
    ) {
        if (resolveCustomPetFile() != null) {
            setPetOrImageResource(endImage)
            return
        }
        try {
            Glide.with(context).load(animation).skipMemoryCache(true)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable?>,
                        isFirstResource: Boolean
                    ): Boolean {
                        screenshotMutex.unlock()
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable?>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                if (resource is AnimatedImageDrawable) {
                                    this@CatView.resource = resource
                                    currentResourceRef = resource
                                    resource.repeatCount = count
                                } else if (resource is GifDrawable) {
                                    this@CatView.gifResource = resource
                                    currentGifResourceRef = resource
                                    resource.setLoopCount(count)
                                } else {
                                    setPetOrImageResource(endImage)
                                    screenshotMutex.unlock()
                                }
                            } else if (resource is GifDrawable) {
                                this@CatView.gifResource = resource
                                currentGifResourceRef = resource
                                resource.setLoopCount(count)
                            } else {
                                setPetOrImageResource(endImage)
                                screenshotMutex.unlock()
                            }
                        } catch (e: Exception) {
                            OmniLog.e("CatView", "Error in onResourceReady: ${e.message}")
                            screenshotMutex.unlock()
                        }
                        return false
                    }
                }).into(ivCat)
        } catch (e: Exception) {
            screenshotMutex.unlock()
            OmniLog.e("CatView", "Error in playCatAnimationTimes: ${e.message}")
        }
    }

    fun doFinish(
        onAnimEnd: () -> Unit = {}
    ) {
        mainJob.cancel()
        ivCat.setImageResource(R.mipmap.ic_cat_normal)
        onAnimEnd.invoke()
    }

    fun cancelAnimation() {
        mainJob.cancel()
    }

    fun refreshPetAppearance() {
        petRefreshToken = UUID.randomUUID().toString()
        mainJob.cancel()
        clearAtlasCache()
        val fallbackImageRes = if (currentState == DraggableViewState.DOING_TASK) {
            R.mipmap.ic_cat_doing_task
        } else {
            R.mipmap.ic_cat_normal
        }
        setPetOrImageResource(fallbackImageRes)
    }

    private fun setPetOrImageResource(imageRes: Int) {
        val petFile = resolveCustomPetFile()
        if (petFile != null) {
            if (isPetAtlasFile(petFile)) {
                playAtlasAnimation(petFile, currentAtlasSpec())
                return
            }
            val request = Glide.with(context)
                .load(petFile)
                .signature(ObjectKey("${petFile.absolutePath}:${petFile.lastModified()}:$petRefreshToken"))
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .skipMemoryCache(true)
            if (imageRes > 0) {
                request.error(imageRes)
            }
            request.into(ivCat)
            return
        }
        if (imageRes > 0) {
            ivCat.setImageResource(imageRes)
        }
    }

    private fun resolveCustomPetFile(): File? {
        val prefs = context.applicationContext.getSharedPreferences(
            "OmnibotSettings",
            Context.MODE_PRIVATE
        )
        val configuredPath = prefs.getString("pet_overlay_image_path", null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val selectedId = prefs.getString("pet_overlay_selected_id", null)
            ?.trim()
            .orEmpty()
        if (selectedId == "builtin:xiaowan" || configuredPath == "__builtin_xiaowan__") {
            return null
        }
        configuredPath?.let { path ->
            resolveConfiguredPetFile(path)?.let { file ->
                preferredPreviewFor(file)?.takeIf { it.isFile }?.let { return it }
                file.takeIf { it.isFile }?.let { return it }
            }
        }
        return petDirectories()
            .flatMap { petDir -> PET_FILE_NAMES.map { fileName -> File(petDir, fileName) } }
            .firstOrNull { it.isFile }
    }

    private fun resolveConfiguredPetFile(path: String): File? {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return null
        val sourcePreviewPath = trimmed
            .takeIf { it.endsWith(GENERATED_PET_PREVIEW_SUFFIX, ignoreCase = true) }
            ?.dropLast(GENERATED_PET_PREVIEW_SUFFIX.length)
        if (!sourcePreviewPath.isNullOrBlank()) {
            resolveConfiguredPetFile(sourcePreviewPath)
                ?.takeIf { it.isFile }
                ?.let { return it }
        }
        if (trimmed == "/workspace" || trimmed.startsWith("/workspace/")) {
            val relativePath = trimmed.removePrefix("/workspace").trimStart('/')
            return File(File(context.applicationContext.applicationInfo.dataDir, "workspace"), relativePath)
        }
        return File(trimmed)
    }

    private fun preferredPreviewFor(file: File): File? {
        if (isPetAtlasFile(file)) {
            return null
        }
        if (!file.name.endsWith(".svg", ignoreCase = true)
        ) {
            return null
        }
        return File(file.parentFile, "${file.name}$GENERATED_PET_PREVIEW_SUFFIX")
    }

    private data class AtlasAnimationSpec(
        val row: Int,
        val frameCount: Int,
        val delayMs: Long = FRAME_DELAY_MS
    )

    private fun currentAtlasSpec(): AtlasAnimationSpec {
        return when (currentState) {
            DraggableViewState.DRAGGING -> AtlasAnimationSpec(row = 1, frameCount = 8)
            DraggableViewState.DOING_TASK -> AtlasAnimationSpec(row = 7, frameCount = 6)
            DraggableViewState.MESSAGE -> AtlasAnimationSpec(row = 8, frameCount = 6)
            DraggableViewState.PAUSE_TASK -> AtlasAnimationSpec(row = 6, frameCount = 6)
            DraggableViewState.SCHEDULED_TIP -> AtlasAnimationSpec(row = 6, frameCount = 6)
            else -> AtlasAnimationSpec(row = 0, frameCount = 6)
        }
    }

    private fun isPetAtlasFile(file: File): Boolean {
        return file.name.equals("spritesheet.webp", ignoreCase = true) ||
            file.name.equals("spritesheet.png", ignoreCase = true)
    }

    private fun playAtlasAnimation(file: File, spec: AtlasAnimationSpec) {
        mainJob.cancel()
        mainJob = CoroutineScope(Dispatchers.Main)
        mainJob.launch {
            val atlas = withContext(Dispatchers.IO) { loadAtlasBitmap(file) } ?: run {
                ivCat.setImageResource(R.mipmap.ic_cat_normal)
                return@launch
            }
            val cellWidth = atlas.width / ATLAS_COLUMNS
            val cellHeight = atlas.height / ATLAS_ROWS
            if (cellWidth <= 0 || cellHeight <= 0) {
                ivCat.setImageResource(R.mipmap.ic_cat_normal)
                return@launch
            }
            val frames = atlasFrames(file, atlas, spec, cellWidth, cellHeight)
            if (frames.isEmpty()) {
                ivCat.setImageResource(R.mipmap.ic_cat_normal)
                return@launch
            }
            var frameIndex = 0
            while (isActive) {
                ivCat.setImageDrawable(BitmapDrawable(resources, frames[frameIndex]))
                frameIndex = (frameIndex + 1) % frames.size
                delay(spec.delayMs)
            }
        }
    }

    private fun atlasFrames(
        file: File,
        atlas: Bitmap,
        spec: AtlasAnimationSpec,
        cellWidth: Int,
        cellHeight: Int
    ): List<Bitmap> {
        val row = spec.row.coerceIn(0, ATLAS_ROWS - 1)
        val frameCount = spec.frameCount.coerceIn(1, ATLAS_COLUMNS)
        val key = "${file.absolutePath}:${file.lastModified()}:$row:$frameCount"
        if (currentAtlasFrameKey == key && currentAtlasFrames.all { !it.isRecycled }) {
            return currentAtlasFrames
        }
        currentAtlasFrames.forEach { frame ->
            if (!frame.isRecycled) {
                frame.recycle()
            }
        }
        currentAtlasFrames = List(frameCount) { frameIndex ->
            Bitmap.createBitmap(
                atlas,
                frameIndex * cellWidth,
                row * cellHeight,
                cellWidth,
                cellHeight
            )
        }
        currentAtlasFrameKey = key
        return currentAtlasFrames
    }

    private fun loadAtlasBitmap(file: File): Bitmap? {
        val modified = file.lastModified()
        val cached = currentAtlasBitmap
        if (
            cached != null &&
            !cached.isRecycled &&
            currentAtlasPath == file.absolutePath &&
            currentAtlasModified == modified
        ) {
            return cached
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        if (decoded.width != ATLAS_WIDTH || decoded.height != ATLAS_HEIGHT) {
            OmniLog.e("CatView", "Unsupported pet atlas size: ${decoded.width}x${decoded.height}")
            decoded.recycle()
            return null
        }
        currentAtlasFrames.forEach { frame ->
            if (!frame.isRecycled) {
                frame.recycle()
            }
        }
        currentAtlasFrames = emptyList()
        currentAtlasFrameKey = null
        currentAtlasBitmap?.takeIf { !it.isRecycled }?.recycle()
        currentAtlasBitmap = decoded
        currentAtlasPath = file.absolutePath
        currentAtlasModified = modified
        return decoded
    }

    private fun clearAtlasCache() {
        currentAtlasFrames.forEach { frame ->
            if (!frame.isRecycled) {
                frame.recycle()
            }
        }
        currentAtlasFrames = emptyList()
        currentAtlasFrameKey = null
        currentAtlasBitmap?.takeIf { !it.isRecycled }?.recycle()
        currentAtlasBitmap = null
        currentAtlasPath = null
        currentAtlasModified = 0L
    }

    private fun petDirectories(): List<File> {
        val appContext = context.applicationContext
        val workspacePetDir = File(appContext.applicationInfo.dataDir, "workspace/.omnibot/pets")
        val legacyWorkspacePetDir = File(appContext.filesDir, "workspace/.omnibot/pets")
        return listOf(workspacePetDir, legacyWorkspacePetDir).distinctBy { it.absolutePath }
    }
}
