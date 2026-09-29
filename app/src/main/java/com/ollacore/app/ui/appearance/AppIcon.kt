package com.ollacore.app.ui.appearance

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.ollacore.app.R

/** Launcher icon choices. Aliases live in the manifest (disabled by default). */
enum class AppIconOption(val label: String, val suffix: String?, val drawable: Int) {
    DEFAULT("Default", null, R.drawable.ic_launcher),
    SUNSET("Sunset", ".LauncherSunset", R.drawable.ic_launcher_sunset),
    OCEAN("Ocean", ".LauncherOcean", R.drawable.ic_launcher_ocean);
}

object AppIconSwitcher {
    private fun component(context: Context, option: AppIconOption): ComponentName =
        ComponentName(context, context.packageName + (option.suffix ?: ".MainActivity"))

    fun current(context: Context): AppIconOption {
        val pm = context.packageManager
        // The enabled alias wins; default when none is explicitly enabled.
        AppIconOption.entries.forEach { option ->
            if (option.suffix != null &&
                pm.getComponentEnabledSetting(component(context, option)) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            ) return option
        }
        return AppIconOption.DEFAULT
    }

    /** Enables one icon, disables the rest. The launcher refreshes on its own. */
    fun apply(context: Context, option: AppIconOption) {
        val pm = context.packageManager
        AppIconOption.entries.forEach {
            if (it.suffix != null) {
                pm.setComponentEnabledSetting(
                    component(context, it),
                    if (it == option) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        }
    }
}

@Composable
fun AppIconDialog(
    current: AppIconOption,
    onPick: (AppIconOption) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("App icon") },
        text = {
            Column {
                Text(
                    "Pick a launcher icon. It updates on your home screen shortly.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                AppIconOption.entries.forEach { option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(option) }
                            .padding(vertical = 10.dp)
                    ) {
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(option.drawable),
                            contentDescription = null,
                            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(option.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        RadioButton(selected = option == current, onClick = { onPick(option) })
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
