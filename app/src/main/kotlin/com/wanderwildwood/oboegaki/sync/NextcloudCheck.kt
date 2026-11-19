package com.wanderwildwood.oboegaki.sync

import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Why a typed sign-in did not work, in the terms the reader can act on. */
enum class Problem { BAD_ADDRESS, PLAIN_HTTP, NOT_FOUND, UNREACHABLE, CERTIFICATE, NOT_NEXTCLOUD, WRONG_PASSWORD, OTHER }

sealed class CheckResult {
    /** The account to keep: the address as it was checked, the login name and the user id the server knows. */
    data class Good(val account: Account) : CheckResult()
    data class Bad(val problem: Problem, val detail: String = "") : CheckResult()
}

/**
 * The typed sign-in: a server address, a user name and an app password, asked of the server once
 * before anything is kept. Nothing depends on a browser or on what the server believes its own
 * address to be; the account is built from exactly what was typed (the address gets https://
 * added if missing), signing in with the login name as typed and building paths with the user
 * id the server reports for it.
 */
object NextcloudCheck {

    /**
     * Checks [server], [user] and [password]. [plainAllowed] says whether an http:// address may
     * be used at all; the default is never.
     */
    fun run(
        server: String,
        user: String,
        password: String,
        plainAllowed: (String) -> Boolean = { it.startsWith("https://", ignoreCase = true) },
        client: OkHttpClient = http,
    ): CheckResult {
        if (server.isBlank()) return CheckResult.Bad(Problem.BAD_ADDRESS)
        val base = LoginFlow.normalise(server)
        if (base.toHttpUrlOrNull() == null) return CheckResult.Bad(Problem.BAD_ADDRESS)
        if (!plainAllowed(base)) return CheckResult.Bad(Problem.PLAIN_HTTP)
        val login = user.trim()
        val secret = password.trim()
        val first = try {
            ask(client, "$base/ocs/v2.php/cloud/user?format=json", login, secret)
        } catch (e: IOException) {
            return CheckResult.Bad(problemFor(e), e.message.orEmpty())
        }
        val id = userId(first.code, first.body)
        if (id != null) return CheckResult.Good(Account(base, login, secret, uid = id))
        // Not let in. Any web server can say no, so ask whether this is a Nextcloud at all.
        val status = try {
            ask(client, "$base/status.php", null, null)
        } catch (e: IOException) {
            return CheckResult.Bad(problemFor(e), e.message.orEmpty())
        }
        if (!looksLikeNextcloud(status.code, status.body)) return CheckResult.Bad(Problem.NOT_NEXTCLOUD)
        return if (first.code == 401 || first.code == 403 || first.code == 997) {
            CheckResult.Bad(Problem.WRONG_PASSWORD)
        } else {
            CheckResult.Bad(Problem.OTHER, "${first.code}")
        }
    }

    /**
     * [account] (from Nextcloud's login page, which gives only the login name) with the user id
     * the server reports for it. As it was if the server could not be asked or gave no id.
     */
    fun withUserId(account: Account, client: OkHttpClient = http): Account {
        val answer = try {
            ask(client, account.server.trimEnd('/') + "/ocs/v2.php/cloud/user?format=json", account.user, account.password)
        } catch (e: IOException) {
            return account
        } catch (e: IllegalArgumentException) {
            return account
        }
        val id = userId(answer.code, answer.body) ?: return account
        return account.copy(uid = id)
    }

    internal class Answer(val code: Int, val body: String)

    private fun ask(client: OkHttpClient, url: String, user: String?, password: String?): Answer {
        val builder = Request.Builder().url(url).header("User-Agent", AGENT).header("OCS-APIRequest", "true")
        if (user != null && password != null) builder.header("Authorization", Credentials.basic(user, password, Charsets.UTF_8))
        client.newCall(builder.build()).execute().use { r -> return Answer(r.code, runCatching { r.body?.string().orEmpty() }.getOrDefault("")) }
    }

    /** The user id in a good answer from the server's "who am I", or null for anything else. */
    internal fun userId(code: Int, body: String): String? {
        if (code != 200) return null
        return runCatching {
            JSONObject(body).getJSONObject("ocs").getJSONObject("data").getString("id").takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    /** Whether a reply to status.php is a Nextcloud's own. */
    internal fun looksLikeNextcloud(code: Int, body: String): Boolean {
        if (code != 200) return false
        return runCatching {
            val o = JSONObject(body)
            o.has("installed") && (o.has("productname") || o.has("version"))
        }.getOrDefault(false)
    }

    internal fun problemFor(e: IOException): Problem = when (e) {
        is UnknownHostException -> Problem.NOT_FOUND
        is SSLException -> Problem.CERTIFICATE
        else -> Problem.UNREACHABLE
    }
}
