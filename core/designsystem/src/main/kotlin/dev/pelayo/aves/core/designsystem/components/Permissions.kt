package dev.pelayo.aves.core.designsystem.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.pelayo.aves.core.designsystem.R

fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/**
 * Si, tras pedir [permission] y recibir un no, el sistema ya no volvera a
 * ensenar el dialogo.
 *
 * Solo tiene sentido justo despues de un rechazo: antes de preguntar por
 * primera vez tambien devuelve false. Desde Android 11, dos "no" seguidos
 * equivalen a "no volver a preguntar", y a partir de ahi `launch` vuelve al
 * instante sin ensenar nada: el boton parecia no hacer nada.
 */
fun Context.isPermanentlyDenied(permission: String): Boolean {
    if (hasPermission(permission)) return false
    val activity = findActivity() ?: return false
    return !activity.shouldShowRequestPermissionRationale(permission)
}

/** Abre la pagina de la app en los ajustes del sistema, que es el unico sitio donde se puede cambiar de opinion. */
fun Context.openAppSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Explica que un permiso esta denegado para siempre y ofrece ir a los ajustes.
 *
 * @param reason que se pierde sin el permiso, en una frase.
 */
@Composable
fun PermissionBlockedHint(
    reason: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(
            onClick = { context.openAppSettings() },
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(stringResource(R.string.permission_open_settings))
        }
    }
}
