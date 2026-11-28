package app.familygem.geni.api

import app.familygem.geni.auth.AuthResult
import app.familygem.geni.auth.TokenStore
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/** A photo upload is slow on a bad connection: its POST waits longer for the answer. */
private const val PHOTO_READ_TIMEOUT_SECONDS = 60L

/** The user must log in to Geni (again). */
class GeniAuthRequiredException : Exception("Geni login required")

/** The signed-in Geni account is not the one that confirmed the request: nothing was sent. */
class GeniAccountMismatchException : Exception("signed in to another Geni account")

/** The request has no recorded confirming account (an older job): it needs a fresh confirmation, nothing was sent. */
class GeniNotConfirmedException : Exception("the request was not confirmed by a Geni account")

/** A check made right before dispatch said the request must not go out: nothing was sent. */
class GeniSendRejectedException : Exception("the request was not sent: its target could not be verified")

/** The login changed while a request was prepared: nothing was sent, the next attempt verifies the account again. */
class GeniSessionChangedException : Exception("the Geni login changed")

/**
 * What the user may do with Geni profiles, read under one login: the signed-in profile and the guids it manages.
 * [accountId] is the signed-in user's own profile ID, the same after every login of that account.
 */
class DeleteAuthorization internal constructor(val accountId: String, val managedGuids: Set<String>, internal val session: AuthResult.Success)

/** Geni answered with an error. */
class GeniApiException(val code: Int, message: String) : Exception(message)

/**
 * Client of the Geni API.
 * @param baseUrl API base, like https://www.geni.com/api/
 * @param now Current time in milliseconds, to check token expiry
 */
/** Profile fields requested for mapping. */
const val PROFILE_FIELDS = "id,guid,first_name,middle_name,last_name,maiden_name,suffix,display_name,title,name,gender,is_alive," +
        "occupation,about_me,cause_of_death,nicknames,birth,death,baptism,burial,updated_at,created_at,deleted,merged_into,mugshot_urls," +
        "public,locked,merge_note,names," +
        // Read-only details for the card (ProfileDetails); `events` must be requested, `curator` is only returned as `is_curator` on the sandbox
        "email,phone_numbers,current_residence,events,managers,claimed,curator,is_curator,big_tree,merge_pending,relationship,profile_url"

/** IDs per batch request. */
private const val BATCH_SIZE = 50

