package cn.com.omnimind.uikit.view.layout

import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.toColorInt
import cn.com.omnimind.baselib.util.ShapeBuilder
import cn.com.omnimind.uikit.R
import cn.com.omnimind.uikit.api.callback.CatStepLayoutApi
import cn.com.omnimind.uikit.view.data.CatDialogStateData
import cn.com.omnimind.uikit.view.data.CatDialogViewState
import cn.com.omnimind.uikit.view.overlay.cat.CatDialogShowInfoView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ShowInfoView - 閺勫墽銇氭穱鈩冧紖鐟欏棗娴?
 * 閺€顖涘瘮娴犲海鐓╄ぐ銏℃煙濡楀棗鍩岄崷鍡楄埌閻樿埖鈧焦娼惃鍕ウ閻ｅ懎濮╅悽鏄忔祮閹?
 */
class ShowInfoView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : BaseFrameLayout(context, attrs, defStyleAttr) {
    // 鏉堣顢嬫０婊嗗缁鐎?
    var borderColorType: GradientBorderContainerView.BorderColor =
        GradientBorderContainerView.BorderColor.BLUE
        set(value) {
            field = value
            gradientBorderContainer?.setBorderColorType(value)
        }

    // 鐎涙劘顫嬮崶鎯х穿閻?
    private var gradientBorderContainer: GradientBorderContainerView? = null
    private var gradientTextView: GradientTextView? = null
    private var llTakeOver: View? = null
    private var llResume: View? = null
    private var llStop: View? = null
    private var executingTextView: TextView? = null
    private var executingTextViewContainer: View? = null // executingTextView 閹碘偓閸︺劎娈戠€圭懓娅?
    private var contentContainer: View? = null
    private var innerRelativeLayout: View? = null
    private var bottomContentLayout: View? = null // 閸栧懎鎯?contentContainer 閻?RelativeLayout
    private var llResumeTextView: TextView? = null // llResume 娑擃厾娈戦弬鍥х摟
    private var ivResume: ImageView? = null

    var delayTask: CoroutineScope = CoroutineScope(Dispatchers.IO)

    var isUserActionCompleted: CompletableDeferred<Boolean>? = null
    var catStepLayoutApi: CatStepLayoutApi? = null
    var readyDoingTaskAnimator: AnimatorSet? = null

