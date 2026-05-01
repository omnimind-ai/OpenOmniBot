package cn.com.omnimind.baselib.shizuku

import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable

data class ShizukuScreenCaptureResult(
    val success: Boolean,
    val width: Int,
    val height: Int,
    val displayWidth: Int,
    val displayHeight: Int,
    val rotation: Int,
    val mimeType: String,
    val method: String,
    val elapsedMs: Long,
    val imageFd: ParcelFileDescriptor?,
    val errorMessage: String?
) : Parcelable {

    @Suppress("DEPRECATION")
    constructor(parcel: Parcel) : this(
        success = parcel.readInt() != 0,
        width = parcel.readInt(),
        height = parcel.readInt(),
        displayWidth = parcel.readInt(),
        displayHeight = parcel.readInt(),
        rotation = parcel.readInt(),
        mimeType = parcel.readString().orEmpty(),
        method = parcel.readString().orEmpty(),
        elapsedMs = parcel.readLong(),
        imageFd = parcel.readParcelable(ParcelFileDescriptor::class.java.classLoader),
        errorMessage = parcel.readString()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(if (success) 1 else 0)
        parcel.writeInt(width)
        parcel.writeInt(height)
        parcel.writeInt(displayWidth)
        parcel.writeInt(displayHeight)
        parcel.writeInt(rotation)
        parcel.writeString(mimeType)
        parcel.writeString(method)
        parcel.writeLong(elapsedMs)
        parcel.writeParcelable(imageFd, flags)
        parcel.writeString(errorMessage)
    }

    override fun describeContents(): Int {
        return if (imageFd != null) Parcelable.CONTENTS_FILE_DESCRIPTOR else 0
    }

    fun readImageBytesAndClose(): ByteArray {
        val descriptor = imageFd ?: return ByteArray(0)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            input.readBytes()
        }
    }

    companion object CREATOR : Parcelable.Creator<ShizukuScreenCaptureResult> {
        override fun createFromParcel(parcel: Parcel): ShizukuScreenCaptureResult {
            return ShizukuScreenCaptureResult(parcel)
        }

        override fun newArray(size: Int): Array<ShizukuScreenCaptureResult?> {
            return arrayOfNulls(size)
        }

        fun success(
            width: Int,
            height: Int,
            displayWidth: Int,
            displayHeight: Int,
            rotation: Int,
            mimeType: String,
            method: String,
            elapsedMs: Long,
            imageFd: ParcelFileDescriptor
        ): ShizukuScreenCaptureResult {
            return ShizukuScreenCaptureResult(
                success = true,
                width = width,
                height = height,
                displayWidth = displayWidth,
                displayHeight = displayHeight,
                rotation = rotation,
                mimeType = mimeType,
                method = method,
                elapsedMs = elapsedMs,
                imageFd = imageFd,
                errorMessage = null
            )
        }

        fun error(message: String, method: String = "none", elapsedMs: Long = 0L): ShizukuScreenCaptureResult {
            return ShizukuScreenCaptureResult(
                success = false,
                width = 0,
                height = 0,
                displayWidth = 0,
                displayHeight = 0,
                rotation = 0,
                mimeType = "",
                method = method,
                elapsedMs = elapsedMs,
                imageFd = null,
                errorMessage = message
            )
        }
    }
}
