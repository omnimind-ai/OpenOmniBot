package cn.com.omnimind.baselib.shizuku;

import cn.com.omnimind.baselib.shizuku.ShizukuScreenCaptureResult;

interface IOmnibotScreenCaptureService {

    void destroy() = 16777114;

    ShizukuScreenCaptureResult captureScreen(int maxWidth, int maxHeight, int quality) = 1;
}
