package info.nightscout.androidaps.activities

import android.content.Context
import android.os.Bundle
import info.nightscout.androidaps.core.R
import info.nightscout.androidaps.events.EventPreferenceChange
import info.nightscout.androidaps.events.EventThemeSwitch
import info.nightscout.androidaps.plugins.bus.RxBus
import info.nightscout.androidaps.utils.FabricPrivacy
import info.nightscout.androidaps.utils.locale.LocaleHelper
import info.nightscout.androidaps.utils.rx.AapsSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.plusAssign
import javax.inject.Inject


open class NoSplashAppCompatActivity : DaggerAppCompatActivityWithResult() {

    @Inject lateinit var rxBus: RxBus

    private val compositeDisposable = CompositeDisposable()

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.AppTheme_NoActionBar)

        compositeDisposable.add(rxBus.toObservable(EventThemeSwitch::class.java).subscribe {
            theme.applyStyle(R.style.CustomTheme,true)
            recreate()
        })

    }

    override fun onDestroy() {
        compositeDisposable.clear()
        super.onDestroy()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }
}