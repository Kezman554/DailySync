package com.personalmorningalarm.data.model

import com.google.gson.annotations.SerializedName

/**
 * The dashboard's presence status: `{open, person, expires_in_s}`. Every
 * /api/presence route answers this shape. [expiresInSeconds] counts from the
 * moment the dashboard answered, so it only means something alongside when it
 * was read.
 */
data class PresenceStatusDto(
    @SerializedName("open") val open: Boolean = false,
    @SerializedName("person") val person: String? = null,
    @SerializedName("expires_in_s") val expiresInSeconds: Double? = null
)
