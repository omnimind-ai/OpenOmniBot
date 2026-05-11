package cn.com.omnimind.uikit.view.data

import cn.com.omnimind.baselib.util.dpToPx
import cn.com.omnimind.baselib.util.getResColor
import cn.com.omnimind.uikit.R

class Constant {
    companion object {
        val CAT_SAFETY_MARGIN_BOTTOM = 150.dpToPx()
        val CAT_SAFETY_MARGIN_TOP = 50.dpToPx()
        val CAT_DIALOG_LAYOUT_MARGIN = 0.dpToPx()
        val CAT_VIEW_LAYOUT_MARGIN = 50.dpToPx()
        val SLIDING_THRESHOLD = 5
        val CLICK_TIME_THRESHOLD = 200
        val NORMAL_ANIM_TIME = 0L
        val QUICK_ANIM_TIME = 0L
        val BG_LINEAR_GRADIENT_COLORS=intArrayOf(
            R.color.uikit00AEFF.getResColor(),
            R.color.uikit0000AEFF.getResColor(),
            R.color.uikit00FFF7.getResColor(),
            R.color.uikit00FFF7.getResColor(),
        )
    }
}
