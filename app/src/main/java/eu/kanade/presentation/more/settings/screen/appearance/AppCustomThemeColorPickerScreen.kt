package eu.kanade.presentation.more.settings.screen.appearance

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.navigation3.runtime.NavKey
import com.github.skydoves.colorpicker.compose.rememberColorPickerController
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.widget.ThemeColorPickerWidget
import kotlinx.serialization.Serializable
import mihon.app.di.globalAppGraph
import mihon.core.navigation.util.LocalBackStack
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

@Serializable
data object AppCustomThemeColorPickerRoute : NavKey

@Composable
fun AppCustomThemeColorPickerScreen() {
    val uiPreferences: UiPreferences = globalAppGraph.uiPreferences

    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val controller = rememberColorPickerController()

    val customColorPref = uiPreferences.colorTheme()
    val customColor by customColorPref.collectAsState()

    val appThemePref = uiPreferences.appTheme()

    val currentColor by remember {
        mutableIntStateOf(customColor)
    }

    LaunchedEffect(customColorPref) {
        customColorPref.set(currentColor)
    }

    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(KMR.strings.pref_custom_color),
                navigateUp = backStack::removeLastOrNull,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier.padding(contentPadding),
        ) {
            ThemeColorPickerWidget(
                controller = controller,
                initialColor = Color(currentColor),
                onItemClick = { color, appTheme ->
                    customColorPref.set(color.toArgb())
                    appThemePref.set(appTheme)
                    (context as? Activity)?.let { ActivityCompat.recreate(it) }
                },
            )
        }
    }
}
