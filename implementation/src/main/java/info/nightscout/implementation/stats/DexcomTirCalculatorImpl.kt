package info.nightscout.implementation.stats

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.widget.TableLayout
import info.nightscout.androidaps.database.AppRepository
import info.nightscout.androidaps.interfaces.ProfileFunction
import info.nightscout.shared.interfaces.ResourceHelper
import info.nightscout.androidaps.interfaces.stats.DexcomTIR
import info.nightscout.androidaps.interfaces.stats.DexcomTirCalculator
import info.nightscout.shared.utils.DateUtil
import info.nightscout.interfaces.utils.MidnightTime
import info.nightscout.shared.utils.T
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DexcomTirCalculatorImpl @Inject constructor(
    private val rh: ResourceHelper,
    private val profileFunction: ProfileFunction,
    private val dateUtil: DateUtil,
    private val repository: AppRepository
) : DexcomTirCalculator {

    val days = 14L

    override fun calculate(): DexcomTIR {
        val startTime = MidnightTime.calc(dateUtil.now() - T.days(days).msecs())
        val endTime = MidnightTime.calc(dateUtil.now())

        val bgReadings = repository.compatGetBgReadingsDataFromTime(startTime, endTime, true).blockingGet()
        val result = DexcomTirImpl()
        for (bg in bgReadings) result.add(bg.timestamp, bg.value)
        return result
    }

    @SuppressLint("SetTextI18n")
    override fun stats(context: Context): TableLayout =
        TableLayout(context).also { layout ->
            val tir = calculate()
            layout.layoutParams = TableLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            layout.addView(tir.toRangeHeaderView(context, rh, profileFunction))
            layout.addView(tir.toTableRowHeader(context, rh))
            layout.addView(tir.toTableRow(context, rh))
            layout.addView(tir.toSDView(context, rh, profileFunction))
            layout.addView(tir.toHbA1cView(context, rh))
        }
}