package io.github.mokkori_tom.pwalibrary.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mokkori_tom.pwalibrary.install.IconStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun AppIcon(iconPath: String?, name: String, size: Dp = 64.dp, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(null, iconPath, name) {
        value = withContext(Dispatchers.IO) {
            iconPath?.let { path ->
                val file = File(path)
                if (file.isFile) BitmapFactory.decodeFile(path) else null
            } ?: IconStore.letterBitmap(name)
        }
    }

    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier.size(size).clip(RoundedCornerShape(size / 4.5f))
        )
    }
}
