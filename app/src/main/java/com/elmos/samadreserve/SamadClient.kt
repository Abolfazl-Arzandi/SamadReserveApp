package com.elmos.samadreserve

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

data class DayStatus(
    val dayName: String,
    val jalaliDate: String,
    val status: String, // "OK", "FAILED", "DISABLED", "EMPTY", "OPEN"
    val detail: String,
    val cbName: String? = null,
    val cbVal: String = "true",
    val cbFee: Int = 0,
    val optionsCount: Int = 0
)

data class CycleResult(
    val overallStatus: String,
    val bannerStatus: String,
    val serverMessage: String,
    val days: List<DayStatus>
)

private class SimpleCookieJar : CookieJar {
    private val store = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (c in cookies) {
            store.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
            if (c.expiresAt > System.currentTimeMillis()) {
                store.add(c)
            }
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        store.removeAll { it.expiresAt <= now }
        return store.filter { it.matches(url) }
    }
}

class SamadClient(private val onLog: (String) -> Unit) {

    companion object {
        private const val LOGIN_URL = "https://stu.iust.ac.ir/"
        private const val PANEL_PATH = "/nurture/user/multi/reserve/showPanel.rose"
        private val WEEKDAYS = listOf(
            "Saturday", "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday"
        )
    }

    private val client = OkHttpClient.Builder()
        .cookieJar(SimpleCookieJar())
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    private var lastBaseUrl: String = LOGIN_URL

