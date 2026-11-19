package dev.canonremote.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.canonremote.app.settings.ThemeMode
import dev.canonremote.app.settings.UiPreferences

/** Static information shown in the "À propos" section. */
data class AboutInfo(val version: String, val phone: String)

/**
 * "Paramètres" tab: interface options, access to the log page, and app information.
 * The log page is a sub-page of this tab (back arrow / system back returns here).
 */
@Composable
fun SettingsTab(
    preferences: UiPreferences,
    about: AboutInfo,
    onThemeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit,
    logs: @Composable () -> Unit,
) {
    var showLogs by rememberSaveable { mutableStateOf(false) }
    if (showLogs) {
        BackHandler { showLogs = false }
        LogsPage(onBack = { showLogs = false }, content = logs)
    } else {
        SettingsScreen(
            preferences = preferences,
            about = about,
            onThemeChange = onThemeChange,
            onDynamicColorChange = onDynamicColorChange,
            onKeepScreenOnChange = onKeepScreenOnChange,
            onOpenLogs = { showLogs = true },
        )
    }
}

@Composable
fun SettingsScreen(
    preferences: UiPreferences,
    about: AboutInfo,
    onThemeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onOpenLogs: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "Paramètres",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        )

        SectionHeader("Apparence")
        ListItem(
            headlineContent = { Text("Thème") },
            supportingContent = { Text("Clair, sombre ou selon le système") },
            leadingContent = { Icon(imageVector = Icons.Filled.BrightnessAuto, contentDescription = null) },
        )
        ThemeSelector(
            selected = preferences.theme,
            onSelect = onThemeChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        SwitchItem(
            icon = Icons.Filled.Palette,
            title = "Couleurs dynamiques",
            subtitle = "Couleurs tirées du fond d'écran",
            checked = preferences.dynamicColor,
            onCheckedChange = onDynamicColorChange,
        )

        SectionHeader("Prise de vue")
        SwitchItem(
            icon = Icons.Filled.ScreenLockPortrait,
            title = "Garder l'écran allumé",
            subtitle = "Pendant que l'app est ouverte",
            checked = preferences.keepScreenOn,
            onCheckedChange = onKeepScreenOnChange,
        )

        SectionHeader("Diagnostic")
        ListItem(
            headlineContent = { Text("Journal (logs)") },
            supportingContent = { Text("Échanges avec l'appareil, à copier pour un rapport") },
            leadingContent = { Icon(imageVector = Icons.AutoMirrored.Filled.Article, contentDescription = null) },
            trailingContent = {
                Icon(imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            },
            modifier = Modifier.clickable(onClick = onOpenLogs),
        )

        SectionHeader("À propos")
        InfoItem(icon = Icons.Filled.Info, title = "Version", value = about.version)
        InfoItem(icon = Icons.Filled.PhotoCamera, title = "Appareil pris en charge", value = "Canon EOS 90D (USB)")
        InfoItem(icon = Icons.Filled.Smartphone, title = "Téléphone", value = about.phone)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSelector(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val modes = ThemeMode.entries
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                icon = {
                    SegmentedButtonDefaults.Icon(active = mode == selected) {
                        Icon(
                            imageVector = themeIcon(mode),
                            contentDescription = null,
                        )
                    }
                },
                label = { Text(mode.label) },
            )
        }
    }
}

private fun themeIcon(mode: ThemeMode): ImageVector = when (mode) {
    ThemeMode.SYSTEM -> Icons.Filled.BrightnessAuto
    ThemeMode.LIGHT -> Icons.Filled.LightMode
    ThemeMode.DARK -> Icons.Filled.DarkMode
}

@Composable
private fun SectionHeader(text: String) {
    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(imageVector = icon, contentDescription = null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.clickable { onCheckedChange(!checked) },
    )
}

@Composable
private fun InfoItem(icon: ImageVector, title: String, value: String) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value) },
        leadingContent = { Icon(imageVector = icon, contentDescription = null) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogsPage(onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Journal") },
            // The Scaffold already pads for the status bar.
            windowInsets = WindowInsets(0, 0, 0, 0),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                }
            },
        )
        content()
    }
}