class GeniClient(
    private val baseUrl: HttpUrl,
    private val tokens: TokenStore,
    private val limiter: RateLimiter,
    // No silent retries: OkHttp could repeat a POST that already created a profile on Geni
    private val http: OkHttpClient = OkHttpClient.Builder().retryOnConnectionFailure(false)
        // A redirect could carry the token or a form body to another origin: Geni's API does not redirect, so none is followed
        .followRedirects(false).followSslRedirects(false).build(),
    private val now: () -> Long = System::currentTimeMillis
) {
    private val gson = Gson()

    /** Profile of the logged-in user. */
    suspend fun currentProfile(): GeniProfile = profileOf(null)

    private suspend fun profileOf(session: AuthResult.Success?): GeniProfile =
        gson.fromJson(execute(Request.Builder().url(apiUrl("profile")), session = session), GeniProfile::class.java)

    /**
     * Profile of the account that owns a login that is not stored yet, read with that login alone.
     * Nothing is stored, and a 401 does not clear the stored login.
     */
    suspend fun profileForLogin(candidate: AuthResult.Success): GeniProfile {
        if (candidate.expiresAtMillis <= now()) throw GeniAuthRequiredException()
        return gson.fromJson(execute(Request.Builder().url(apiUrl("profile")), candidate = candidate), GeniProfile::class.java)
    }

    /** The valid login now; the requests of one decision use it alone, so they cannot slip to another account. */
    private fun session(): AuthResult.Success = tokens.get()?.takeIf { it.expiresAtMillis > now() } ?: throw GeniAuthRequiredException()

    /**
     * The signed-in account and its managed guids, both read with the same login, for the "Delete on Geni" confirmation.
     * Pass it to [isCurrent] when the user confirms, and queue the delete with its [DeleteAuthorization.accountId].
     */
    suspend fun deleteAuthorization(): DeleteAuthorization {
        val session = session()
        return DeleteAuthorization(profileOf(session).id, managedGuids(session), session)
    }

    /** True while the login that produced [authorization] is still the stored one. */
    fun isCurrent(authorization: DeleteAuthorization): Boolean = isCurrent(authorization.session)

    private fun isCurrent(session: AuthResult.Success): Boolean = tokens.get() == session && session.expiresAtMillis > now()

    /**
     * Guids of the profiles the user manages (and so may delete). Geni lists them by numeric guid, the same as
     * the `guid` field of a profile, not by `profile-N` ID. Pages are read until one is empty.
     */
    suspend fun managedGuids(): Set<String> = managedGuids(null)

    private suspend fun managedGuids(session: AuthResult.Success?): Set<String> {
        val guids = mutableSetOf<String>()
        var page = 1
        while (true) {
            val results = JsonParser.parseString(execute(Request.Builder().url(apiUrl("user/managed-profiles?fields=guid&page=$page")), session = session)).asJsonObject
                .getAsJsonArray("results")
            if (results == null || results.size() == 0) return guids
            // A page that adds nothing new means the server ignores `page`: stop instead of looping
            var added = false
            results.forEach { it.asJsonObject.get("guid")?.asString?.let { guid -> if (guids.add(guid)) added = true } }
            if (!added) return guids
            page++
        }
    }

    /** IDs in the immediate family of a profile. */
    suspend fun immediateFamily(profileId: String): ImmediateFamily = ImmediateFamily.parse(getJson("$profileId/immediate-family"))

    /**
     * Profiles with the mapped fields, each with its raw JSON, by Geni ID. Geni leaves `names` out while a profile has no name in
     * another language, so the raw JSON gets an empty one: a stored version without `names` is then one from before they were read.
     */
    suspend fun profiles(ids: List<String>): Map<String, Pair<GeniProfile, String>> =
        batch(ids, GeniProfile::class.java, { it.id }) { "profile?ids=$it&fields=$PROFILE_FIELDS" }
            .mapValues { (_, value) -> withNames(value.first, value.second) }

    /** The profile and its raw JSON with an empty `names` in both when Geni left it out; a profile Geni did send `names` for stays as it is. */
    private fun withNames(profile: GeniProfile, raw: String): Pair<GeniProfile, String> = runCatching {
        val root = JsonParser.parseString(raw).asJsonObject
        if (root.has("names")) profile to raw else profile.copy(names = JsonObject()) to root.also { it.add("names", JsonObject()) }.toString()
    }.getOrDefault(profile to raw)

    /** Unions, each with its raw JSON, by Geni ID. */
    suspend fun unions(ids: List<String>): Map<String, Pair<GeniUnion, String>> =
        batch(ids, GeniUnion::class.java, { it.id }) { "union?ids=$it" }

    /** Requests the IDs in chunks of [BATCH_SIZE] and parses each result. */
    private suspend fun <T> batch(ids: List<String>, type: Class<T>, idOf: (T) -> String, path: (String) -> String): Map<String, Pair<T, String>> {
        val result = linkedMapOf<String, Pair<T, String>>()
        for (chunk in ids.distinct().chunked(BATCH_SIZE)) {
            for (raw in rawResults(getJson(path(chunk.joinToString(","))))) {
                val item = gson.fromJson(raw, type)
                result[idOf(item)] = item to raw
            }
        }
        return result
    }

    /** All photos of a profile; pages are read until one is empty or adds nothing new. */
    suspend fun photos(profileId: String): List<GeniPhoto> {
        val photos = linkedMapOf<String, GeniPhoto>()
        var page = 1
        while (true) {
            val batch = parsePhotos(getJson("$profileId/photos?page=$page"))
            if (batch.isEmpty()) break
            var added = false
            batch.forEach { if (photos.put(it.id, it) == null) added = true }
            if (!added) break
            page++
        }
        return photos.values.toList()
    }

    /** A single photo, by ID like "photo-2175". */
    suspend fun photo(photoId: String): GeniPhoto = parsePhoto(getJson(photoId))

    /** Uploads a photo to the profile; the first photo of a profile becomes its main one on Geni. */
    suspend fun addPhoto(profileId: String, fileName: String, title: String?, bytes: ByteArray,
        beforeSend: (suspend () -> Boolean)? = null): GeniPhoto {
        // Base64 of a large photo is CPU and memory heavy: keep it off the caller's (maybe Main) thread
        val encoded = withContext(Dispatchers.IO) { bytes.toByteString().base64() }
        val params = mutableListOf("file" to encoded, "file_name" to fileName)
        if (title != null) params.add("title" to title)
        return parsePhoto(post("$profileId/add-photo", params, PHOTO_READ_TIMEOUT_SECONDS, beforeSend))
    }

    /**
     * Changes title, description or date of a photo ([params] from `GeniParams.photo`: only what changed, an empty value clears)
     * and returns the photo as Geni stores it now. Never called without parameters.
     */
    suspend fun updatePhoto(photoId: String, params: List<Pair<String, String>>): GeniPhoto = parsePhoto(post("$photoId/update", params))

    /** Tags [profileId] in the photo, so it shows in that profile's photos. Repeating it is safe. */
    suspend fun tagPhoto(photoId: String, profileId: String) {
        post("$photoId/tag/$profileId", emptyList())
    }

    /** Removes [profileId] from the photo's tags; the photo stays on Geni. Repeating it is safe. */
    suspend fun untagPhoto(photoId: String, profileId: String) {
        post("$photoId/untag/$profileId", emptyList())
    }

    /** Makes [photoId] the main photo of [profileId] (it also tags the profile in the photo). */
    suspend fun setMainPhoto(profileId: String, photoId: String) {
        post("$profileId/add-mugshot", listOf("photo_id" to photoId))
    }

    /** Deletes a photo from Geni. */
    suspend fun deletePhoto(photoId: String) {
        post("$photoId/delete", emptyList())
    }

    /**
     * Deletes a profile on Geni, only under the account that confirmed it ([confirmedBy], a profile ID; null for none).
     * The account is read and the POST sent with one captured login, rechecked right before every dispatch:
     * another login in between sends nothing. Throws [GeniNotConfirmedException], [GeniAccountMismatchException]
     * or [GeniSessionChangedException] before anything is sent.
     */
    suspend fun deleteProfile(profileId: String, confirmedBy: String?): String {
        if (confirmedBy == null) throw GeniNotConfirmedException()
        val session = session()
        if (profileOf(session).id != confirmedBy) throw GeniAccountMismatchException()
        val form = FormBody.Builder().build()
        return execute(Request.Builder().url(apiUrl("$profileId/delete")).post(form), session = session)
    }

    /** Raw JSON response of a GET request to an API path relative to the base, like "profile-1/immediate-family". */
    suspend fun getJson(path: String): String = execute(Request.Builder().url(apiUrl(path)))

    /** The URL of a path relative to the API; any other origin is refused, so the token never leaves Geni. */
    private fun apiUrl(path: String): HttpUrl {
        val url = baseUrl.resolve(path)
        require(url != null && url.scheme == baseUrl.scheme && url.host == baseUrl.host && url.port == baseUrl.port) {
            "request outside the Geni API"
        }
        return url
    }

    /**
     * Raw JSON response of a form POST to an API path relative to the base, like "profile-1/update".
     * Repeated keys are allowed; an empty value clears the field on Geni.
     */
    suspend fun post(path: String, params: List<Pair<String, String>>, readTimeoutSeconds: Long? = null,
        beforeSend: (suspend () -> Boolean)? = null): String {
        // add() canonicalizes every value, which is slow for a big base64 photo: keep it off the caller's thread
        val form = withContext(Dispatchers.IO) {
            FormBody.Builder().apply { params.forEach { (key, value) -> add(key, value) } }.build()
        }
        return execute(Request.Builder().url(apiUrl(path)).post(form), readTimeoutSeconds, beforeSend = beforeSend)
    }

    /** Sends the request with the token (a pinned [session] only while it is current); 401 clears that token, 429 is retried once after the rate window. */
    private suspend fun execute(builder: Request.Builder, readTimeoutSeconds: Long? = null, session: AuthResult.Success? = null,
        beforeSend: (suspend () -> Boolean)? = null, candidate: AuthResult.Success? = null): String {
        val token = candidate ?: session ?: session()
        val request = builder.header("Authorization", "Bearer ${token.token}").build()
        var retried = false
        while (true) {
            limiter.acquire()
            // The limiter may have waited: a pinned request goes out only while its login is still the stored one
            if (session != null && !isCurrent(session)) throw GeniSessionChangedException()
            // Before every attempt, the replay after a 429 too: the world may have changed while the request waited
            if (beforeSend != null && !beforeSend()) throw GeniSendRejectedException()
            val (code, body) = withContext(Dispatchers.IO) {
                (if (readTimeoutSeconds == null) http else http.newBuilder().readTimeout(readTimeoutSeconds, TimeUnit.SECONDS).build())
                    .newCall(request).execute().use { response ->
                    updateLimiter(response)
                    response.code to response.body.string()
                }
            }
            when {
                code in 200..299 -> return body
                code == 401 -> {
                    // A late 401 for an old login must not log out a newer one
                    if (tokens.get() == token) tokens.clear()
                    throw GeniAuthRequiredException()
                }
                code == 429 && !retried -> {
                    retried = true
                    limiter.update(limit = null, remaining = 0, windowSeconds = null)
                }
                else -> throw GeniApiException(code, body)
            }
        }
    }

    private fun updateLimiter(response: Response) = limiter.update(
        limit = response.header("X-API-Rate-Limit")?.toIntOrNull(),
        remaining = response.header("X-API-Rate-Remaining")?.toIntOrNull(),
        windowSeconds = response.header("X-API-Rate-Window")?.toIntOrNull()
    )
}
