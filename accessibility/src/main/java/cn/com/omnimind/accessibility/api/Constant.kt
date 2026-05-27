package cn.com.omnimind.accessibility.api

object Constant {
    val IGNORED_PACKAGES = listOf(
        "com.android.systemui",           // 状态栏、通知栏
        "com.google.android.inputmethod.latin", // Google输入法
        "com.samsung.android.app.clipboardedge", // Samsung边缘面板
        "com.android.quickstep",           // 最近任务
        "com.huawei.screenrecorder",//华为录屏功能 直接过滤
        "com.oplus.securitypermission",//一加权限弹框
        "com.vivo.upslide", // vivo 手势栏/侧滑栏/上滑控制层，不是真实前台应用
    )

    // 屏蔽特定类名
    val IGNORED_CLASSES = listOf(
        "android.view.View",//普通view变化
        "android.inputmethodservice.SoftInputWindow",  // 输入法窗口
        "android.widget.Toast\$ToastView",             // Toast提示
        "com.android.systemui.statusbar.phone.StatusBarWindowView", // 状态栏
        "com.android.systemui.popup.PopupContainerWithArrow", // 弹窗
        "com.huawei.screenrecorder.ScreenRecordService"//华为录屏
    )
    // 使用正则表达式匹配android.widget包下的所有类
    val IGNORED_PATTERNS = listOf(
        "^android\\.widget\\..*".toRegex(),  // 匹配android.widget包下的所有类
        "^android\\.view\\..*".toRegex(),  // 匹配android.widget包下的所有类
        "^android\\.support\\..*".toRegex()  // 匹配android.widget包下的所有类
    )
    val IGNORED_VIEWS =   listOf("toast", "popup", "dialog", "ime")
    val SYSTEM_PACKAGE_START="com.android"


    val LAUNCHER_PACKAGES = arrayOf(
        "com.google.android.apps.nexuslauncher",//Google Pixel
        "com.sec.android.app.launcher",//Samsung One UI
        "com.huawei.android.launcher",//Huawei EMUI
        "com.miui.home",//Xiaomi MIUI Launcher
        "com.oppo.launcher",//OPPO
        "com.bbk.launcher2",//vivo
        "com.realme.launcher",//Realme
        "net.oneplus.launcher",//OnePlus
        "com.lenovo.launcher",//Lenovo
        "com.zte.mifavor.launcher",//ZTE
        "com.meizu.flyme.launcher",//魅族
        "com.zte.mifavor.launcher",//中心
        "com.hihonor.android.launcher",//荣耀
        "com.android.launcher")
}
