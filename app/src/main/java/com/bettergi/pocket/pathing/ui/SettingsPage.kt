package com.bettergi.pocket.pathing.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.widget.SwitchCompat
import com.bettergi.pocket.R
import com.bettergi.pocket.pathing.map.MapMatchingMethod
import com.bettergi.pocket.pathing.settings.*
import com.bettergi.pocket.ui.*
import java.math.BigDecimal

enum class CandidateSlot(val title: String, val element: String? = null) {
    Walker("شخصيات التنقل المرشحة"), ShortGuardian("الحماية · مهارة قصيرة"), LongGuardian("الحماية · مهارة مطولة"),
    Hydro("مرشحو جمع موارد الماء", "Hydro"), Electro("مرشحو جمع موارد الكهرباء", "Electro"),
    Anemo("مرشحو جمع موارد الرياح", "Anemo"), Pyro("مرشحو جمع موارد النار", "Pyro");
    fun names(config: PathingSettings, repo: PathingSettingsRepository): List<String> = when (this) {
        Walker -> config.mainAvatars; ShortGuardian -> config.guardianShortAvatars; LongGuardian -> config.guardianLongAvatars
        else -> config.elementAvatars[element] ?: repo.defaultElementCandidates(element!!)
    }
    fun defaults(repo: PathingSettingsRepository) = when (this) {
        Walker -> PathingSettings.DEFAULT_WALKERS; ShortGuardian -> PathingSettings.DEFAULT_SHORT_GUARDIANS
        LongGuardian -> PathingSettings.DEFAULT_LONG_GUARDIANS; else -> repo.defaultElementCandidates(element!!)
    }
    fun save(repo: PathingSettingsRepository, names: List<String>) = repo.update { when (this) {
        Walker -> it.copy(mainAvatars = names)
        ShortGuardian -> it.copy(guardianShortAvatars = names, guardianLongAvatars = it.guardianLongAvatars - names.toSet())
        LongGuardian -> it.copy(guardianLongAvatars = names, guardianShortAvatars = it.guardianShortAvatars - names.toSet())
        else -> it.copy(elementAvatars = it.elementAvatars + (element!! to names))
    } }
}