    init {
        // 娴犲逗ML閸旂姾娴囩敮鍐ㄧ湰
        LayoutInflater.from(context).inflate(R.layout.layout_show_info_view, this, true)
        // 閸掓繂顫愰崠鏍潒閸ユ儳绱╅悽?
        gradientBorderContainer = findViewById(R.id.gradientBorderContainer)
        gradientTextView = findViewById(R.id.gradientTextView)
        llTakeOver = findViewById(R.id.llTakeOver)
        llResume = findViewById(R.id.llResume)
        llStop = findViewById(R.id.llStop)
        executingTextView = findViewById(R.id.executingTextView)
        // 闁俺绻僫d閼惧嘲褰?executingTextView 閻ㄥ嫮鍩楃€圭懓娅掗敍鍦爄nearLayout閿?
        executingTextViewContainer = findViewById(R.id.executingTextViewContainer)
        contentContainer = findViewById(R.id.contentContainer)
        // 闁俺绻僫d閼惧嘲褰囬崠鍛儓 contentContainer 閻?RelativeLayout閿涘牆绨抽柈銊ュ敶鐎圭懓顔愰崳顭掔礆
        bottomContentLayout = findViewById(R.id.bottomContentLayout)
        // 闁俺绻僫d閼惧嘲褰囬崘鍛村劥 RelativeLayout閿涘澁lContent閿?
        innerRelativeLayout = findViewById(R.id.rlContent)
        // 闁俺绻僫d閼惧嘲褰?llResume 娑擃厾娈?TextView閿?缂佈呯敾"閺傚洤鐡ч敍?
        llResumeTextView = findViewById(R.id.llResumeTextView)
        //閺€鎯版崳閸氬骸鐫嶇粈铏规畱缂佈呯敾閹稿鎸?
        ivResume = findViewById(R.id.ivResume)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        llResume?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        llTakeOver?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        llStop?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        ivResume?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        llResume?.contentDescription = context.getString(R.string.accessibility_overlay_resume)
        llTakeOver?.contentDescription = context.getString(R.string.accessibility_overlay_takeover)
        llStop?.contentDescription = context.getString(R.string.accessibility_overlay_stop)
        ivResume?.contentDescription = context.getString(R.string.accessibility_overlay_resume)
//        ShapeBuilder.roundedRectangle("#F3F4F5".toColorInt(), 30.dpToPxF()).applyTo(ivResume!!)
        // 鐠佸墽鐤嗛崚婵嗩潗閸?
        gradientBorderContainer?.setBorderColorType(borderColorType)
        // 鐠佸墽鐤嗛幐澶愭尦閻愮懓鍤惄鎴濇儔
        llTakeOver?.setOnClickListener {
            catStepLayoutApi?.onPauseClick()
        }
        llStop?.setOnClickListener {
            val deferred = isUserActionCompleted
            if (deferred != null) {
                // 閸欘亣鐨熼悽?complete閿涘奔绗夌憰浣虹彌閸楀疇顔曠純顔昏礋 null
                // 鐠?await() 閸?finally 閸фぞ鑵戞径鍕倞濞撳懐鎮?
                try {
                    deferred.complete(false)
                } catch (e: IllegalStateException) {
                    // 婵″倹鐏夊鑼病鐎瑰本鍨氶敍灞芥嫹閻ｃ儱绱撶敮?
                }
                return@setOnClickListener
            }
            catStepLayoutApi?.onStopClick()

        }
        ivResume?.setOnClickListener {
            val deferred = isUserActionCompleted
            if (deferred != null) {
                // 閸欘亣鐨熼悽?complete閿涘奔绗夌憰浣虹彌閸楀疇顔曠純顔昏礋 null
                // 鐠?await() 閸?finally 閸фぞ鑵戞径鍕倞濞撳懐鎮?
                try {
                    deferred.complete(true)
                } catch (e: IllegalStateException) {
                    // 婵″倹鐏夊鑼病鐎瑰本鍨氶敍灞芥嫹閻ｃ儱绱撶敮?
                }
                return@setOnClickListener
            }
            catStepLayoutApi?.onResumeClick()
        }
        llResume?.setOnClickListener {
            val deferred = isUserActionCompleted
            if (deferred != null) {
                // 閸欘亣鐨熼悽?complete閿涘奔绗夌憰浣虹彌閸楀疇顔曠純顔昏礋 null
                // 鐠?await() 閸?finally 閸фぞ鑵戞径鍕倞濞撳懐鎮?
                try {
                    deferred.complete(true)
                } catch (e: IllegalStateException) {
                    // 婵″倹鐏夊鑼病鐎瑰本鍨氶敍灞芥嫹閻ｃ儱绱撶敮?
                }
                return@setOnClickListener
            }
            catStepLayoutApi?.onResumeClick()
        }
    }

