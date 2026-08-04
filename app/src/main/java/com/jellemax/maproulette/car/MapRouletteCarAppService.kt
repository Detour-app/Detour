package com.jellemax.maproulette.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/** Entry point Android Auto binds to. Sideloaded/personal use only (see
 *  FUTURE.md) — accepts any host rather than checking a signed allowlist. */
class MapRouletteCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = MapRouletteCarSession()
}
