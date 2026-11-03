package com.wanderwildwood.oboegaki.desktop

/*
 * Which requests the server answers, and which of them need the token. Kept apart from the
 * server so the rule can be tested without a phone: every route that reads or changes a note
 * is listed in [API_ROUTES], and the test walks that list and proves each one is refused
 * without a token. See docs/DESKTOP-SYNC.md.
 */

/** A route the page calls, by method and path. */
data class Route(val method: String, val path: String)

/** Every route that needs the token. A route the server answers must be in here or in [PUBLIC_FILES]. */
val API_ROUTES: Set<Route> = setOf(
    Route("GET", "/api/state"),
    Route("GET", "/api/file"),
    Route("POST", "/api/notes/create"),
    Route("POST", "/api/notes/update"),
    Route("POST", "/api/notes/tick"),
    Route("POST", "/api/notes/additem"),
    Route("POST", "/api/notes/pin"),
    Route("POST", "/api/notes/archive"),
    Route("POST", "/api/notes/delete"),
    Route("POST", "/api/trash/restore"),
    Route("POST", "/api/trash/delete"),
    Route("POST", "/api/trash/empty"),
    Route("POST", "/api/tasks/add"),
    Route("POST", "/api/tasks/update"),
    Route("POST", "/api/tasks/tick"),
    Route("POST", "/api/tasks/delete"),
    Route("POST", "/api/unpair"),
)

/**
 * The files served to anyone the address check lets in, as the page is: the typeface the page
 * is set in. A stylesheet's @font-face cannot send the token, and a typeface says nothing about
 * the notes. Path to the font's name in the app's resources (Lato, inside MMD; SIL OFL 1.1).
 */
val PUBLIC_FILES: Map<String, String> = mapOf(
    "/fonts/lato-regular.ttf" to "lato_regular",
    "/fonts/lato-bold.ttf" to "lato_bold",
)

/** What the server does with a request once its address has been let in. */
enum class Gate {
    /** The page itself. */
    PAGE,
    /** One of [PUBLIC_FILES]. */
    PUBLIC_FILE,
    /** The favicon: nothing. */
    NOTHING,
    /** Exchanging a pairing code for a token: its own checks. */
    PAIR,
    /** A route in [API_ROUTES], token and header both right. */
    API,
    /** 405: only GET and POST. */
    BAD_METHOD,
    /** 401: no token, or not one this phone gave out. */
    NO_TOKEN,
    /** 403: a POST without the page's own header. */
    NOT_FROM_PAGE,
}

/**
 * The decision for one request. [headers] have lower-case names, as NanoHTTPD gives them;
 * [tokenValid] is asked about the bearer token only when one is needed. A path that is not a
 * route at all still needs the token first, so a stranger learns nothing about what is here.
 */
fun gate(method: String, rawPath: String, headers: Map<String, String>, tokenValid: (String?) -> Boolean): Gate {
    val path = rawPath.trimEnd('/')
    if (method != "GET" && method != "POST") return Gate.BAD_METHOD
    if (method == "GET" && (path == "" || path == "/index.html")) return Gate.PAGE
    if (method == "GET" && path in PUBLIC_FILES) return Gate.PUBLIC_FILE
    if (path == "/favicon.ico") return Gate.NOTHING
    if (method == "POST" && path == "/api/pair") return Gate.PAIR
    if (!tokenValid(bearerToken(headers["authorization"]))) return Gate.NO_TOKEN
    // A cross-origin page cannot add this header without a preflight that is never answered.
    if (method == "POST" && headers["x-notes"] != "1") return Gate.NOT_FROM_PAGE
    return Gate.API
}
