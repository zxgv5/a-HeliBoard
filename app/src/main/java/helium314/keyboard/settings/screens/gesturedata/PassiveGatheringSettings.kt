// SPDX-License-Identifier: GPL-3.0-only
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import helium314.keyboard.latin.utils.GestureDataGatheringSettings
import helium314.keyboard.latin.utils.dpToPx
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.dialogs.InfoDialog
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog
import kotlinx.coroutines.launch
import kotlin.collections.plus

// functionality for gesture data gathering as part of the NLNet Project https://nlnet.nl/project/GestureTyping/
// will be removed once the project is finished

@Composable
fun PassiveGatheringSettings() {
    val ctx = LocalContext.current
    var passiveGathering by remember { mutableStateOf(GestureDataGatheringSettings.isPassiveGatheringEnabled(ctx.prefs())) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showExcludedWordsDialog by remember { mutableStateOf(false) }
    var showIncludedAppsDialog by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .clickable { passiveGathering = !passiveGathering }
            .fillMaxWidth()
    ) {
        Text(stringResource(R.string.gesture_data_passive_gathering))
        Switch(passiveGathering, { passiveGathering = it; GestureDataGatheringSettings.setPassiveGatheringEnabled(ctx.prefs(), it) })
    }
    ButtonWithText(stringResource(R.string.gesture_data_passive_gathering_info), Modifier.fillMaxWidth()) { showInfoDialog = true }
    ButtonWithText(stringResource(R.string.gesture_data_passive_excluded_words_button), Modifier.fillMaxWidth()) { showExcludedWordsDialog = true }
    ButtonWithText(stringResource(R.string.gesture_data_passive_apps_button), Modifier.fillMaxWidth()) { showIncludedAppsDialog = true }
    if (showInfoDialog) {
        // todo: explanation text
        //   include / exclude apps (included are accent colored + checkmark, others are red + x)
        //   exclude words: simple list -> word will not be saved (passive data gathering only!)
        //   data is filtered when saving, and in case of words also when exporting
        //   what is not saved?
        //    user-choices obviously
        //    text fields marked as passwords (but there is no glide typing anyway)
        //    text fields asking "no learning"
        //    email text fields
        //    mIncognitoModeEnabled
        //    excluded word in top suggestions
        //   recommend users can review data with swipe-o-scope, can check and blacklist there (send to yourself!)
        //   icon at the bottom of the keyboard when passive data gathering may record data (at least for now)
        //    icon is empty if nothing recorded for this session / text field
        //    icon is filled if a words are ready to be saved -> will happen when switching text field, closing keyboard, some other cases
        //    clear that cache by entering incognito mode or disabling passive gathering (via toolbar key / key code)
        //  toolbar key for toggling passive gathering
        //   only available if you at least once enabled the setting
        //   press to toggle (clears cache)
        //   long press to disable for 5 min (clears cache, gathering enabled on next input field change when the 5 min are over)
        // todo: read over the other texts too, maybe need update
        InfoDialog(stringResource(R.string.gesture_data_passive_gathering_info_message)) { showInfoDialog = false }
    }
    var packageInfos by remember { mutableStateOf(emptyList<Triple<String, String, Drawable?>>()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(packageInfos) {
        if (packageInfos.isEmpty())
            scope.launch { packageInfos = AppsManager(ctx).getPackagesWithNameAndIcon() }
    }
    if (showIncludedAppsDialog) {
        var defaultInclude by remember { mutableStateOf(GestureDataGatheringSettings.getAppIncludeByDefault(ctx)) }
        var excludedPackages by remember { mutableStateOf(GestureDataGatheringSettings.getAppExclusions(ctx)) }
        var sortedPackagesAndNames by remember { mutableStateOf(
            packageInfos
                .sortedWith( compareBy({ it.first !in excludedPackages }, { it.second.lowercase() }))
        ) }
        LaunchedEffect(packageInfos) {
            sortedPackagesAndNames = packageInfos
                .sortedWith( compareBy({ it.first !in excludedPackages }, { it.second.lowercase() }))
        }
        var filter by remember { mutableStateOf(TextFieldValue()) }
        ThreeButtonAlertDialog(
            title = { Text(stringResource(R.string.gesture_data_passive_apps)) },
            onDismissRequest = { showIncludedAppsDialog = false },
            content = { Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.gesture_data_passive_apps_include_default))
                    Switch(checked = defaultInclude, onCheckedChange = { defaultInclude = it; GestureDataGatheringSettings.setAppIncludeByDefault(ctx, it) })
                }
                TextField(
                    value = filter,
                    onValueChange = { filter = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.label_search_key)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
                )
                Spacer(Modifier.height(10.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val filtered = sortedPackagesAndNames.filter {
                        if (filter.text.lowercase() == filter.text)
                            filter.text in it.first || filter.text in it.second.lowercase()
                        else
                            filter.text in it.second
                    }
                    items(filtered, { it.first }) { (packageName, name, icon) ->
                        val included = if (defaultInclude) packageName !in excludedPackages else packageName in excludedPackages
                        Row(Modifier
                            .fillMaxWidth()
                            .clickable {
                                excludedPackages = if (included) excludedPackages + packageName
                                else excludedPackages - packageName
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
                            Column(Modifier.weight(1f)) {
                                CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyLarge) {
                                    Text(name)
                                }
                                CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyMedium) {
                                    Text(
                                        packageName,
                                        color = if (included) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            if (included)
                                Icon(painterResource(R.drawable.ic_setup_check), "included", Modifier.size(32.dp), MaterialTheme.colorScheme.primary)
                            else
                                Icon(painterResource(R.drawable.ic_close), "ignored", Modifier.size(32.dp), MaterialTheme.colorScheme.error)
                        }
                    }
                }
            } },
            onConfirmed = {
                GestureDataGatheringSettings.setAppExclusions(ctx, excludedPackages)
            },
            confirmButtonText = stringResource(android.R.string.ok),
            properties = DialogProperties(dismissOnClickOutside = false)
        )
    }
    if (showExcludedWordsDialog) {
        var ignoreWords by remember { mutableStateOf(GestureDataGatheringSettings.getWordExclusions(ctx)) }
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
                        label = { Text(stringResource(R.string.user_dict_add_word_button)) },
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
                GestureDataGatheringSettings.setWordExclusions(ctx, ignoreWords)
            },
            confirmButtonText = stringResource(android.R.string.ok),
            properties = DialogProperties(dismissOnClickOutside = false)
        )
    }
}
