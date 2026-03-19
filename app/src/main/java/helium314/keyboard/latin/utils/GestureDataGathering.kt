// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import com.android.inputmethod.latin.BinaryDictionary
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.InputAttributes
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.R
import helium314.keyboard.latin.SingleDictionaryFacilitator
import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.common.InputPointers
import helium314.keyboard.latin.common.StringUtils
import helium314.keyboard.latin.database.Database
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.dictionary.ReadOnlyBinaryDictionary
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.settings.SettingsDestination
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog
import helium314.keyboard.settings.screens.gesturedata.END_DATE_EPOCH_MILLIS
import helium314.keyboard.settings.screens.gesturedata.TWO_WEEKS_IN_MILLIS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.DateFormat
import java.util.Date
import java.util.SortedSet
import kotlin.random.Random

// functionality for gesture data gathering as part of the NLNet Project https://nlnet.nl/project/GestureTyping/
// will be removed once the project is finished

fun isInActiveGatheringMode(editorInfo: EditorInfo) =
    dictTestImeOption == editorInfo.privateImeOptions && gestureDataActiveFacilitator != null

fun isPassiveGatheringEnabled(prefs: SharedPreferences) = prefs.getBoolean(PREF_PASSIVE_ENABLED, false)

fun setPassiveGatheringEnabled(prefs: SharedPreferences, enabled: Boolean) =
    prefs.edit { putBoolean(PREF_PASSIVE_ENABLED, enabled) }

// todo: check interaction with (inline) emoji search
//  and shortcuts (emoji dicts and others)
// todo: non-empty cache should change color of "recording" icon
//  icon needs to be described in the text (and maybe have some dark outline in case user has red keyboard)
// todo: optional toolbar button to stop collection (forever, for 5 min or whatever)
//  should also clear the cache
//  describe button in the infotext
// todo: describe in info text exactly what is stored
object PassiveGatheringCache {
    private val cachedWords = mutableListOf<WordData>()
    private const val TAG = "PassiveGathering"
    private val scope = CoroutineScope(Dispatchers.IO)

    fun addWord(word: WordData) {
        // initial target word is first (modified) suggestion, may have different capitalization
        // -> target word as var so we can update it?
        Log.i(TAG, "adding ${word.usedWord}")
        cachedWords.add(word)
    }

    fun onPickSuggestionAfterGesturing(suggestion: SuggestedWords.SuggestedWordInfo, originalWord: String) {
        // replace the latest entry in cache, or is there any chance we come here other than right after gesture typing?
        // anyway, use originalWord to make sure we're replacing the right thing
        Log.i(TAG, "picked ${suggestion.word} instead of $originalWord after gesturing")
        val lastEntry = cachedWords.lastOrNull()
        if (lastEntry == null) {
            // log message?
            Log.w(TAG, "...but cache is empty")
            return
        }
        if (lastEntry.usedWord != originalWord) {
            // log message?
            Log.w(TAG, "...but our last word is ${lastEntry.usedWord}, not $originalWord")
            return
        }
        lastEntry.usedWord = suggestion.mWord
        lastEntry.targetWord = suggestion.mWord
    }

    fun onPickSuggestion(suggestion: SuggestedWords.SuggestedWordInfo, originalWord: String) {
        Log.i(TAG, "picked ${suggestion.word} instead of $originalWord")
        // this happen after tap-typing (new word or corrected gesture word), or when moving the cursor and then selecting a different suggestion
        // don't update anything if we have the word more than once
        val word = cachedWords.singleOrNull { it.usedWord == originalWord } ?: return
        word.usedWord = suggestion.mWord
        word.targetWord = suggestion.mWord
    }

    fun onRejectedSuggestion(suggestion: String) {
        Log.i(TAG, "rejected $suggestion")
        if (cachedWords.lastOrNull()?.usedWord != suggestion) {
            Log.w(TAG, "...but last word is ${cachedWords.lastOrNull()?.usedWord}")
            return
        }
        cachedWords.removeAt(cachedWords.lastIndex)
    }

    fun onEdit(word: String) {
        // todo: not sure whether this should be kept, because repeated backspace might remove different words
        Log.i(TAG, "edit something in $word")
        cachedWords.removeAll { it.usedWord == word }
    }

