package cn.com.omnimind.baselib.shizuku;

import android.os.ParcelFileDescriptor;

interface IOmnibotPrivilegedUserService {

    void destroy() = 16777114;

    String execute(String requestJson) = 1;

    ParcelFileDescriptor captureScreenshotPng() = 2;
}
