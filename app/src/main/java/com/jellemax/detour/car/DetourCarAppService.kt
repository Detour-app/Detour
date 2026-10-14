package com.jellemax.detour.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.jellemax.detour.BuildConfig

/** Entry point Android Auto binds to. The service is exported, so a release
 *  build only accepts hosts signed with a key in the car library's own
 *  allowlist (Android Auto and the AAOS templates host); anything else posing
 *  as a car never gets the session (#690). Debug and automotive builds accept
 *  any host so a test host or an emulator image can bind them. */
class DetourCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if (BuildConfig.DEBUG) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = DetourCarSession()
}
