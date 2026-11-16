package com.peterhull.shelf

object Config {
    // Set spotify.clientId in local.properties (see README).
    val CLIENT_ID: String = BuildConfig.SPOTIFY_CLIENT_ID
    const val REDIRECT_URI = "shelf://callback"
    const val SCOPES = "user-library-read playlist-read-private playlist-read-collaborative app-remote-control"
}
