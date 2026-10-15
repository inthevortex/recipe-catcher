package org.eu.angsumanray.recipecatcher.wear.complication

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import org.eu.angsumanray.recipecatcher.wear.data.TodayRepository
import org.eu.angsumanray.recipecatcher.wear.data.WearMealItem
import org.eu.angsumanray.recipecatcher.wear.ui.MainActivity
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

open class TodayComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder("2 left").build(),
                contentDescription = PlainComplicationText.Builder("2 meals left today").build()
            ).setTitle(PlainComplicationText.Builder("Meals").build()).build()

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = PlainComplicationText.Builder("Dinner: Pasta").build(),
                contentDescription = PlainComplicationText.Builder("Next meal is dinner: pasta").build()
            ).setTitle(PlainComplicationText.Builder("Next Meal").build()).build()

            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = 1f,
                min = 0f,
                max = 3f,
                contentDescription = PlainComplicationText.Builder("1 of 3 meals cooked").build()
            ).setText(PlainComplicationText.Builder("1/3").build()).build()

            else -> null
        }
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val meals = TodayRepository.getInstance(applicationContext).meals()
        val remaining = meals.filter { !it.done }
        val nextMeal = remaining.firstOrNull()

        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return when (request.complicationType) {
            ComplicationType.SHORT_TEXT -> {
                val text = when {
                    meals.isEmpty() -> "—"
                    remaining.isEmpty() -> "Done"
                    else -> "${remaining.size} left"
                }
                val description = when {
                    meals.isEmpty() -> "No meals planned today"
                    remaining.isEmpty() -> "All meals done today"
                    else -> "${remaining.size} meals left today"
                }
                ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder(text).build(),
                    contentDescription = PlainComplicationText.Builder(description).build()
                )
                    .setTitle(PlainComplicationText.Builder("Meals").build())
                    .setTapAction(tapIntent)
                    .build()
            }

            ComplicationType.LONG_TEXT -> {
                val text = when {
                    nextMeal != null -> nextItemText(nextMeal)
                    meals.isEmpty() -> "No meals planned"
                    else -> "All meals done"
                }
                LongTextComplicationData.Builder(
                    text = PlainComplicationText.Builder(text).build(),
                    contentDescription = PlainComplicationText.Builder(text).build()
                )
                    .setTitle(PlainComplicationText.Builder("Today").build())
                    .setTapAction(tapIntent)
                    .build()
            }

            ComplicationType.RANGED_VALUE -> {
                val total = meals.size.coerceAtLeast(1).toFloat()
                val doneCount = (meals.size - remaining.size).toFloat()
                RangedValueComplicationData.Builder(
                    value = doneCount,
                    min = 0f,
                    max = total,
                    contentDescription = PlainComplicationText.Builder("${doneCount.toInt()}/${total.toInt()} meals done").build()
                )
                    .setText(PlainComplicationText.Builder("${doneCount.toInt()}/${total.toInt()}").build())
                    .setTapAction(tapIntent)
                    .build()
            }

            else -> null
        }
    }

    companion object {
        /** "Prep oats, 9:00 pm" style: title first, then the slot's time when it has one. */
        fun nextItemText(item: WearMealItem): String {
            val title = if (item.kind == "prep" && !item.title.startsWith("Prep", ignoreCase = true)) "Prep ${item.title}" else item.title
            val time = item.time?.let { formatTime(it) }
            return if (time != null) "$title, $time" else "${item.slot}: $title"
        }

        private fun formatTime(isoTime: String): String? = try {
            LocalTime.parse(isoTime).format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
        } catch (_: Exception) {
            null
        }
    }
}
