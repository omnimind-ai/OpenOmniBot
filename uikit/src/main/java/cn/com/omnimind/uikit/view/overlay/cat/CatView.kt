package cn.com.omnimind.uikit.view.overlay.cat

import android.content.Context
import android.graphics.drawable.AnimatedImageDrawable
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

class CatView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        const val width = 60
        const val height = 60
        private val screenshotMutex = Mutex()
    }

    private lateinit var ivCat: ImageView
    private var currentState = DraggableViewState.COLLAPSED
    private var dialogState = DraggableViewState.COLLAPSED
    private var isAttachedToLeft = false
    private lateinit var flAnimation: FrameLayout
    private lateinit var onStateChangeListener: OnStateChangeListener
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
                ivCat.setImageResource(R.mipmap.ic_cat_normal)
                doAnimationOnce(R.raw.anim_cat_start_doing, R.mipmap.ic_cat_doing_task, 1000)
            }

            else -> {
                ivCat.setImageResource(R.mipmap.ic_cat_normal)
                doAnimationOnce(R.raw.anim_cat_doing_task, R.mipmap.ic_cat_normal, 3000)
            }
        }
        onStateChangeListener.onStateChange(currentState)
    }

    fun showCollapsed() {
        when (dialogState) {
            DraggableViewState.COLLAPSED -> {
                ivCat.setImageResource(R.mipmap.ic_cat_normal)
            }

            DraggableViewState.DOING_TASK -> {
                ivCat.setImageResource(R.mipmap.ic_cat_doing_task)
                doAnimationOnce(R.raw.anim_cat_end_doing, R.mipmap.ic_cat_normal, 1000)
            }

            DraggableViewState.DRAGGING -> {
                ivCat.setImageResource(R.mipmap.ic_cat_normal)
            }

            else -> {
                ivCat.setImageResource(R.mipmap.ic_cat_normal)
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
        ivCat.setImageResource(imageRes)
        onAnimEnd.invoke()
    }

    fun doAnimation(animRes: Int, imageRes: Int) {
        mainJob.cancel()
        ivCat.setImageResource(imageRes)
    }

    var resource: AnimatedImageDrawable? = null
    var gifResource: GifDrawable? = null
    private var currentResourceRef: AnimatedImageDrawable? = null
    private var currentGifResourceRef: GifDrawable? = null

    suspend fun playCatAnimationTimes(
        animation: Int, endImage: Int, count: Int
    ) {
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
                                    ivCat.setImageResource(endImage)
                                    screenshotMutex.unlock()
                                }
                            } else if (resource is GifDrawable) {
                                this@CatView.gifResource = resource
                                currentGifResourceRef = resource
                                resource.setLoopCount(count)
                            } else {
                                ivCat.setImageResource(endImage)
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
}
