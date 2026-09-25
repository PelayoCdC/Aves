package dev.pelayo.aves.feature.camera

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * "Encuadra el ave": la foto entera con un marco cuadrado que se arrastra y se
 * agranda con dos dedos. Al analizar, el modelo ve solo lo de dentro.
 *
 * La geometria (que el marco no se salga ni se haga enano) vive en
 * [transformSquareCrop], con tests; aqui solo se traducen pixeles de pantalla a
 * pixeles de la imagen.
 *
 * @param minSize lado minimo del marco en pixeles de la imagen.
 */
@Composable
internal fun FramingDialog(
    photo: Bitmap,
    minSize: Int,
    onAnalyze: (SquareCrop) -> Unit,
    onDismiss: () -> Unit,
) {
    var crop by remember(photo) { mutableStateOf(centerSquareCrop(photo.width, photo.height)) }
    val image = remember(photo) { photo.asImageBitmap() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .safeDrawingPadding()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.camera_framing_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val density = LocalDensity.current
                val boxWidth = with(density) { maxWidth.toPx() }
                val boxHeight = with(density) { maxHeight.toPx() }
                // Como la pinta ContentScale.Fit: escala y hueco a los lados.
                val scale = minOf(boxWidth / photo.width, boxHeight / photo.height)
                val imageLeft = (boxWidth - photo.width * scale) / 2f
                val imageTop = (boxHeight - photo.height * scale) / 2f

                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(photo, scale) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                crop = transformSquareCrop(
                                    crop,
                                    panX = pan.x / scale,
                                    panY = pan.y / scale,
                                    zoom = zoom,
                                    width = photo.width,
                                    height = photo.height,
                                    minSize = minSize,
                                )
                            }
                        },
                ) {
                    val frameTopLeft = Offset(imageLeft + crop.left * scale, imageTop + crop.top * scale)
                    val frameSize = Size(crop.size * scale, crop.size * scale)
                    // Fuera del marco, velado: se ve que es lo que NO va a mirar el modelo.
                    clipRect(
                        left = frameTopLeft.x,
                        top = frameTopLeft.y,
                        right = frameTopLeft.x + frameSize.width,
                        bottom = frameTopLeft.y + frameSize.height,
                        clipOp = ClipOp.Difference,
                    ) {
                        drawRect(
                            color = Color.Black.copy(alpha = 0.55f),
                            topLeft = Offset(imageLeft, imageTop),
                            size = Size(photo.width * scale, photo.height * scale),
                        )
                    }
                    drawRect(
                        color = Color.White,
                        topLeft = frameTopLeft,
                        size = frameSize,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.camera_framing_cancel), color = Color.White)
                }
                OutlinedButton(onClick = { crop = centerSquareCrop(photo.width, photo.height) }) {
                    Text(stringResource(R.string.camera_framing_center), color = Color.White)
                }
                Button(onClick = { onAnalyze(crop) }) {
                    Text(stringResource(R.string.camera_framing_analyze))
                }
            }
        }
    }
}