    fun flush(context: Context) {
        // save all words and clear cache
        val words = cachedWords.toList()
        Log.i(TAG, "flush cache")
        cachedWords.clear()
        scope.launch { words.forEach { it.save(context) } }
    }

    fun clear() {
        // just clear it without saving
        Log.i(TAG, "clear cache")
        cachedWords.clear()
    }
}

@JvmField
var usePassiveGathering = false

fun setUsePassiveGathering(context: Context, editorInfo: EditorInfo): Boolean {
    usePassiveGathering = isPassiveGatheringUsed(context, editorInfo)
    if (!usePassiveGathering)
        PassiveGatheringCache.clear()
    return usePassiveGathering
}

private fun isPassiveGatheringUsed(context: Context, editorInfo: EditorInfo): Boolean {
    if (!JniUtils.sHaveGestureLib) return false
    if (!isPassiveGatheringEnabled(context.prefs())) return false
    if (Settings.getValues().mIncognitoModeEnabled) return false
    val inputAttributes = InputAttributes(editorInfo, false, "")
    val isEmailField = InputTypeUtils.isEmailVariation(inputAttributes.mInputType and InputType.TYPE_MASK_VARIATION)
    if (inputAttributes.mIsPasswordField || inputAttributes.mNoLearning || isEmailField) return false
    if (isForbiddenForDataGathering(editorInfo.packageName, context)) return false
    // we might not have a known dictionary, but I guess that's acceptable
    return true
}

fun setWordExclusions(context: Context, list: Collection<String>) {
    excludedWords = null
    val json = Json.encodeToString(list)
    // todo: when excluding a word, it should be removed from db, but also from suggestions of existing entries -> this will be awful
    context.prefs().edit { putString(PREF_WORD_EXCLUSIONS, json) }
}

