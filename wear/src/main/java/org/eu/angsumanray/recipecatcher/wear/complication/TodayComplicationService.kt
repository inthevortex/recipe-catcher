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
import org.eu.angsumanray.recipecatcher.wear.ui.MainActivity

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
        val repository = TodayRepository.getInstance(applicationContext)
        val meals = repository.getMeals()
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
                val text = if (meals.isEmpty()) {
                    "—"
                } else if (remaining.isEmpty()) {
                    "Done"
                } else {
                    "${remaining.size} left"
                }
                ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder(text).build(),
                    contentDescription = PlainComplicationText.Builder("Meals remaining: $text").build()
                )
                    .setTitle(PlainComplicationText.Builder("Meals").build())
                    .setTapAction(tapIntent)
                    .build()
            }

            ComplicationType.LONG_TEXT -> {
                val text = if (meals.isEmpty()) {
                    "No meals planned"
                } else if (remaining.isEmpty()) {
                    "All meals done"
                } else {
                    val timePrefix = nextMeal?.time?.let { "$it • " } ?: ""
                    "$timePrefix${nextMeal?.slot}: ${nextMeal?.title}"
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
}
