package vn.dichmanhinh.app.engine

import android.content.Context
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.tasks.await
import vn.dichmanhinh.app.data.Languages

/** Quản lý gói dịch ngoại tuyến (ML Kit Translate). Mặc định chỉ Trung + Anh + Việt. */
object OfflinePacks {
    private val mgr get() = RemoteModelManager.getInstance()

    private fun conditions(wifiOnly: Boolean): DownloadConditions =
        DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()

    suspend fun downloaded(): Set<String> = try {
        mgr.getDownloadedModels(TranslateRemoteModel::class.java).await().map { it.language }.toSet()
    } catch (e: Exception) {
        emptySet()
    }

    suspend fun isDownloaded(code: String): Boolean = try {
        mgr.isModelDownloaded(TranslateRemoteModel.Builder(code).build()).await()
    } catch (e: Exception) {
        false
    }

    suspend fun download(code: String, wifiOnly: Boolean) {
        mgr.download(TranslateRemoteModel.Builder(code).build(), conditions(wifiOnly)).await()
    }

    suspend fun delete(code: String) {
        mgr.deleteDownloadedModel(TranslateRemoteModel.Builder(code).build()).await()
    }

    /** Tải các gói mặc định chưa có. Trả về số gói tải mới thành công; lỗi từng gói được bỏ qua. */
    suspend fun ensureDefaults(wifiOnly: Boolean): Int {
        val have = downloaded()
        var n = 0
        for (c in Languages.OFFLINE_DEFAULT) {
            if (c in have) continue
            try {
                download(c, wifiOnly)
                n++
            } catch (e: Exception) {
                // bỏ qua, người dùng có thể thử lại trong màn hình Gói ngôn ngữ
            }
        }
        return n
    }
}

/**
 * OCR cho chữ Nhật / Hàn / Devanagari không nằm trong APK: tải qua Google Play Services khi cần.
 * Chữ Latin (Anh, Việt, …) và chữ Hán (Trung) đã đóng gói sẵn nên dùng ngoại tuyến ngay.
 */
object OcrPacks {
    val BUNDLED = listOf("latin", "zh")
    val EXTRA = listOf("ja", "ko", "hi")

    suspend fun isAvailable(ctx: Context, script: String): Boolean {
        if (script in BUNDLED) return true
        return try {
            ModuleInstall.getClient(ctx).areModulesAvailable(OcrEngine.recognizerFor(script)).await().areModulesAvailable()
        } catch (e: Exception) {
            false
        }
    }

    suspend fun install(ctx: Context, script: String) {
        val req = ModuleInstallRequest.newBuilder().addApi(OcrEngine.recognizerFor(script)).build()
        ModuleInstall.getClient(ctx).installModules(req).await()
    }
}