fun getWordExclusions(context: Context): Set<String> {
    excludedWords?.let { return it }
    val json = context.prefs().getString(PREF_WORD_EXCLUSIONS, "[]") ?: "[]"
    excludedWords = if (json.isEmpty()) sortedSetOf()
    else Json.decodeFromString<List<String>>(json).toSortedSet(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
    return excludedWords!!
}

private var excludedWords: SortedSet<String>? = null

fun setAppExclusionList(context: Context, list: Collection<String>) {
    context.prefs().edit { putString(PREF_APP_EXCLUSIONS, list.joinToString(",")) }
}

fun getAppExclusionList(context: Context): List<String> {
    val string = context.prefs().getString(PREF_APP_EXCLUSIONS, "") ?: ""
    return string.split(",").filterNot { it.isEmpty() }
}

fun setAppIncludeByDefault(context: Context, value: Boolean) =
    context.prefs().edit { putBoolean(PREF_APP_EXCLUSIONS_INCLUDE_BY_DEFAULT, value) }

fun getAppIncludeByDefault(context: Context) =
    context.prefs().getBoolean(PREF_APP_EXCLUSIONS_INCLUDE_BY_DEFAULT, false)

fun isForbiddenForDataGathering(packageName: String?, context: Context): Boolean {
    val exclusions = getAppExclusionList(context)
    return if (getAppIncludeByDefault(context)) packageName in exclusions
    else packageName !in exclusions
}

fun addExportedActiveDeletionCount(context: Context, count: Int) {
    val oldCount = getExportedActiveDeletionCount(context)
    context.prefs().edit { putInt(PREF_DELETED_ACTIVE, oldCount + count) }
}

fun getExportedActiveDeletionCount(context: Context) = context.prefs().getInt(PREF_DELETED_ACTIVE, 0)

/** shows dialog promoting contribution of gesture data, or ask to do again if last contribution was more than 2 weeks ago */
@Composable fun GestureDataPromotionReminderDialog() {
    val ctx = LocalContext.current
    val promotionShowNext = ctx.prefs().getLong(PREF_SHOW_PROMOTION_DIALOG_NEXT, 0)
    val reminderShowNext = ctx.prefs().getLong(PREF_SHOW_REMINDER_DIALOG_NEXT, 0)
    val neverShow = promotionShowNext == Long.MAX_VALUE || reminderShowNext == Long.MAX_VALUE // user selected "don't show again"
        // we only show the dialog if the use actively loaded the gesture typing library (as opposed to having the lib in the system and HeliBoard as a system app)
        || ctx.protectedPrefs().getString(Settings.PREF_LIBRARY_CHECKSUM, "").isNullOrEmpty() || !JniUtils.sHaveGestureLib
    var shouldShowReminder by remember { mutableStateOf(
        !neverShow && reminderShowNext < System.currentTimeMillis() && reminderShowNext > 0L
    ) }
    var shouldShowPromotion by remember { mutableStateOf(
        // only show if the user never contributed data
        !neverShow && promotionShowNext < System.currentTimeMillis() && reminderShowNext == 0L
    ) }
    if (shouldShowPromotion) {
        ThreeButtonAlertDialog(
            cancelButtonText = stringResource(R.string.ask_later),
            onDismissRequest = {
                ctx.prefs().edit { putLong(PREF_SHOW_PROMOTION_DIALOG_NEXT, System.currentTimeMillis() + 30L * 60 * 60 * 1000) }
                shouldShowPromotion = false
            },
            title = { Text(stringResource(R.string.gesture_data_screen)) },
            content = { Text(stringResource(R.string.gesture_data_promotion_message)) },
            confirmButtonText = stringResource(R.string.gesture_data_take_me_there),
            onConfirmed = { SettingsDestination.navigateTo(SettingsDestination.DataGathering) },
            neutralButtonText = stringResource(R.string.no_dictionary_dont_show_again_button),
            onNeutral = {
                ctx.prefs().edit { putLong(PREF_SHOW_PROMOTION_DIALOG_NEXT, Long.MAX_VALUE) }
                shouldShowPromotion = false
            },
        )
    }
    if (shouldShowReminder) {
        ThreeButtonAlertDialog(
            cancelButtonText = stringResource(R.string.ask_later),
            onDismissRequest = {
                ctx.prefs().edit { putLong(PREF_SHOW_REMINDER_DIALOG_NEXT, System.currentTimeMillis() + 30L * 60 * 60 * 1000) }
                shouldShowReminder = false
            },
            title = { Text(stringResource(R.string.gesture_data_screen)) },
            content = { Text(stringResource(R.string.gesture_data_reminder_message)) },
            confirmButtonText = stringResource(R.string.gesture_data_take_me_there),
            onConfirmed = { SettingsDestination.navigateTo(SettingsDestination.DataGathering) },
            neutralButtonText = stringResource(R.string.no_dictionary_dont_show_again_button),
            onNeutral = {
                ctx.prefs().edit { putLong(PREF_SHOW_REMINDER_DIALOG_NEXT, Long.MAX_VALUE) }
                shouldShowReminder = false
            },
        )
    }
}

/** shows a toast notification if we're close to the end of the data gathering phase (at most once per 24 hours, only if there is non-exported data) */
fun showEndNotificationIfNecessary(context: Context) {
    val now = System.currentTimeMillis()
    if (now < END_DATE_EPOCH_MILLIS - TWO_WEEKS_IN_MILLIS) return
    val lastShown = context.prefs().getLong(PREF_END_NOTIFICATION_LAST_SHOWN, 0)
    if (lastShown > now - 24L * 60 * 60 * 1000) return // show at most once per 24 hours
    context.prefs().edit { putLong(PREF_END_NOTIFICATION_LAST_SHOWN, now) } // set even if we have nothing to tell
    val notExported = GestureDataDao.getInstance(context)?.count(exported = false) ?: 0
    if (notExported == 0) return // nothing to export

    // show a toast
    val endDate = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(END_DATE_EPOCH_MILLIS))
    KeyboardSwitcher.getInstance().showToast(context.getString(R.string.gesture_data_ends_at, endDate), false)
}