    fun pause(
        catDialogShowInfoView: CatDialogShowInfoView,
        catDialogShowInfoViewParams: WindowManager.LayoutParams,
        windowManager: WindowManager
    ) {
        bottomContentLayout?.visibility = VISIBLE
        ivResume?.visibility = GONE
        llResume?.visibility = VISIBLE
        llTakeOver?.visibility = GONE
        if (CatDialogStateData.viewState == CatDialogViewState.USER_INFO) {
            return
        }
        if (CatDialogStateData.viewState == CatDialogViewState.USER_INFO_HINT) {
            return
        }
        CatDialogStateData.viewState = CatDialogViewState.USER_INFO
        delayTask.launch {
            delay(2000)

            withContext(Dispatchers.Main) {
                gradientBorderContainer?.cornerRadiusProgress = 1.0f
                innerRelativeLayout?.setPadding(10.dpToPx(), 3.dpToPx(), 10.dpToPx(), 3.dpToPx())
                bottomContentLayout?.visibility = GONE
                ivResume?.visibility = VISIBLE
                CatDialogStateData.viewState = CatDialogViewState.USER_INFO_HINT
                val (startX, startY) = CatDialogStateData.getDoingTaskXY()
                val (endX, endY) = CatDialogStateData.getUserInfoXY()
                val (startW, startH) = CatDialogStateData.getTaskDoingWH()
                val (endW, endH) = CatDialogStateData.getUserInfoWH()
                post {
                    // 閸掓稑缂撶€硅棄瀹抽崝銊ф暰閿涘奔濞囬悽?OvershootInterpolator 鐠佲晛濮╅悽缁樻箒鏉炶浜曢惃鍕剨鐠鸿櫕鏅ラ弸婊愮礉閺囧鏁撻崝銊ㄥ殰閻?
                    val widthAnimator = ValueAnimator.ofInt(startW, endW).apply {
                        duration = 1000L // 閸斻劎鏁鹃弮鍫曟毐 500ms
                        interpolator = OvershootInterpolator(1.2f) // 鐡掑懓鐨熼幓鎺戔偓鐓庢珤閿涘矁浜ゅ顔跨Т鏉╁洨娲伴弽鍥р偓鐓庡晙閸ョ偛鑴婇敍灞肩瑢妞ゅ湱娲版稉顓炲従娴犳牕濮╅悽璁崇箽閹镐椒绔撮懛?
                        addUpdateListener { animation ->
                            val animatedValue = animation.animatedValue as Int
                            val params = layoutParams
                            params.width = animatedValue
                            catDialogShowInfoViewParams.width = animatedValue

                        }
                    }
                    // 閸掓稑缂撴妯哄閸斻劎鏁鹃敍灞煎▏閻劎娴夐崥宀€娈戦幓鎺戔偓鐓庢珤娣囨繃瀵旈崝銊ф暰閸楀繗鐨?
                    val heightAnimator = ValueAnimator.ofInt(startH, endH).apply {
                        duration = 1000L // 閸斻劎鏁鹃弮鍫曟毐 500ms
                        interpolator = OvershootInterpolator(1.2f) // 鐡掑懓鐨熼幓鎺戔偓鐓庢珤閿涘矁浜ゅ顔跨Т鏉╁洨娲伴弽鍥р偓鐓庡晙閸ョ偛鑴?
                        addUpdateListener { animation ->
                            val animatedValue = animation.animatedValue as Int
                            val params = layoutParams
                            params.height = animatedValue
                            catDialogShowInfoViewParams.height = animatedValue
                        }
                    }
                    // 閸掓稑缂搙閸ф劖鐖ｉ崝銊ф暰
                    val xAnimator = ValueAnimator.ofInt(startX, endX).apply {
                        duration = 1000L // 閸斻劎鏁鹃弮鍫曟毐 500ms
                        interpolator = OvershootInterpolator(1.2f) // 鐡掑懓鐨熼幓鎺戔偓鐓庢珤閿涘矁浜ゅ顔跨Т鏉╁洨娲伴弽鍥р偓鐓庡晙閸ョ偛鑴?
                        addUpdateListener { animation ->
                            val animatedValue = animation.animatedValue as Int
                            catDialogShowInfoViewParams.x = animatedValue

                        }
                    }
                    // 閸掓稑缂搙閸ф劖鐖ｉ崝銊ф暰
                    val yAnimator = ValueAnimator.ofInt(startY, endY).apply {
                        duration = 1000L // 閸斻劎鏁鹃弮鍫曟毐 500ms
                        interpolator = OvershootInterpolator(1.2f) // 鐡掑懓鐨熼幓鎺戔偓鐓庢珤閿涘矁浜ゅ顔跨Т鏉╁洨娲伴弽鍥р偓鐓庡晙閸ョ偛鑴?
                        addUpdateListener { animation ->
                            val animatedValue = animation.animatedValue as Int
                            catDialogShowInfoViewParams.y = animatedValue

                        }
                    }
                    val dongingAnimator = ValueAnimator.ofInt(1, 100).apply {
                        duration = 1000L // 閸斻劎鏁鹃弮鍫曟毐 500ms
                        addUpdateListener { animation ->
                            windowManager.updateViewLayout(
                                catDialogShowInfoView, catDialogShowInfoViewParams
                            )
                        }
                    }
                    // 閸氬本妞傞幍褑顢戠€硅棄瀹抽崪宀勭彯鎼达箑濮╅悽?
                    readyDoingTaskAnimator = AnimatorSet()
                    readyDoingTaskAnimator!!.playTogether(
                        widthAnimator, heightAnimator, xAnimator, yAnimator, dongingAnimator
                    )
                    readyDoingTaskAnimator!!.start()
                }
            }
        }

    }

