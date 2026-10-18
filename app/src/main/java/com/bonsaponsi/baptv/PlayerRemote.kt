package com.bonsaponsi.baptv

/**
 * Player hooks for the web server (remote tests without the remote):
 * HbbTV browser, timeshift and recordings. Set by [MainActivity]; keys go through `App.resumed`.
 */
object PlayerRemote {
    @Volatile var hbb: HbbTvBrowser? = null
    /** Closes the tuner session before installing an update (main thread). */
    @Volatile var releaseTuner: (() -> Unit)? = null
    /** dtvinput timeshift experiments (action: tune|pause|resume|seek|speed|live, arg: value). */
    @Volatile var timeshift: ((String, String?) -> String)? = null
    @Volatile var timeshiftState: (() -> org.json.JSONObject)? = null
    /** Recordings (action: start|stop|play|live, arg: id of the recording to play). */
    @Volatile var recording: ((String, String?) -> String)? = null
    /** Publishing identified channels to TvContract ([ChannelPublisher]); returns a status line. */
    @Volatile var publish: (() -> String)? = null
}