private const val PREF_WORD_EXCLUSIONS = "gesture_data_word_exclusions"
private const val PREF_APP_EXCLUSIONS = "gesture_data_app_exclusions"
private const val PREF_APP_EXCLUSIONS_INCLUDE_BY_DEFAULT = "gesture_data_app_exclusions_ignore_by_default"
private const val PREF_DELETED_ACTIVE = "gesture_data_deleted_active_words"
private const val PREF_PASSIVE_NOTIFY_COUNT = "gesture_data_passive_notify_count"
private const val PREF_PASSIVE_ENABLED = "gesture_data_passive_gathering_enabled"
private const val PREF_END_NOTIFICATION_LAST_SHOWN = "gesture_data_end_notification_shown"
private const val PREF_SHOW_PROMOTION_DIALOG_NEXT = "gesture_data_show_promotion_dialog_next_time"
private const val PREF_SHOW_REMINDER_DIALOG_NEXT = "gesture_data_show_reminder_dialog_next_time"

const val dictTestImeOption = "useTestDictionaryFacilitator,${BuildConfig.APPLICATION_ID}.${Constants.ImeOption.NO_FLOATING_GESTURE_PREVIEW}"

var gestureDataActiveFacilitator: SingleDictionaryFacilitator? = null

private val scope = CoroutineScope(Dispatchers.IO)