    suspend fun userAction(
        catDialogShowInfoView: CatDialogShowInfoView,
        catDialogShowInfoViewParams: WindowManager.LayoutParams,
        windowManager: WindowManager
    ): Boolean {
        withContext(Dispatchers.Main) {
            pause(
                catDialogShowInfoView,
                catDialogShowInfoViewParams,
                windowManager
            )
        }
        isUserActionCompleted = CompletableDeferred()
        val deferred = isUserActionCompleted
        return if (deferred != null) {
            try {
                deferred.await()
            } finally {
                // 绾喕绻氶崷?await 鐎瑰本鍨氶崥搴㈠鐠佸墽鐤嗘稉?null
                if (isUserActionCompleted == deferred) {
                    isUserActionCompleted = null
                }
            }
        } else {
            false
        }
    }

    fun doingTask(
        catDialogShowInfoView: CatDialogShowInfoView,
        catDialogShowInfoViewLayoutParams: WindowManager.LayoutParams,
        windowManager: WindowManager
    ) {
        setDoing(catDialogShowInfoView, catDialogShowInfoViewLayoutParams, windowManager, true)
    }


    fun doingAnimatorWithWH(startW: Int, startH: Int, endW: Int, endH: Int) {
        post {
            visibility = VISIBLE
            // 閸掓稑缂撶€硅棄瀹抽崝銊ф暰閿涘奔濞囬悽?OvershootInterpolator 鐠佲晛濮╅悽缁樻箒鏉炶浜曢惃鍕剨鐠鸿櫕鏅ラ弸婊愮礉閺囧鏁撻崝銊ㄥ殰閻?
            val widthAnimator = ValueAnimator.ofInt(startW, endW).apply {
                duration = 1000L // 閸斻劎鏁鹃弮鍫曟毐 500ms
                interpolator = OvershootInterpolator(1.2f) // 鐡掑懓鐨熼幓鎺戔偓鐓庢珤閿涘矁浜ゅ顔跨Т鏉╁洨娲伴弽鍥р偓鐓庡晙閸ョ偛鑴婇敍灞肩瑢妞ゅ湱娲版稉顓炲従娴犳牕濮╅悽璁崇箽閹镐椒绔撮懛?
                addUpdateListener { animation ->
                    val animatedValue = animation.animatedValue as Int
                    val params = layoutParams
                    params.width = animatedValue
                    layoutParams = params
                }
            }
            // 閸掓稑缂撴妯哄閸斻劎鏁鹃敍灞煎▏閻劎娴夐崥宀€娈戦幓鎺戔偓鐓庢珤娣囨繃瀵旈崝銊ф暰閸楀繗鐨?
            val heightAnimator = ValueAnimator.ofInt(startH, endH).apply {
                duration = 1000L // 閸斻劎鏁鹃弮鍫曟毐 500ms
                interpolator = OvershootInterpolator(1.2f) // 鐡掑懓鐨熼幓鎺戔偓鐓庢珤閿涘矁浜ゅ顔跨Т鏉╁洨娲伴弽鍥р偓鐓庡晙閸ョ偛鑴?
                addUpdateListener { animation ->
                    val animatedValue = animation.animatedValue as Int
                    val params = layoutParams
                    params.height = animatedValue
                    layoutParams = params
                }
            }
            // 閸氬本妞傞幍褑顢戠€硅棄瀹抽崪宀勭彯鎼达箑濮╅悽?
            readyDoingTaskAnimator = AnimatorSet()
            readyDoingTaskAnimator!!.playTogether(widthAnimator, heightAnimator)
            readyDoingTaskAnimator!!.start()
        }
    }

