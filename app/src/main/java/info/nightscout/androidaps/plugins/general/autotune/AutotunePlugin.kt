package info.nightscout.androidaps.plugins.general.autotune

import android.content.Context
import android.view.View
import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.Constants
import info.nightscout.androidaps.R
import info.nightscout.androidaps.data.LocalInsulin
import info.nightscout.androidaps.database.entities.UserEntry
import info.nightscout.androidaps.database.entities.ValueWithUnit
//import info.nightscout.androidaps.data.Profile
import info.nightscout.androidaps.interfaces.*
import info.nightscout.androidaps.logging.UserEntryLogger
import info.nightscout.shared.logging.AAPSLogger
import info.nightscout.shared.logging.LTag
import info.nightscout.androidaps.plugins.bus.RxBus
import info.nightscout.androidaps.plugins.general.autotune.data.ATProfile
import info.nightscout.androidaps.plugins.general.autotune.data.PreppedGlucose
import info.nightscout.androidaps.plugins.general.autotune.events.EventAutotuneUpdateResult
import info.nightscout.androidaps.plugins.profile.local.events.EventLocalProfileChanged
import info.nightscout.androidaps.utils.DateUtil
import info.nightscout.androidaps.utils.MidnightTime
import info.nightscout.androidaps.utils.T
import info.nightscout.androidaps.utils.resources.ResourceHelper
import info.nightscout.shared.sharedPreferences.SP
import org.json.JSONException
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * adaptation from oref0 autotune started by philoul on 2020 (complete refactoring of AutotunePlugin compare to Rumen Georgiev work done in 2018)
 *
 * TODO: detail analysis of iob calculation to understand (and correct if necessary) differences between oaps calculation and aaps calculation
 * => Today AutotuneCore is full consistent between oaps and aaps module (same results up to 3 digits)
 * => only gap is in iob calculation, that's why today results even if close, are not exactly the same :-(
 * TODO: build data sets for autotune validation
 * => I work on a new BGsimulatorplugin, it uses a dedicated "reference profile" for BG calculation,
 *      the idea is to use this reference profile to simulate a real person (if different of profile used for loop, it's the optimum result of a perfect autotune algorythm...)
 * => I hope we will be able to validate autotunePlugin with several data set (simulation of several situations)
 * TODO: Add Constraints for auto Switch (in Objective 11 ?) for safety => see with Milos once autotuneplugin validated
 * => for complete beginners only show results, then add ability to copy to local profile (Obj x?) , then add ability to switch from autotune results (Obj y?), then ability to use autotune from automation...
 * TODO: Improve layout (see ProfileViewerDialog and HtmlHelper.fromHtml() function)
 *      use html table for results presentation
 * TODO: futur version: add profile selector in AutotuneFragment to allow running autotune plugin with other profiles than current
 * TODO: futur version (once first version validated): add DIA and Peak tune for insulin
 */

@Singleton
class AutotunePlugin @Inject constructor(
    injector: HasAndroidInjector,
    resourceHelper: ResourceHelper,
    private val context: Context,
    private val sp: SP,
    private val rxBus: RxBus,
    private val profileFunction: ProfileFunction,
    private val dateUtil: DateUtil,
    private val activePlugin: ActivePlugin,
    private val uel: UserEntryLogger,
    aapsLogger: AAPSLogger
) : PluginBase(PluginDescription()
    .mainType(PluginType.GENERAL)
    .fragmentClass(AutotuneFragment::class.qualifiedName)
    .pluginIcon(R.drawable.ic_autotune)
    .pluginName(R.string.autotune)
    .shortName(R.string.autotune_shortname)
    .preferencesId(R.xml.pref_autotune)
    .description(R.string.autotune_description),
    aapsLogger, resourceHelper, injector
), Autotune {
    override var result: String = ""
    override var calculationRunning: Boolean = false
    override var lastRun: Long = 0
    override var lastNbDays: String = ""
    override var copyButtonVisibility: Int = 0
    override var profileSwitchButtonVisibility: Int = 0
    override var lastRunSuccess: Boolean = false
    private var logString = ""
    private var preppedGlucose: PreppedGlucose? = null
    private lateinit var autotunePrep: AutotunePrep
    private lateinit var autotuneCore: AutotuneCore
    private lateinit var autotuneIob: AutotuneIob
    private lateinit var autotuneFS: AutotuneFS

    override var currentprofile: ATProfile? = null
    override var tunedProfile: ATProfile? = null

    //    @Override
    val fragmentClass: String
        get() = AutotuneFragment::class.java.name

    //Launch Autotune with default settings
    override fun aapsAutotune() {
        val daysBack = sp.getInt(R.string.key_autotune_default_tune_days, 5)
        val autoSwitch = sp.getBoolean(R.string.key_autotune_auto, false)
        Thread(Runnable {
            aapsAutotune(daysBack, autoSwitch)
        }).start()
    }

    override fun aapsAutotune(daysBack: Int, autoSwitch: Boolean): String {
        autotuneFS = AutotuneFS(injector)
        autotunePrep = AutotunePrep(injector)
        autotuneCore = AutotuneCore(injector)
        autotuneIob = AutotuneIob(injector)
        profileSwitchButtonVisibility = View.GONE
        copyButtonVisibility = View.GONE
        lastRunSuccess = false
        calculationRunning = true
        currentprofile = null
        result = ""
        lastNbDays = "" + daysBack
        val now = System.currentTimeMillis()
        profile = profileFunction.getProfile(now)
        if (profile == null ) {
            result = "Cannot tune a null profile"
            atLog(result)
            calculationRunning = false
            Thread(Runnable {
                rxBus.send(EventAutotuneUpdateResult(result))
            }).start()
            tunedProfile=null
            return result
        }
        var localInsulin = LocalInsulin("PumpInsulin", activePlugin.activeInsulin.peak, profile!!.dia) // var because localInsulin could be updated later with Tune
        // Insulin peak/dia
        lastRun = System.currentTimeMillis()

        atLog("Start Autotune with $daysBack days back")
        //create autotune subfolder for autotune files if not exists
        autotuneFS.createAutotuneFolder()
        logString = ""
        //clean autotune folder before run
        autotuneFS.deleteAutotuneFiles()
        // Today at 4 AM
        var endTime = MidnightTime.calc(now) + autotuneStartHour * 60 * 60 * 1000L
        // Check if 4 AM is before now
        if (endTime > now) endTime -= 24 * 60 * 60 * 1000L
        val starttime = endTime - daysBack * 24 * 60 * 60 * 1000L
        autotuneFS.exportSettings(settings(lastRun, daysBack, starttime, endTime))
        tunedProfile = ATProfile(profile, localInsulin, injector)
        tunedProfile?.profilename = rh.gs(R.string.autotune_tunedprofile_name)
        val pumpprofile = ATProfile(profile, localInsulin, injector)
        pumpprofile.profilename = profileFunction.getProfileName()
        autotuneFS.exportPumpProfile(pumpprofile)
        if (daysBack < 1) {
            //Not necessary today (test is done in fragment, but left if other way later to launch autotune (i.e. with automation)
            result = rh.gs(R.string.autotune_min_days)
            atLog(result)
            calculationRunning = false
            Thread(Runnable {
                rxBus.send(EventAutotuneUpdateResult(result))
            }).start()
            tunedProfile=null
            return result
        } else {
            for (i in 0 until daysBack) {
                // get 24 hours BG values from 4 AM to 4 AM next day
                val from = starttime + i * 24 * 60 * 60 * 1000L
                val to = from + 24 * 60 * 60 * 1000L
                atLog("Tune day " + (i + 1) + " of " + daysBack)

                //autotuneIob contains BG and Treatments data from history (<=> query for ns-treatments and ns-entries)
                autotuneIob.initializeData(from, to)
               //<=> ns-entries.yyyymmdd.json files exported for results compare with oref0 autotune on virtual machine
                autotuneFS.exportEntries(autotuneIob)
                //<=> ns-treatments.yyyymmdd.json files exported for results compare with oref0 autotune on virtual machine (include treatments ,tempBasal and extended
                autotuneFS.exportTreatments(autotuneIob)
                preppedGlucose = autotunePrep.categorizeBGDatums(autotuneIob, tunedProfile!!, pumpprofile, localInsulin)
                //<=> autotune.yyyymmdd.json files exported for results compare with oref0 autotune on virtual machine
                if (preppedGlucose == null) {
                    result = rh.gs(R.string.autotune_error)
                    atLog(result)
                    calculationRunning = false
                    Thread(Runnable {
                        rxBus.send(EventAutotuneUpdateResult(result))
                    }).start()
                    tunedProfile=null
                    autotuneFS.exportResult(result)
                    autotuneFS.exportLogAndZip(lastRun, logString)
                    return result
                }
                autotuneFS.exportPreppedGlucose(preppedGlucose!!)
                tunedProfile = autotuneCore.tuneAllTheThings(preppedGlucose!!, tunedProfile!!, pumpprofile)
                // localInsulin = LocalInsulin("TunedInsulin", tunedProfile!!.peak, tunedProfile!!.dia)
                //<=> newprofile.yyyymmdd.json files exported for results compare with oref0 autotune on virtual machine
                autotuneFS.exportTunedProfile(tunedProfile!!)
                if (i < daysBack - 1) {
                    atLog("Partial result for day ${i + 1}".trimIndent())
                    Thread(Runnable {
                        result = rh.gs(R.string.format_autotune_partialresult, i + 1, daysBack, showResults(tunedProfile!!, pumpprofile))
                        rxBus.send(EventAutotuneUpdateResult(result))
                    }).start()
                }
            }
        }
        return if (tunedProfile!!.profile != null) {
            result = showResults(tunedProfile!!, pumpprofile)
            autotuneFS.exportResult(result)
            autotuneFS.exportLogAndZip(lastRun, logString)
            profileSwitchButtonVisibility = View.VISIBLE
            copyButtonVisibility = View.VISIBLE
            if (autoSwitch) {
                profileSwitchButtonVisibility = View.GONE //hide profilSwitch button in fragment
                //activePlugin.activeTreatments.doProfileSwitch(tunedProfile!!.profileStore, tunedProfile!!.profilename, 0, 100, 0, dateUtil.now())
                val now = dateUtil.now()
                if (profileFunction.createProfileSwitch(
                        tunedProfile!!.profileStore!!,
                        profileName = tunedProfile!!.profilename!!,
                        durationInMinutes = 0,
                        percentage = 100,
                        timeShiftInHours = 0,
                        timestamp = now
                    )
                ) {
                    uel.log(
                        UserEntry.Action.PROFILE_SWITCH,
                        UserEntry.Sources.ProfileSwitchDialog,
                        "Autotune AutoSwitch",
                        ValueWithUnit.SimpleString(tunedProfile!!.profilename!!))
                }
                rxBus.send(EventLocalProfileChanged())
            }
            lastRunSuccess = true
            Thread(Runnable {
                rxBus.send(EventAutotuneUpdateResult(result))
            }).start()
            calculationRunning = false
            currentprofile = pumpprofile
            result
        } else "No Result"
    }

    private fun showResults(tunedProfile: ATProfile, pumpProfile: ATProfile): String {
        var toMgDl = 1.0
        if (profileFunction.getUnits() == GlucoseUnit.MMOL) toMgDl = Constants.MMOLL_TO_MGDL
        val line = rh.gs(R.string.format_autotune_separator)
        var strResult = line
        strResult += rh.gs(R.string.format_autotune_title)
        strResult += line
        var totalBasal = 0.0
        var totalTuned = 0.0
        for (i in 0..23) {
            totalBasal += pumpProfile.basal[i]
            totalTuned += tunedProfile.basal[i]
            val percentageChangeValue = tunedProfile.basal[i] / pumpProfile.basal[i] * 100 - 100
            strResult += rh.gs(R.string.format_autotune_basal, i.toDouble(), pumpProfile.basal[i], tunedProfile.basal[i], tunedProfile.basalUntuned[i], percentageChangeValue)
        }
        strResult += line
        strResult += rh.gs(R.string.format_autotune_sum_basal, totalBasal, totalTuned)
        strResult += line
        // show ISF and CR
        strResult += rh.gs(R.string.format_autotune_isf, rh.gs(R.string.isf_short), pumpProfile.isf / toMgDl, tunedProfile.isf / toMgDl)
        strResult += line
        strResult += rh.gs(R.string.format_autotune_ic, rh.gs(R.string.ic_short), pumpProfile.ic, tunedProfile.ic)
        strResult += line
        atLog(strResult)
        return strResult
    }

    private fun settings(runDate: Long, nbDays: Int, firstloopstart: Long, lastloopend: Long): String {
        var jsonString = ""
        val jsonSettings = JSONObject()
        val insulinInterface = activePlugin.activeInsulin
        val utcOffset = T.msecs(TimeZone.getDefault().getOffset(dateUtil.now()).toLong()).hours()
        val startDateString = dateUtil.toISOString(firstloopstart).substring(0,10)
        val endDateString = dateUtil.toISOString(lastloopend - 24 * 60 * 60 * 1000L).substring(0,10)
        val nsUrl = sp.getString(R.string.key_nsclientinternal_url, "")
        val optCategorizeUam = if (sp.getBoolean(R.string.key_autotune_categorize_uam_as_basal, false)) "-c=true" else ""
        val optInsulinCurve = ""
        try {
            jsonSettings.put("datestring", dateUtil.toISOString(runDate))
            jsonSettings.put("dateutc", dateUtil.toISOAsUTC(runDate))
            jsonSettings.put("utcOffset", utcOffset)
            jsonSettings.put("units", profileFunction.getUnits().asText)
            jsonSettings.put("timezone", TimeZone.getDefault().id)
            jsonSettings.put("url_nightscout", sp.getString(R.string.key_nsclientinternal_url, ""))
            jsonSettings.put("nbdays", nbDays)
            jsonSettings.put("startdate", startDateString)
            jsonSettings.put("enddate", endDateString)
            // command to change timezone
            jsonSettings.put("timezone_command", "sudo ln -sf /usr/share/zoneinfo/" + TimeZone.getDefault().id + " /etc/localtime")
            // oref0_command is for running oref0-autotune on a virtual machine in a dedicated ~/aaps subfolder
            jsonSettings.put("oref0_command", "oref0-autotune -d=~/aaps -n=$nsUrl -s=$startDateString -e=$endDateString $optCategorizeUam $optInsulinCurve")
            // aaps_command is for running modified oref0-autotune with exported data from aaps (ns-entries and ns-treatment json files copied in ~/aaps/autotune folder and pumpprofile.json copied in ~/aaps/settings/
            jsonSettings.put("aaps_command", "aaps-autotune -d=~/aaps -s=$startDateString -e=$endDateString $optCategorizeUam $optInsulinCurve")
            jsonSettings.put("categorize_uam_as_basal", sp.getBoolean(R.string.key_autotune_categorize_uam_as_basal, false))
            jsonSettings.put("tune_insulin_curve", false)

            val peaktime: Int = insulinInterface.peak
            if (insulinInterface.id === Insulin.InsulinType.OREF_ULTRA_RAPID_ACTING)
                jsonSettings.put("curve","ultra-rapid")
            else if (insulinInterface.id === Insulin.InsulinType.OREF_RAPID_ACTING)
                jsonSettings.put("curve", "rapid-acting")
            else if (insulinInterface.id === Insulin.InsulinType.OREF_LYUMJEV) {
                jsonSettings.put("curve", "ultra-rapid")
                jsonSettings.put("useCustomPeakTime", true)
                jsonSettings.put("insulinPeakTime", peaktime)
            } else if (insulinInterface.id === Insulin.InsulinType.OREF_FREE_PEAK) {
                jsonSettings.put("curve", if (peaktime > 55) "rapid-acting" else "ultra-rapid")
                jsonSettings.put("useCustomPeakTime", true)
                jsonSettings.put("insulinPeakTime", peaktime)
            }
            jsonString = jsonSettings.toString(4).replace("\\/", "/")
        } catch (e: JSONException) {
            log.error("Unhandled exception", e)
        }
        return jsonString
    }

    // end of autotune Plugin
    override fun atLog(message: String) {
        aapsLogger.debug(LTag.AUTOTUNE, message)
        log.debug(message) // for debugging to have log even if Autotune Log disabled
        logString += message + "\n" // for log file in autotune folder even if autotune log disable
    }

    companion object {
        private val log = LoggerFactory.getLogger(AutotunePlugin::class.java)
        //@JvmField var currentprofile: ATProfile? = null
        private var profile: Profile? = null
    }

}