/** 执行和角色各自滚动；所有设置沿用原有存储键，即改即存。 */
class SettingsPage(c: Context, wide: Boolean, private val repo: PathingSettingsRepository,
                   private val pick: (CandidateSlot) -> Unit,
                   private val editNumber: (String, String, (String) -> String?) -> Unit) : LinearLayout(c) {
    private val candidateRows = LinkedHashMap<CandidateSlot, OverlayRow>()
    init {
        orientation = if (wide) HORIZONTAL else VERTICAL
        val execution = OverlayUi.column(c).apply { setPadding(dp(12), dp(4), dp(8), dp(12)) }
        val team = OverlayUi.column(c).apply { setPadding(dp(8), dp(4), dp(12), dp(12)) }
        execution.addView(section("التنفيذ"))
        val config = repo.get()
        val matching = OverlayRow(c, "مطابقة الخريطة", subtitle = "تُستخدم إذا لم يحدد المسار طريقة المطابقة")
        matching.trailing.addView(OverlayUi.segments(c, listOf("مطابقة القوالب", "SIFT"),
            if (config.mapMatchingMethod == MapMatchingMethod.SIFT) 1 else 0) { index ->
            repo.update { it.copy(mapMatchingMethod = if (index == 0) MapMatchingMethod.TemplateMatch else MapMatchingMethod.SIFT) }
        })
        val gadget = OverlayRow(c, "استخدام الأداة تلقائياً")
        val gadgetStepper = StepperView(c, 100, Int.MAX_VALUE.toLong(), 100, config.useGadgetIntervalMs.toLong(),
            { "${seconds(it)} s" }, { value -> repo.update { it.copy(useGadgetIntervalMs = value.toInt()) } }, { stepper ->
            editNumber("فاصل الأداة (ثوانٍ)", seconds(repo.get().useGadgetIntervalMs.toLong())) { value ->
                val ms = parseGadgetInterval(value)
                if (ms == null) "أدخل عدداً أكبر من صفر، حتى 3 منازل عشرية" else {
                    repo.update { it.copy(useGadgetIntervalMs = ms) }; stepper.setValue(ms.toLong()); null
                }
            }
        }, editorMinimum = 1)
        gadget.trailing.addView(gadgetStepper)
        gadget.trailing.addView(SwitchCompat(c, null, androidx.appcompat.R.attr.switchStyle).apply {
            setThumbTintList(androidx.core.content.ContextCompat.getColorStateList(c, R.color.overlay_switch_thumb))
            setTrackTintList(androidx.core.content.ContextCompat.getColorStateList(c, R.color.overlay_switch_track))
            contentDescription = "استخدام الأداة تلقائياً"; isChecked = config.useGadgetEnabled
            setOnCheckedChangeListener { _, enabled -> repo.update { it.copy(useGadgetEnabled = enabled) }; gadgetStepper.isEnabled = enabled }
            layoutParams = LayoutParams(-2, -2).apply { marginStart = dp(8) }
        })
        gadgetStepper.isEnabled = config.useGadgetEnabled
        val guardian = OverlayRow(c, "فاصل تجديد الدرع", subtitle = "تلقائي = حسب زمن تبريد مهارة الشخصية")
        guardian.trailing.addView(StepperView(c, 0, 3_600_000, 1000, (config.guardianIntervalSeconds * 1000).toLong(),
            { if (it == 0L) "تلقائي" else "${seconds(it)} s" }, { value -> repo.update { it.copy(guardianIntervalSeconds = value / 1000.0) } }, { stepper ->
            editNumber("فاصل تجديد الدرع (ثوانٍ، 0 للتلقائي)", repo.get().guardianIntervalSeconds.toString()) { text ->
                val value = text.toDoubleOrNull()
                if (value == null || !value.isFinite() || value != 0.0 && value !in 1.0..3600.0) "أدخل 0 أو من 1 إلى 3600 ثانية"
                else { repo.update { it.copy(guardianIntervalSeconds = value) }; stepper.setValue((value * 1000).toLong()); null }
            }
        }))
        execution.addView(OverlayUi.group(c, matching, gadget, guardian))
        team.addView(section("الفريق · فريق فردي من أربع شخصيات، لا تبدل يدوياً أثناء التنفيذ",
            "تُطابق الشخصيات حسب ترتيب الاختيار، وتُتخطى غير الموجودة في الفريق. تُفضّل مهارة الحماية القصيرة ثم المطولة."))
        team.addView(OverlayUi.group(c,
            candidate(CandidateSlot.Walker, "شخصية التنقل", R.drawable.ic_ui_directions_walk, R.id.pathing_setting_main_avatar),
            candidate(CandidateSlot.ShortGuardian, "الحماية · مهارة قصيرة", R.drawable.ic_ui_shield, R.id.pathing_setting_guardian_avatar),
            candidate(CandidateSlot.LongGuardian, "الحماية · مهارة مطولة", R.drawable.ic_ui_shield, R.id.pathing_setting_guardian_press)))
        team.addView(section("الجمع العنصري · عند وجود إجراء جمع مناسب في المسار", "افتراضياً تُستخدم جميع الشخصيات المؤهلة من العنصر، والمسح يعطل الوظيفة."))
        val elements = OverlayUi.column(c).apply { setBackgroundResource(R.drawable.bg_pocket_group) }
        listOf(listOf(CandidateSlot.Hydro, CandidateSlot.Electro), listOf(CandidateSlot.Anemo, CandidateSlot.Pyro)).forEach { slots ->
            elements.addView(OverlayUi.row(c).apply { slots.forEach { slot ->
                val label = mapOf(CandidateSlot.Hydro to "ماء", CandidateSlot.Electro to "كهرباء", CandidateSlot.Anemo to "رياح", CandidateSlot.Pyro to "نار").getValue(slot)
                val icon = mapOf(CandidateSlot.Hydro to R.drawable.ic_ui_water_drop, CandidateSlot.Electro to R.drawable.ic_ui_bolt,
                    CandidateSlot.Anemo to R.drawable.ic_ui_air, CandidateSlot.Pyro to R.drawable.ic_ui_local_fire_department).getValue(slot)
                val row = candidate(slot, label, icon)
                (row.getChildAt(0) as android.widget.ImageView).imageTintList = android.content.res.ColorStateList.valueOf(elementColor(c, slot.element))
                addView(row, LayoutParams(0, -2, 1f))
            } })
        }
        team.addView(elements)
        if (wide) {
            addView(OverlayUi.scroll(c, execution), LayoutParams(0, -1, 1f)); addView(OverlayUi.scroll(c, team), LayoutParams(0, -1, 1f))
        } else {
            addView(OverlayUi.scroll(c, OverlayUi.column(c).apply { addView(execution); addView(team) }), LayoutParams(-1, -1))
        }
        refresh()
    }
    private fun section(label: String, info: String? = null): View = OverlayUi.column(context).apply {
        val description = OverlayUi.text(context, info.orEmpty(), 11f, R.color.pocket_text_secondary).apply { visibility = GONE; setPadding(dp(4), 0, dp(4), dp(8)) }
        addView(OverlayUi.row(context).apply {
            minimumHeight = dp(28)
            addView(OverlayUi.text(context, label, 11f, R.color.pocket_text_secondary, true), LayoutParams(0, -2, 1f))
            if (info != null) addView(OverlayUi.iconButton(context, R.drawable.ic_ui_info, "شرح") { description.visibility = if (description.visibility == GONE) VISIBLE else GONE })
        }); addView(description)
    }
    private fun candidate(slot: CandidateSlot, label: String, icon: Int, rowId: Int = View.NO_ID): OverlayRow {
        val row = OverlayRow(context, label, icon, dense = true).apply { id = rowId }
        val value = OverlayUi.text(context, "", 13f).apply { tag = "value"; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        // 标签列等宽，值列从相同的位置开始；元素项只显示计数。
        if (slot.element == null) {
            row.titleView.layoutParams = LayoutParams(dp((76 * resources.configuration.fontScale).toInt()), -2)
            row.titleView.maxLines = 1
            (row.titleView.parent as LinearLayout).apply { orientation = HORIZONTAL; addView(value, LayoutParams(0, -2, 1f)) }
        }
        row.trailing.addView(OverlayUi.text(context, "", 12f, R.color.pocket_primary).apply { tag = "count" })
        row.trailing.addView(OverlayUi.icon(context, R.drawable.ic_ui_chevron_right), LayoutParams(dp(16), dp(16)))
        if (slot.element != null) {
            row.setPadding(dp(8), dp(4), dp(8), dp(4))
            (row.getChildAt(0).layoutParams as LayoutParams).apply { width = dp(16); height = dp(16); marginEnd = dp(8) }
            (row.trailing.layoutParams as LayoutParams).marginStart = dp(4)
            row.titleView.apply { maxLines = 1; minWidth = dp(20) }
            row.findViewWithTag<android.widget.TextView>("count").apply { textSize = 11f; maxLines = 1 }
            row.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                val names = slot.names(repo.get(), repo)
                row.findViewWithTag<android.widget.TextView>("count").text = elementCount(slot, names, row.width)
            }
        }
        row.setOnClickListener { pick(slot) }; candidateRows[slot] = row; return row
    }
    private fun elementCount(slot: CandidateSlot, names: List<String>, width: Int): String = when {
        names.isEmpty() -> "معطل"
        width > 0 && width < dp((180 * resources.configuration.fontScale).toInt()) -> "${names.size} شخصيات"
        names == slot.defaults(repo) -> "افتراضي: ${names.size} شخصيات"
        else -> "${names.size} شخصيات"
    }
    fun refresh() {
        val config = repo.get()
        candidateRows.forEach { (slot, row) ->
            val names = slot.names(config, repo)
            row.findViewWithTag<android.widget.TextView>("value")?.text = names.joinToString("、").ifEmpty { "معطل" }
            row.findViewWithTag<android.widget.TextView>("count").text = when {
                names.isEmpty() -> "معطل"; slot.element == null -> names.size.toString()
                else -> elementCount(slot, names, row.width)
            }
            row.alpha = if (names.isEmpty()) .5f else 1f
            androidx.appcompat.widget.TooltipCompat.setTooltipText(row,
                "${slot.title}：${if (names.isEmpty()) "معطل" else names.joinToString("、")}")
        }
    }
    companion object {
        fun seconds(ms: Long): String = BigDecimal.valueOf(ms, 3).stripTrailingZeros().toPlainString()
        fun parseGadgetInterval(text: String): Int? = try {
            BigDecimal(text).movePointRight(3).intValueExact().takeIf { it > 0 }
        } catch (_: NumberFormatException) { null } catch (_: ArithmeticException) { null }
    }
}

fun elementColor(c: Context, element: String?): Int = androidx.core.content.ContextCompat.getColor(c, when (element) {
    "Pyro" -> R.color.pocket_pyro; "Hydro" -> R.color.pocket_hydro; "Anemo" -> R.color.pocket_anemo
    "Electro" -> R.color.pocket_electro; "Cryo" -> R.color.pocket_cryo; "Geo" -> R.color.pocket_geo
    "Dendro" -> R.color.pocket_dendro; else -> R.color.pocket_text_secondary
})

fun elementLabel(element: String?): String = mapOf("Pyro" to "نار", "Hydro" to "ماء", "Anemo" to "رياح",
    "Electro" to "كهرباء", "Cryo" to "جليد", "Geo" to "صخر", "Dendro" to "طبيعة")[element] ?: "بلا عنصر"
