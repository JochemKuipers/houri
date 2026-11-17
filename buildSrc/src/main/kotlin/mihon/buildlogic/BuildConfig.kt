package mihon.buildlogic

import org.gradle.api.Project

interface BuildConfig {
    val includeTelemetry: Boolean
    val enableUpdater: Boolean
    val enableCodeShrink: Boolean
    val includeDependencyInfo: Boolean
    val malClientId: String
    val googleDriveRedirect: String
}

val Project.Config: BuildConfig get() = object : BuildConfig {
    override val includeTelemetry: Boolean = project.hasProperty("include-telemetry")
    override val enableUpdater: Boolean = project.hasProperty("enable-updater")
    override val enableCodeShrink: Boolean = !project.hasProperty("disable-code-shrink")
    override val includeDependencyInfo: Boolean = project.hasProperty("include-dependency-info")
    // KMK --> A fork that registers its own MyAnimeList application passes its client id here; the
    // bundled one is the upstream Mihon/Komikku client, whose redirect scheme is not necessarily
    // one this app answers (issue #72).
    override val malClientId: String = (project.findProperty("malClientId") as String?)
        ?.takeIf { it.isNotBlank() }
        ?: "2be14959235191ece14eebdc2eea0466"
    // A fork whose own Google OAuth client is registered for its application id passes the matching
    // redirect; the default is the one the inherited client (and the manifest's legacy scheme)
    // already uses, so an existing secrets file keeps working unchanged (issue #73).
    override val googleDriveRedirect: String = (project.findProperty("googleDriveRedirect") as String?)
        ?.takeIf { it.isNotBlank() }
        ?: "eu.kanade.google.oauth:/oauth2redirect"
    // KMK <--
}
