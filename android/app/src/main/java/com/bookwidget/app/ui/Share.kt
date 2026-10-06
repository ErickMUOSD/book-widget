package com.bookwidget.app.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.bookwidget.app.data.Api
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Genera la tarjeta PNG en el servidor (sellada o revelada) y abre el menú de compartir de Android. */
suspend fun shareCard(ctx: Context, api: Api, bookId: Long, sealed: Boolean, title: String) {
    val bytes = api.shareCard(bookId, sealed)
    val uri = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "share").also { it.mkdirs() }
        val file = File(dir, "pre-spoiler.png").also { it.writeBytes(bytes) }
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, title)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
