package info.nightscout.androidaps.activities

import android.content.Context
import android.os.Bundle
import dagger.android.support.DaggerAppCompatActivity
import info.nightscout.androidaps.core.R
import info.nightscout.androidaps.utils.locale.LocaleHelper
import info.nightscout.androidaps.utils.sharedPreferences.SP
import javax.inject.Inject

//@Suppress("registered")
open class NoSplashAppCompatActivity : DaggerAppCompatActivity() {
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.AppTheme)
    }

    public override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }
}