    fun doingAnimatorWithWHXY(
        startW: Int,
        startH: Int,
        endW: Int,
        endH: Int,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        catDialogShowInfoView: CatDialogShowInfoView,
        catDialogShowInfoViewLayoutParams: WindowManager.LayoutParams,
        windowManager: WindowManager
    ) {
        post {
            // 閸掓稑缂撶€硅棄瀹抽崝銊ф暰閿涘奔濞囬悽?OvershootInterpolator 鐠佲晛濮╅悽缁樻箒鏉炶浜曢惃鍕剨鐠鸿櫕鏅ラ弸婊愮礉閺囧鏁撻崝銊ㄥ殰閻?
            val widthAnimator = ValueAnimator.ofInt(startW, endW).apply {
                duration = 1000L // 閸斻劎鏁鹃弮鍫曟毐 500ms
                interpolator = OvershootInterpolator(1.2f) // 鐡掑懓鐨熼幓鎺戔偓鐓庢珤閿涘矁浜ゅ顔跨Т鏉╁洨娲伴弽鍥р偓鐓庡晙閸ョ偛鑴婇敍灞肩瑢妞ゅ湱娲版稉顓炲従娴犳牕濮╅悽璁崇箽閹镐椒绔撮懛?
                addUpdateListener { animation ->
                    val animatedValue = animation.animatedValue as Int
                    val params = layoutParams
                    params.width = animatedValue
                    catDialogShowInfoViewLayoutParams.width = animatedValue

                }
            }
            // 閸掓稑缂撴妯哄閸斻劎鏁鹃敍灞煎▏閻劎娴夐崥宀€娈戦幓鎺戔偓鐓庢珤娣囨繃瀵旈崝銊ф暰閸楀繗鐨?
            val heightAnimator = ValueAnimator.ofInt(startH, endH).apply {
                duration = 1000L // 閸斻劎鏁鹃弮鍫曟毐 500ms
                interpolator = OvershootInterpolator(1.2f) // 鐡掑懓鐨熼幓鎺戔偓鐓庢珤閿涘矁浜ゅ顔跨Т鏉╁洨娲伴弽鍥р偓鐓庡晙閸ョ偛鑴?
                addUpdateListener { animation ->
                    val animatedValue = animation.animatedValue as Int
                    val params = layoutParams
                    params.height = animatedValue
                    catDialogShowInfoViewLayoutParams.height = animatedValue
                }
            }
            // 閸掓稑缂搙閸ф劖鐖ｉ崝銊ф暰
            val xAnimator = ValueAnimator.ofInt(startX, endX).apply {
                duration = 1000L // 閸斻劎鏁鹃弮鍫曟毐 500ms
                interpolator = OvershootInterpolator(1.2f) // 鐡掑懓鐨熼幓鎺戔偓鐓庢珤閿涘矁浜ゅ顔跨Т鏉╁洨娲伴弽鍥р偓鐓庡晙閸ョ偛鑴?
                addUpdateListener { animation ->
                    val animatedValue = animation.animatedValue as Int
                    catDialogShowInfoViewLayoutParams.x = animatedValue

                }
            }
            // 閸掓稑缂搙閸ф劖鐖ｉ崝銊ф暰
            val yAnimator = ValueAnimator.ofInt(startY, endY).apply {
                duration = 1000L // 閸斻劎鏁鹃弮鍫曟毐 500ms
                interpolator = OvershootInterpolator(1.2f) // 鐡掑懓鐨熼幓鎺戔偓鐓庢珤閿涘矁浜ゅ顔跨Т鏉╁洨娲伴弽鍥р偓鐓庡晙閸ョ偛鑴?
                addUpdateListener { animation ->
                    val animatedValue = animation.animatedValue as Int
                    catDialogShowInfoViewLayoutParams.y = animatedValue

                }
            }
            val dongingAnimator = ValueAnimator.ofInt(1, 100).apply {
                duration = 1000L // 閸斻劎鏁鹃弮鍫曟毐 500ms
                addUpdateListener { animation ->
                    windowManager.updateViewLayout(
                        catDialogShowInfoView, catDialogShowInfoViewLayoutParams
                    )
                }
            }
            // 閸氬本妞傞幍褑顢戠€硅棄瀹抽崪宀勭彯鎼达箑濮╅悽?
            readyDoingTaskAnimator = AnimatorSet()
            readyDoingTaskAnimator!!.playTogether(
                widthAnimator, heightAnimator, xAnimator, yAnimator, dongingAnimator
            )

            readyDoingTaskAnimator!!.start()
        }
    }