// class for storing relevant information
class WordData(
    var targetWord: String?, // might be adjusted when using passive gathering
    val suggestions: SuggestionResults,
    val composedData: ComposedData,
    val ngramContext: NgramContext,
    val keyboard: Keyboard,
    val inputStyle: Int,
    val activeMode: Boolean,
    var usedWord: String? = null // first suggestion in passive gathering, used to track later changes (not saved)
) {
    // keyboard is not immutable, so better store potentially relevant information immediately
    private val keys = keyboard.sortedKeys
    private val height = keyboard.mOccupiedHeight
    private val width = keyboard.mOccupiedWidth

    private val packageName = keyboard.mId.mEditorInfo.packageName

    // we want to store which dictionaries are used, and a dict index (in used dict list) for each suggestion
    private var dictCount = 0
    private val dictionariesInSuggestions = LinkedHashMap<Dictionary, Int>().apply { // linked because we need the order
        suggestions.forEach { if (!containsKey(it.mSourceDict)) put(it.mSourceDict, dictCount++) }
    }

    private val timestamp = System.currentTimeMillis()

    fun save(context: Context) {
        if (context.prefs().getLong(PREF_SHOW_PROMOTION_DIALOG_NEXT, 0) < Long.MAX_VALUE)
            context.prefs().edit { putLong(PREF_SHOW_REMINDER_DIALOG_NEXT, System.currentTimeMillis() + TWO_WEEKS_IN_MILLIS) }
        if (!isSavingOk(context))
            return
        val dao = GestureDataDao.getInstance(context) ?: return

        val keyboardInfo = KeyboardInfo(
            width, // baseHeight is without padding, but coordinates include padding
            height,
            keys.map {
                KeyInfo(
                    it.x, it.width, it.y, it.height,
                    it.outputText ?: if (it.code > 0) StringUtils.newSingleCodePointString(it.code) else "",
                    it.popupKeys.orEmpty().map { popup ->
                        popup.mOutputText ?: if (popup.mCode > 0) StringUtils.newSingleCodePointString(popup.mCode) else ""
                    }
                )
            }
        )
        val filteredSuggestions = mutableListOf<SuggestedWords.SuggestedWordInfo>()
        for (word in suggestions) { // suggestions are sorted with highest score first
            if (word.mSourceDict.mDictType == Dictionary.TYPE_CONTACTS
                || suggestions.any { it.mWord == word.mWord && it.mSourceDict.mDictType == Dictionary.TYPE_CONTACTS })
                continue // never store contacts (might be in user history too)
            // for the personal dictionary we rely on the ignore list
            if (word.mScore < 0 && filteredSuggestions.size > 5)
                continue // no need to add bad matches
            if (filteredSuggestions.any { it.mWord == word.mWord })
                continue // only first occurrence word, todo: ask whether this is ok!
            if (filteredSuggestions.size > 12)
                continue // should be enough
            if (!activeMode && word.mWord in getWordExclusions(context))
                continue // keep blocked suggestions in active mode because otherwise one might find out which word is blocked
            filteredSuggestions.add(word)
        }
        val data = GestureData(
            context.getString(R.string.english_ime_name) + " " + BuildConfig.VERSION_NAME,
            if (!context.protectedPrefs().contains(Settings.PREF_LIBRARY_CHECKSUM)) null
                else context.protectedPrefs().getString(Settings.PREF_LIBRARY_CHECKSUM, "") == JniUtils.expectedDefaultChecksum(),
            targetWord,
            dictionariesInSuggestions.map { (dict, _) ->
                val hash = (dict as? BinaryDictionary)?.hash ?: (dict as? ReadOnlyBinaryDictionary)?.hash
                DictInfo(hash, dict.mDictType, dict.mLocale?.toLanguageTag())
            },
            // todo: check whether the index really is correct!
            filteredSuggestions.map { Suggestion(it.mWord, it.mScore, dictionariesInSuggestions[it.mSourceDict]) },
            PointerData.fromPointers(composedData.mInputPointers),
            keyboardInfo,
            activeMode,
            null
        )
        scope.launch { dao.add(data, targetWord ?: usedWord, timestamp) }
        informAboutTooManyPassiveModeWords(context, dao)
    }

    // show a toast every 5k words, to avoid having to upload multiple files at a time because they are over their email attachment size limit
    // but don't check on every word, because getting count from DB is not free
    private fun informAboutTooManyPassiveModeWords(context: Context, dao: GestureDataDao) {
        if (!activeMode || Random.nextInt() % 20 != 0) return
        val count = dao.count(exported = false, activeMode = false)
        val nextNotifyCount = context.prefs().getInt(PREF_PASSIVE_NOTIFY_COUNT, 5000)
        if (count <= nextNotifyCount) return
        val approxCount = (count / 1000) * 1000
        // show a toast
        KeyboardSwitcher.getInstance().showToast(context.getString(R.string.gesture_data_many_not_shared_words, approxCount.toString()), true)
        context.prefs().edit { putInt(PREF_PASSIVE_NOTIFY_COUNT, approxCount + 5000) }
    }

    // find when we should NOT save
    private fun isSavingOk(context: Context): Boolean {
        if (inputStyle != SuggestedWords.INPUT_STYLE_TAIL_BATCH)
            return false
        if (activeMode && dictCount == 1)
            return true // active mode should be fine, the size check is just an addition in case there is a bug that sets the wrong mode or dictionary facilitator
        if (Settings.getValues().mIncognitoModeEnabled)
            return false // don't save in incognito mode
        if (!activeMode && !isPassiveGatheringEnabled(context.prefs()))
            return false
        if (!activeMode && isForbiddenForDataGathering(packageName, context))
            return false // package ignored (we should never come here in this case, but better be safe)
        val inputAttributes = InputAttributes(keyboard.mId.mEditorInfo, false, "")
        val isEmailField = InputTypeUtils.isEmailVariation(inputAttributes.mInputType and InputType.TYPE_MASK_VARIATION)
        if (inputAttributes.mIsPasswordField || inputAttributes.mNoLearning || isEmailField)
            return false // probably some more inputAttributes to consider
        val ignoreWords = getWordExclusions(context)
        // how to deal with the ignore list?
        // check targetWord and first 5 suggestions?
        // or check only what is in the actually saved suggestions?
        if (usedWord in ignoreWords || suggestions.take(5).any { it.word in ignoreWords })
            return false
        if (suggestions.first().mSourceDict.mDictType == Dictionary.TYPE_CONTACTS)
            return false
        return true
    }
}

data class GestureDataInfo(val id: Long, val targetWord: String, val timestamp: Long, val exported: Boolean, val activeMode: Boolean)

@Serializable
data class GestureData(
    val application: String,
    val knownLibrary: Boolean?,
    val targetWord: String?,
    val dictionaries: List<DictInfo>,
    val suggestions: List<Suggestion>,
    val gesture: List<PointerData>,
    val keyboardInfo: KeyboardInfo,
    val activeMode: Boolean,
    val uuid: String?
)

// hash is only available for dictionaries from .dict files
// language can be null (but should not be)
@Serializable
data class DictInfo(val hash: String?, val type: String, val language: String?)

@Serializable
data class Suggestion(val word: String, val score: Int, val dictIndex: Int? = null)

