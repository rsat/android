package org.owntracks.android.support

import org.owntracks.android.data.WaypointModel
import org.owntracks.android.model.FusedContact
import java.util.*

class Events {
    abstract class E internal constructor() {
        val date: Date = Date()
    }

    class ModeChanged(val newModeId: Int) : E()
    class MonitoringChanged : E()
    class EndpointChanged : E()
    class PermissionGranted(val permission: String) : E()
    class ServiceStarted : E()

    open class WaypointEvent internal constructor(val waypointModel: WaypointModel) : E()
    class WaypointAdded(m: WaypointModel) : WaypointEvent(m)
    class WaypointUpdated(m: WaypointModel) : WaypointEvent(m)
    class WaypointRemoved(m: WaypointModel) : WaypointEvent(m)
    class FusedContactAdded(val contact: FusedContact) : E()
    class FusedContactRemoved(val contact: FusedContact) : E()
    class RestartApp : E()

    class WelcomeNextDoneButtonsEnableToggle(val nextEnabled: Boolean = true, val doneEnabled: Boolean = false) : E()
}