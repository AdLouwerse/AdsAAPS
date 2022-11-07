package info.nightscout.androidaps.plugins.general.actions

import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.R
import info.nightscout.interfaces.Config
import info.nightscout.androidaps.interfaces.PluginBase
import info.nightscout.interfaces.PluginDescription
import info.nightscout.interfaces.PluginType
import info.nightscout.androidaps.interfaces.ResourceHelper
import info.nightscout.rx.logging.AAPSLogger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActionsPlugin @Inject constructor(
    injector: HasAndroidInjector,
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    config: Config
) : PluginBase(
    PluginDescription()
    .mainType(PluginType.GENERAL)
    .fragmentClass(ActionsFragment::class.qualifiedName)
    .enableByDefault(config.APS || config.PUMPCONTROL)
    .visibleByDefault(config.APS || config.PUMPCONTROL)
    .pluginIcon(R.drawable.ic_action)
    .pluginName(R.string.actions)
    .shortName(R.string.actions_shortname)
    .description(R.string.description_actions),
    aapsLogger, rh, injector
)