@Serializable
data class PointerData(val id: Int, val x: Int, val y: Int, val millis: Int) {
    companion object {
        fun fromPointers(pointers: InputPointers): List<PointerData> {
            val result = mutableListOf<PointerData>()
            for (i in 0..<pointers.pointerSize) {
                result.add(PointerData(
                    pointers.pointerIds[i],
                    pointers.xCoordinates[i],
                    pointers.yCoordinates[i],
                    pointers.times[i]
                ))
            }
            return result
        }
    }
}

// the old gesture typing library only works with code, not with arbitrary text
// but we take the output text (usually still a single codepoint) because we'd like to change this
@Serializable
data class KeyInfo(val left: Int, val width: Int, val top: Int, val height: Int, val value: String, val alts: List<String>)

@Serializable
data class KeyboardInfo(val width: Int, val height: Int, val keys: List<KeyInfo>)

class GestureDataDao(val db: Database) {
    fun add(data: GestureData, word: String?, timestamp: Long) = synchronized(this) {
        require(data.uuid == null)
        val jsonString = Json.encodeToString(data)
        // if uuid in the resulting string is replaced with null, we should be able to reproduce it
        val dataWithId = data.copy(uuid = ChecksumCalculator.checksum(jsonString.byteInputStream()))
        val cv = ContentValues(3)
        cv.put(COLUMN_TIMESTAMP, timestamp)
        cv.put(COLUMN_WORD, word) // we may store the "usedWord" here, because the user should be able to find what they entered
        if (data.activeMode)
            cv.put(COLUMN_SOURCE_ACTIVE, 1)
        cv.put(COLUMN_DATA, Json.encodeToString(dataWithId))
        db.writableDatabase.insert(TABLE, null, cv)
    }

    fun filterInfos(
        word: String? = null,
        begin: Long? = null,
        end: Long? = null,
        exported: Boolean? = null,
        activeMode: Boolean? = null,
        limit: Int? = null
    ): List<GestureDataInfo> = synchronized(this) {
        val result = mutableListOf<GestureDataInfo>()
        val query = mutableListOf<String>()
        if (word != null) query.add("LOWER($COLUMN_WORD) like ?||'%'")
        if (begin != null) query.add("$COLUMN_TIMESTAMP >= $begin")
        if (end != null) query.add("$COLUMN_TIMESTAMP <= $end")
        if (exported != null) query.add("$COLUMN_EXPORTED = ${if (exported) 1 else 0}")
        if (activeMode != null) query.add("$COLUMN_SOURCE_ACTIVE = ${if (activeMode) 1 else 0}")
        db.readableDatabase.query(
            TABLE,
            arrayOf(COLUMN_ID, COLUMN_WORD, COLUMN_TIMESTAMP, COLUMN_EXPORTED, COLUMN_SOURCE_ACTIVE),
            query.joinToString(" AND "),
            word?.let { arrayOf(it.lowercase()) },
            null,
            null,
            null,
            limit?.toString()
        ).use {
            while (it.moveToNext()) {
                result.add(GestureDataInfo(
                    it.getLong(0),
                    it.getString(1),
                    it.getLong(2),
                    it.getInt(3) != 0,
                    it.getInt(4) != 0
                ))
            }
        }
        return result
    }

    fun getJsonData(ids: List<Long>): Sequence<String> = synchronized(this) { sequence {
        db.readableDatabase.query(
            TABLE,
            arrayOf(COLUMN_DATA),
            "$COLUMN_ID IN (${ids.joinToString(",")})",
            null,
            null,
            null,
            null
        ).use {
            while (it.moveToNext()) {
                yield(it.getString(0))
            }
        }
    }}

    fun getAllJsonData(): Sequence<String> = synchronized(this) { sequence {
        db.readableDatabase.query(
            TABLE,
            arrayOf(COLUMN_DATA),
            null,
            null,
            null,
            null,
            null
        ).use {
            while (it.moveToNext()) {
                yield(it.getString(0))
            }
        }
    }}