    fun setDoing(
        catDialogShowInfoView: CatDialogShowInfoView,
        catDialogShowInfoViewLayoutParams: WindowManager.LayoutParams,
        windowManager: WindowManager,
        isShowTakeOver: Boolean = true,
    ) {
        bottomContentLayout?.visibility = VISIBLE
        ivResume?.visibility = GONE
        llResume?.visibility = GONE
        llTakeOver?.visibility = if (isShowTakeOver) {
            VISIBLE
        } else {
            GONE
        }
        gradientBorderContainer?.cornerRadiusProgress = 0.0f
        innerRelativeLayout?.setPadding(12.dpToPx(), 12.dpToPx(), 12.dpToPx(), 12.dpToPx())
        if (CatDialogStateData.viewState == CatDialogViewState.TASK_DOING) {
            return
        }
        clearAllAminAndDelay()

        val (startWH, startXY) = when (CatDialogStateData.viewState) {
            CatDialogViewState.MESSAGE_INFO -> {
                Pair(CatDialogStateData.getMessageInfoWH(), CatDialogStateData.getMessageInfoXY())
            }

            CatDialogViewState.USER_INFO -> {
                Pair(
                    CatDialogStateData.getMessageInfoStartWH(),
                    CatDialogStateData.getDoingTaskXY()
                )
            }

            CatDialogViewState.USER_INFO_HINT -> {
                Pair(CatDialogStateData.getUserInfoWH(), CatDialogStateData.getUserInfoXY())
            }

            CatDialogViewState.EMPTY -> {

                Pair(
                    CatDialogStateData.getMessageInfoStartWH(), CatDialogStateData.getDoingTaskXY()
                )
            }

            else -> {
                Pair(CatDialogStateData.getTaskDoingWH(), CatDialogStateData.getDoingTaskXY())
            }
        }

        val (endW, endH) = CatDialogStateData.getTaskDoingWH()
        val (endX, endY) = CatDialogStateData.getDoingTaskXY()
        if (CatDialogStateData.viewState == CatDialogViewState.EMPTY) {
            doingAnimatorWithWH(startWH.first, startWH.second, endW, endH)
        } else {
            doingAnimatorWithWHXY(
                startWH.first,
                startWH.second,
                endW,
                endH,
                startXY.first,
                startXY.second,
                endX,
                endY,
                catDialogShowInfoView,
                catDialogShowInfoViewLayoutParams,
                windowManager
            )
        }


    }


    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 鐏忓搫顕崣妯哄閺冭埖娲块弬鏉胯埌閻?
    }

    fun setMessage(message: String) {
        gradientTextView?.setText(message)
        updateAccessibilityAnnouncement()
    }

    fun setSubMessage(message: String) {
        executingTextView?.setText(message)
        updateAccessibilityAnnouncement()
    }

    fun finishDoingTask() {
    }

    /**
     * 閸戝棗顦幍褑顢戞禒璇插閿涘本妯夌粈鍝勫З閻㈢粯鏅ラ弸?
     * 鐎逛粙鐝禒?40dp 閸斻劎鏁鹃崚?80dp
     */
    fun readyDoingTask() {
        bottomContentLayout?.visibility = GONE
        ivResume?.visibility = GONE
        llResume?.visibility = GONE
        llTakeOver?.visibility = VISIBLE
        gradientBorderContainer?.cornerRadiusProgress = 0.0f
        innerRelativeLayout?.setPadding(12.dpToPx(), 12.dpToPx(), 12.dpToPx(), 12.dpToPx())
        visibility = GONE
        clearAllAminAndDelay()
        val (startW, startH) = CatDialogStateData.getMessageInfoStartWH()
        val (endW, endH) = CatDialogStateData.getMessageInfoWH()
        // 閼惧嘲褰囪ぐ鎾冲閻?layoutParams
        layoutParams.width = startW
        layoutParams.height = startH
        doingAnimatorWithWH(startW, startH, endW, endH)
    }


    fun clearAllAminAndDelay() {
        delayTask.cancel()
        readyDoingTaskAnimator?.cancel()
        readyDoingTaskAnimator = null
        delayTask = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private fun updateAccessibilityAnnouncement() {
        val primary = gradientTextView?.getText().orEmpty().trim()
        val secondary = executingTextView?.text?.toString().orEmpty().trim()
        val announcement = listOf(primary, secondary)
            .filter { it.isNotEmpty() }
            .joinToString(",")
        if (announcement.isEmpty()) {
            return
        }
        contentDescription = announcement
        announceForAccessibility(announcement)
    }
}