    private fun get(url: String): Pair<String, Document> {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36")
            .header("Accept-Language", "fa-IR,fa;q=0.9,en-US;q=0.8")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} on GET $url")
            val finalUrl = resp.request.url.toString()
            val html = resp.body?.string() ?: ""
            return finalUrl to Jsoup.parse(html, finalUrl)
        }
    }

    private fun post(url: String, referer: String, params: Map<String, String>): Pair<String, Document> {
        val formBuilder = FormBody.Builder()
        for ((k, v) in params) {
            formBuilder.add(k, v)
        }
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36")
            .header("Accept-Language", "fa-IR,fa;q=0.9,en-US;q=0.8")
            .header("Referer", referer)
            .post(formBuilder.build())
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} on POST $url")
            val finalUrl = resp.request.url.toString()
            val html = resp.body?.string() ?: ""
            return finalUrl to Jsoup.parse(html, finalUrl)
        }
    }

    private fun resolveUrl(baseUrl: String, relativeOrFull: String): String {
        return baseUrl.toHttpUrl().resolve(relativeOrFull)?.toString() ?: relativeOrFull
    }

    private fun isLoggedIn(doc: Document): Boolean {
        if (doc.selectFirst("input#username") != null) return false
        return doc.selectFirst("#mega-menu-1") != null || doc.selectFirst("#selfId") != null
    }

    private fun getBannerInfo(doc: Document): Pair<String, String> {
        val errEls = doc.select("#errorMessages, .messages-container .error")
        if (errEls.isNotEmpty()) {
            val text = errEls.map { it.text().trim() }.filter { it.isNotEmpty() }.joinToString(" | ")
            return "error" to text
        }
        val okEls = doc.select("#successMessages, .messages-container .message, .messages-container .success")
        if (okEls.isNotEmpty()) {
            val text = okEls.map { it.text().trim() }.filter { it.isNotEmpty() }.joinToString(" | ")
            return "success" to text
        }
        val container = doc.selectFirst(".messages-container")
        val text = container?.text()?.trim() ?: ""
        if (text.isNotEmpty()) return "info" to text
        return "none" to ""
    }

    fun login(username: String, password: String): String {
        onLog("[1/6] Opening login page...")
        val (pageUrl, doc) = get(LOGIN_URL)
        if (isLoggedIn(doc)) {
            onLog("Already logged in.")
            lastBaseUrl = pageUrl
            return pageUrl
        }

        val loginForm = doc.selectFirst("form[name=loginForm]") ?: doc.selectFirst("form")
            ?: throw RuntimeException("Login form not found.")
        val action = resolveUrl(pageUrl, loginForm.attr("action").ifBlank { "/j_security_check" })

        val payload = linkedMapOf<String, String>()
        for (inp in loginForm.select("input")) {
            val name = inp.attr("name")
            if (name.isBlank()) continue
            if (inp.attr("type").equals("hidden", ignoreCase = true)) {
                payload[name] = inp.attr("value")
            }
        }
        payload["username"] = username
        payload["password"] = password

        onLog("[2/6] Submitting login credentials...")
        val (landingUrl, landingDoc) = post(action, pageUrl, payload)
        if (!isLoggedIn(landingDoc)) {
            val redErr = landingDoc.select("form[name=loginForm] div[style*=red]")
                .map { it.text().trim() }.filter { it.isNotEmpty() }.joinToString(" | ")
            val (_, bannerMsg) = getBannerInfo(landingDoc)
            val details = redErr.ifEmpty { bannerMsg.ifEmpty { "Check username or password" } }
            throw RuntimeException("Login failed: $details")
        }
        onLog("Login successful!")
        lastBaseUrl = landingUrl
        return landingUrl
    }

    private fun buildResFormPayload(form: Element, methodButtonName: String): Pair<String, MutableMap<String, String>> {
        val action = form.attr("action").ifBlank { "/nurture/user/multi/reserve/reserve.rose" }
        val payload = linkedMapOf<String, String>()
        val checkedCountNames = mutableSetOf<String>()

        for (inp in form.select("input")) {
            val name = inp.attr("name")
            if (name.isBlank() || inp.hasAttr("disabled")) continue
            when (inp.attr("type").lowercase().ifBlank { "text" }) {
                "submit", "button", "image", "reset" -> continue
                "checkbox" -> {
                    if (inp.hasAttr("checked")) {
                        payload[name] = inp.attr("value").ifBlank { "true" }
                        if (name.endsWith(".selected")) {
                            checkedCountNames.add(name.replace(".selected", ".selectedCount"))
                        }
                    }
                }
                "radio" -> {
                    if (inp.hasAttr("checked")) {
                        payload[name] = inp.attr("value").ifBlank { "on" }
                    }
                }
                else -> payload[name] = inp.attr("value")
            }
        }

        for (sel in form.select("select")) {
            val name = sel.attr("name")
            if (name.isBlank()) continue
            if (sel.hasAttr("disabled") && name !in checkedCountNames) continue
            val opt = sel.selectFirst("option[selected]") ?: sel.selectFirst("option")
            if (opt != null) {
                payload[name] = opt.attr("value")
            }
        }

        payload[methodButtonName] = "Submit"
        return action to payload
    }

    private fun getCurrentSelfId(form: Element): String {
        val hiddenVal = form.selectFirst("#selfHiddenId")?.attr("value")?.trim() ?: ""
        if (hiddenVal.isNotEmpty()) return hiddenVal
        return form.selectFirst("#selfId option[selected]")?.attr("value")?.trim() ?: ""
    }

    private fun parseCredit(doc: Document): Long? {
        val text = doc.selectFirst("#creditId")?.text()?.trim() ?: return null
        val digits = text.replace(Regex("[^\\d-]"), "")
        return digits.toLongOrNull()
    }

    private fun parseCheckboxFee(cb: Element): Int {
        val onclick = cb.attr("onclick")
        val regex = Regex("enableDisableNumber(?:ForFreeFood)?\\([^,]+,\\s*[^,]+,\\s*'(\\d+)'")
        return regex.find(onclick)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    }

    private fun inspectWeekLunchTable(form: Element): List<DayStatus> {
        val headerTr = form.select("tr").firstOrNull { tr ->
            tr.children().any { it.tagName().equals("th", ignoreCase = true) }
        } ?: return emptyList()

        val headerCells = headerTr.children().asSequence()
            .filter { it.tagName().equals("th", true) || it.tagName().equals("td", true) }
            .toList()

        var lunchColIdx = 2
        for ((idx, cell) in headerCells.withIndex()) {
            if (cell.selectFirst("[mtid=2]") != null) {
                lunchColIdx = idx
                break
            }
        }

        val dayRows = headerTr.siblingElements().asSequence()
            .filter { it.tagName().equals("tr", true) }
            .toList()

        val results = mutableListOf<DayStatus>()

        for ((rowIdx, row) in dayRows.withIndex()) {
            val cells = row.children().asSequence()
                .filter { it.tagName().equals("td", true) }
                .toList()

            if (cells.size <= lunchColIdx) continue

            val dayName = WEEKDAYS.getOrElse(rowIdx) { "Day-${rowIdx + 1}" }
            val jalaliDate = cells[0].selectFirst("div")?.text()?.trim() ?: "----/--/--"
            val lunchCell = cells[lunchColIdx]
            val checkboxes = lunchCell.select("input[type=checkbox][name^=userWeekReserves][name$=\\.selected]")

            if (checkboxes.isNotEmpty()) {
                if (checkboxes.any { it.hasAttr("checked") }) {
                    results.add(DayStatus(dayName, jalaliDate, "OK", "Already reserved"))
                } else {
                    val firstCb = checkboxes[0]
                    val chosen = if (!firstCb.hasAttr("disabled")) {
                        firstCb
                    } else {
                        checkboxes.firstOrNull { !it.hasAttr("disabled") }
                    }
                    if (chosen != null) {
                        results.add(
                            DayStatus(
                                dayName = dayName,
                                jalaliDate = jalaliDate,
                                status = "OPEN",
                                detail = "Open",
                                cbName = chosen.attr("name"),
                                cbVal = chosen.attr("value").ifBlank { "true" },
                                cbFee = parseCheckboxFee(chosen),
                                optionsCount = checkboxes.size
                            )
                        )
                    } else {
                        results.add(DayStatus(dayName, jalaliDate, "DISABLED", "Checkbox disabled"))
                    }
                }
            } else {
                val cellText = lunchCell.text().replace("\u00A0", "").trim()
                if (cellText.isNotEmpty()) {
                    results.add(DayStatus(dayName, jalaliDate, "OK", "Already reserved"))
                } else {
                    results.add(DayStatus(dayName, jalaliDate, "EMPTY", "No food option"))
                }
            }
        }
        return results
    }

    fun checkAndReserveNextWeek(username: String, password: String, targetSelfId: String): CycleResult {
        var panelUrl = resolveUrl(lastBaseUrl, PANEL_PATH)
        onLog("[3/6] Opening reservation panel...")
        var (currUrl, doc) = get(panelUrl)

        if (!isLoggedIn(doc)) {
            onLog("Session expired. Re-logging in...")
            val newBase = login(username, password)
            panelUrl = resolveUrl(newBase, PANEL_PATH)
            val res = get(panelUrl)
            currUrl = res.first
            doc = res.second
        }
        lastBaseUrl = currUrl

        var form = doc.selectFirst("form#resFinalform")
            ?: throw RuntimeException("Reservation form not found.")

        // Select target self if needed
        if (getCurrentSelfId(form) != targetSelfId) {
            val opt = form.selectFirst("select#selfId option[value=$targetSelfId]")
            if (opt == null) {
                val msg = "Self ID $targetSelfId not found in dropdown."
                onLog("Error: $msg")
                return CycleResult("FAILED ($msg)", "error", msg, emptyList())
            }
            onLog("[4/6] Selecting self ID $targetSelfId...")
            val (actionPath, payload) = buildResFormPayload(form, "method:showPanel")
            payload["selectedSelfDefId"] = targetSelfId
            payload["selectedSelfDefIdCombo"] = targetSelfId
            val res = post(resolveUrl(currUrl, actionPath), currUrl, payload)
            currUrl = res.first
            doc = res.second
            form = doc.selectFirst("form#resFinalform")
                ?: throw RuntimeException("Form missing after selecting self.")
        }

        // Switch to Next Week
        onLog("[5/6] Switching to Next Week...")
        run {
            val (actionPath, payload) = buildResFormPayload(form, "method:showNextWeek")
            val res = post(resolveUrl(currUrl, actionPath), currUrl, payload)
            currUrl = res.first
            doc = res.second
            form = doc.selectFirst("form#resFinalform")
                ?: throw RuntimeException("Form missing after switching to next week.")
        }

        // Re-select target self on next week if reset
        if (getCurrentSelfId(form) != targetSelfId) {
            val opt = form.selectFirst("select#selfId option[value=$targetSelfId]")
            if (opt == null) {
                val (bStatus, bMsg) = getBannerInfo(doc)
                val msg = "Self ID $targetSelfId unavailable for next week."
                onLog(msg)
                if (bMsg.isNotEmpty()) onLog("Server ($bStatus): $bMsg")
                return CycleResult("NO PROGRAM", bStatus, bMsg.ifEmpty { msg }, emptyList())
            }
            onLog("Re-selecting self ID $targetSelfId for next week...")
            val (actionPath, payload) = buildResFormPayload(form, "method:showPanel")
            payload["selectedSelfDefId"] = targetSelfId
            payload["selectedSelfDefIdCombo"] = targetSelfId
            val res = post(resolveUrl(currUrl, actionPath), currUrl, payload)
            currUrl = res.first
            doc = res.second
            form = doc.selectFirst("form#resFinalform")
                ?: throw RuntimeException("Form missing after re-selecting self.")
        }

        lastBaseUrl = currUrl
        onLog("[6/6] Inspecting next week's lunch schedule...")
        val beforeRows = inspectWeekLunchTable(form)
        val (beforeBannerStatus, beforeBannerMsg) = getBannerInfo(doc)

        if (beforeRows.isEmpty()) {
            val msg = "No meal schedule defined for next week yet."
            onLog(msg)
            if (beforeBannerMsg.isNotEmpty()) onLog("Server ($beforeBannerStatus): $beforeBannerMsg")
            return CycleResult("NO PROGRAM", beforeBannerStatus, beforeBannerMsg.ifEmpty { msg }, emptyList())
        }

        val openRows = beforeRows.filter { it.status == "OPEN" && it.cbName != null }
        if (openRows.isEmpty()) {
            onLog("No new open lunch slots found.")
            if (beforeBannerMsg.isNotEmpty()) onLog("Server ($beforeBannerStatus): $beforeBannerMsg")
            return CycleResult(
                "NO ACTION (All resolved/empty)",
                beforeBannerStatus,
                beforeBannerMsg.ifEmpty { "Checked successfully; no open slots." },
                beforeRows
            )
        }

        // Prepare final reserve POST
        val (actionPath, payload) = buildResFormPayload(form, "method:doReserve")
        payload["selectedSelfDefId"] = targetSelfId

        val currentCredit = parseCredit(doc)
        val isFreeRest = form.selectFirst("#freeRestaurant")?.attr("value")?.equals("true", true) == true
        var totalFee = 0L
        val attemptedDates = mutableSetOf<String>()

        for (row in openRows) {
            val cbName = row.cbName!!
            val countName = cbName.replace(".selected", ".selectedCount")
            payload[cbName] = row.cbVal

            val countSel = form.selectFirst("select[name=$countName]")
            val qty = countSel?.let {
                (it.selectFirst("option[selected]") ?: it.selectFirst("option"))?.attr("value")
            }?.ifBlank { "1" } ?: "1"
            payload[countName] = qty

            if (!isFreeRest) {
                totalFee += row.cbFee.toLong() * (qty.toLongOrNull() ?: 1L)
            }
            attemptedDates.add(row.jalaliDate)
            val note = if (row.optionsCount > 1) " (menu #1 picked)" else ""
            onLog("  + Prepared ${row.dayName} (${row.jalaliDate})$note")
        }

        if (currentCredit != null) {
            val remainCredit = currentCredit - totalFee
            payload["remainCredit"] = remainCredit.toString()
            onLog("Credit: $currentCredit -> $remainCredit Rials (fee: $totalFee)")
        }

        onLog("Submitting reservation for ${attemptedDates.size} day(s)...")
        val (afterUrl, afterDoc) = post(resolveUrl(currUrl, actionPath), currUrl, payload)
        lastBaseUrl = afterUrl

        val (afterBannerStatus, afterBannerMsg) = getBannerInfo(afterDoc)
        onLog("Server ($afterBannerStatus): ${afterBannerMsg.ifEmpty { "No banner message" }}")

        val afterForm = afterDoc.selectFirst("form#resFinalform")
        val afterRows = if (afterForm != null) inspectWeekLunchTable(afterForm) else emptyList()

        var reservedNow = 0
        var failedNow = 0
        val finalDays = afterRows.map { row ->
            if (row.jalaliDate in attemptedDates) {
                if (row.status == "OK") {
                    reservedNow++
                    onLog("  -> ${row.dayName} (${row.jalaliDate}): OK (Reserved!)")
                    row.copy(status = "OK", detail = "Reserved in this run!")
                } else {
                    failedNow++
                    onLog("  -> ${row.dayName} (${row.jalaliDate}): FAILED (Rejected / full)")
                    row.copy(status = "FAILED", detail = "Failed (rejected / capacity full)")
                }
            } else {
                onLog("  -> ${row.dayName} (${row.jalaliDate}): ${row.detail}")
                row
            }
        }

        val overall = when {
            afterBannerStatus == "error" || failedNow > 0 ->
                if (reservedNow > 0) "PARTIAL ($reservedNow ok, $failedNow failed)"
                else "FAILED ($failedNow rejected by server)"
            afterBannerStatus == "success" || reservedNow > 0 ->
                "SUCCESS ($reservedNow day(s) reserved)"
            else -> "UNKNOWN"
        }

        onLog("Result: $overall")
        return CycleResult(overall, afterBannerStatus, afterBannerMsg.ifEmpty { overall }, finalDays)
    }
}