    fun markAsExported(ids: List<Long>, context: Context) = synchronized(this) {
        if (ids.isEmpty()) return
        val cv = ContentValues(1)
        cv.put(COLUMN_EXPORTED, 1)
        db.writableDatabase.update(TABLE, cv, "$COLUMN_ID IN (${ids.joinToString(",")})", null)
        if (count(exported = false, activeMode = false) < context.prefs().getInt(PREF_PASSIVE_NOTIFY_COUNT, 0))
            context.prefs().edit { remove(PREF_PASSIVE_NOTIFY_COUNT) } // reset if we exported passive data
    }

    fun delete(ids: List<Long>, onlyExported: Boolean, context: Context): Int = synchronized(this) {
        if (ids.isEmpty()) return 0
        val where = "$COLUMN_ID IN (${ids.joinToString(",")})"
        val whereExported = " AND $COLUMN_EXPORTED <> 0"
        val count: Int
        if (onlyExported) {
            count = db.writableDatabase.delete(TABLE, where + whereExported, null)
            addExportedActiveDeletionCount(context, count) // actually we could also have a counter in the db
        } else {
            val exportedCount = db.readableDatabase.rawQuery("SELECT COUNT(1) FROM $TABLE WHERE $where$whereExported", null).use {
                it.moveToFirst()
                it.getInt(0)
            }
            count = db.writableDatabase.delete(TABLE, where, null)
            addExportedActiveDeletionCount(context, exportedCount)
        }
        return count
    }

    fun deleteAll() = synchronized(this) {
        db.writableDatabase.delete(TABLE, null, null)
    }

    fun deletePassiveWords(words: Collection<String>) = synchronized(this) {
        val wordsString = words.joinToString("','") { it.lowercase() }
        db.writableDatabase.delete(
            TABLE,
            "$COLUMN_SOURCE_ACTIVE <> 0 AND LOWER($COLUMN_WORD) in (?)",
            arrayOf(wordsString)
        )
    }

    fun count(exported: Boolean? = null, activeMode: Boolean? = null): Int = synchronized(this) {
        val where = mutableListOf<String>()
        if (exported != null)
            where.add("$COLUMN_EXPORTED ${if (exported) "<>" else "="} 0")
        if (activeMode != null)
            where.add("$COLUMN_SOURCE_ACTIVE ${if (activeMode) "<>" else "="} 0")
        val whereString = if (where.isEmpty()) "" else "WHERE ${where.joinToString(" AND ")}"
        return db.readableDatabase.rawQuery("SELECT COUNT(1) FROM $TABLE $whereString", null).use {
            it.moveToFirst()
            it.getInt(0)
        }
    }

    fun isEmpty(): Boolean = synchronized(this) {
        db.readableDatabase.rawQuery("SELECT EXISTS (SELECT 1 FROM $TABLE)", null).use {
            it.moveToFirst()
            return it.getInt(0) == 0
        }
    }

    companion object {
        private const val TAG = "GestureDataDao"

        private const val TABLE = "GESTURE_DATA"
        private const val COLUMN_ID = "ID"
        private const val COLUMN_TIMESTAMP = "TIMESTAMP"
        private const val COLUMN_WORD = "WORD"
        private const val COLUMN_EXPORTED = "EXPORTED"
        private const val COLUMN_SOURCE_ACTIVE = "SOURCE_ACTIVE"
        private const val COLUMN_DATA = "DATA" // data is text, blob with zip is slower to store, and probably not worth the saved space

        const val CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS $TABLE (
                $COLUMN_ID INTEGER PRIMARY KEY,
                $COLUMN_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_WORD TEXT NOT NULL,
                $COLUMN_EXPORTED TINYINT NOT NULL DEFAULT 0,
                $COLUMN_SOURCE_ACTIVE TINYINT NOT NULL DEFAULT 0,
                $COLUMN_DATA TEXT
            )
        """

        private var instance: GestureDataDao? = null

        /** Returns the instance or creates a new one. Returns null if instance can't be created (e.g. no access to db due to device being locked) */
        fun getInstance(context: Context): GestureDataDao? {
            if (instance == null)
                try {
                    instance = GestureDataDao(Database.getInstance(context))
                } catch (e: Throwable) {
                    Log.e(TAG, "can't create ClipboardDao", e)
                }
            return instance
        }
    }
}
