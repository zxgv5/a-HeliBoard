package helium314.keyboard.settings.screens.gesturedata

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import helium314.keyboard.latin.AppsManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.DeleteButton
import helium314.keyboard.latin.utils.GestureDataDao
import helium314.keyboard.latin.utils.dpToPx
import helium314.keyboard.latin.utils.getAppExclusionList
import helium314.keyboard.latin.utils.getAppIgnoreByDefault
import helium314.keyboard.latin.utils.getWordIgnoreList
import helium314.keyboard.latin.utils.setAppExclusionList
import helium314.keyboard.latin.utils.setAppIgnoreByDefault
import helium314.keyboard.latin.utils.setWordIgnoreList
import helium314.keyboard.settings.dialogs.InfoDialog
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog
import kotlinx.coroutines.launch
import kotlin.collections.plus

@Composable
fun PassiveGatheringSettings() {
    val ctx = LocalContext.current
    var passiveGathering by remember { mutableStateOf(false) } // todo (when implemented): read from setting
    var showInfoDialog by remember { mutableStateOf(false) }
    var showExcludedWordsDialog by remember { mutableStateOf(false) }
    var showExcludedAppsDialog by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .clickable { passiveGathering = !passiveGathering }
            .fillMaxWidth()
    ) {
        Text("passive data gathering")
        Switch(passiveGathering, { passiveGathering = it })
    }
    ButtonWithText("show details", Modifier.fillMaxWidth()) { showInfoDialog = true }
    ButtonWithText("manage excluded words", Modifier.fillMaxWidth()) { showExcludedWordsDialog = true }
    ButtonWithText("manage excluded applications", Modifier.fillMaxWidth()) { showExcludedAppsDialog = true }
    if (showInfoDialog) {
        InfoDialog("infos about passive data gathering") { showInfoDialog = false }
    }
    var packageInfos by remember { mutableStateOf(emptyList<Triple<String, String, Drawable?>>()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(packageInfos) {
        if (packageInfos.isEmpty())
            scope.launch { packageInfos = AppsManager(ctx).getPackagesWithNameAndIcon() }
    }
    if (showExcludedAppsDialog) {
        var defaultIgnore by remember { mutableStateOf(getAppIgnoreByDefault(ctx)) }
        var excludedPackages by remember { mutableStateOf(getAppExclusionList(ctx)) }
        var sortedPackagesAndNames by remember { mutableStateOf(
            packageInfos
                .sortedWith( compareBy({ it.first !in excludedPackages }, { it.second.lowercase() }))
        ) }
        LaunchedEffect(packageInfos) {
            sortedPackagesAndNames = packageInfos
                .sortedWith( compareBy({ it.first !in excludedPackages }, { it.second.lowercase() }))
        }
        var filter by remember { mutableStateOf(TextFieldValue()) }
        val scroll = rememberScrollState()
        ThreeButtonAlertDialog(
            title = { Text("select apps to exclude from passive gathering") },
            onDismissRequest = { showExcludedAppsDialog = false },
            content = { Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("ignore by default")
                    Switch(checked = defaultIgnore, onCheckedChange = { defaultIgnore = it; setAppIgnoreByDefault(ctx, it) })
                }
                TextField(
                    value = filter,
                    onValueChange = { filter = it },
                    singleLine = true,
                    label = { Text("filter") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
                )
                Spacer(Modifier.height(10.dp))
                Column( // make it lazy?
                    modifier = Modifier.verticalScroll(scroll),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    sortedPackagesAndNames.filter {
                        if (filter.text.lowercase() == filter.text)
                            filter.text in it.first || filter.text in it.second.lowercase()
                        else
                            filter.text in it.second
                    }.map { (packageName, name, icon) ->
                        val ignored = if (defaultIgnore) packageName !in excludedPackages else packageName in excludedPackages
                        Row(Modifier
                            .fillMaxWidth()
                            .clickable {
                                excludedPackages = if (ignored) excludedPackages - packageName
                                else excludedPackages + packageName
                            },
                            Arrangement.spacedBy(6.dp),
                            Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(32.dp)) {
                                if (icon != null) {
                                    val px = 32.dpToPx(LocalResources.current)
                                    Image(icon.toBitmap(px, px).asImageBitmap(), name)
                                }
                            }
                            // todo: instead of text use some icon to clarify ignored / used
                            Column {
                                CompositionLocalProvider(
                                    LocalTextStyle provides MaterialTheme.typography.bodyLarge
                                ) {
                                    Text(name)
                                }
                                CompositionLocalProvider(
                                    LocalTextStyle provides MaterialTheme.typography.bodyMedium
                                ) {
                                    Text(
                                        packageName + ", ${if (ignored) "ignored" else "used"}",
                                        color = if (ignored) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            } },
            onConfirmed = {
                setAppExclusionList(ctx, excludedPackages)
            },
            confirmButtonText = stringResource(android.R.string.ok),
            properties = DialogProperties(dismissOnClickOutside = false)
        )
    }
    if (showExcludedWordsDialog) {
        var ignoreWords by remember { mutableStateOf(getWordIgnoreList(ctx)) }
        var newWord by remember { mutableStateOf(TextFieldValue()) }
        val scroll = rememberScrollState()
        fun addWord() {
            if (newWord.text.isNotBlank())
                ignoreWords += newWord.text.trim()
            newWord = TextFieldValue()
        }
        ThreeButtonAlertDialog(
            onDismissRequest = { showExcludedWordsDialog = false },
            content = { Column(Modifier.verticalScroll(scroll)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = newWord,
                        onValueChange = { newWord = it},
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("add new word") },
                        keyboardActions = KeyboardActions { addWord() }
                    )
                    IconButton(
                        { addWord() },
                        Modifier.weight(0.2f)) {
                        Icon(painterResource(R.drawable.ic_plus), stringResource(R.string.add))
                    }
                }
                CompositionLocalProvider(
                    LocalTextStyle provides MaterialTheme.typography.bodyLarge
                ) {
                    ignoreWords.map { word ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(word)
                            DeleteButton { ignoreWords = ignoreWords.filterNot { word == it }.toSortedSet() }
                        }
                    }
                }
            } },
            onConfirmed = {
                addWord()
                setWordIgnoreList(ctx, ignoreWords)
                GestureDataDao.getInstance(ctx)?.deletePassiveWords(ignoreWords)
            },
            confirmButtonText = stringResource(android.R.string.ok),
            properties = DialogProperties(dismissOnClickOutside = false)
        )
    }
}
