package com.fundocareer.app.core.jobalerts.provider

import com.fundocareer.app.core.jobalerts.JobAlertDefaults
import com.fundocareer.app.core.jobalerts.JobSearchCriteria
import com.fundocareer.app.core.jobalerts.JobSearchError
import com.fundocareer.app.core.jobalerts.JobSearchResult
import com.fundocareer.app.core.jobalerts.ParsedJob
import com.fundocareer.app.core.logging.FcLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class LinkedInGuestJobSourceProvider(
    private val client: OkHttpClient = LinkedInGuestJobSourceProvider.defaultClient()
) : JobSourceProvider {

    companion object {
        const val SOURCE = "LINKEDIN"
        private const val SEARCH_URL = "https://www.linkedin.com/jobs-guest/jobs/api/seeMoreJobPostings/search"

        private const val TIMEOUT_SECONDS = 30L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        fun computeFingerprint(jobId: String?, jobTitle: String, company: String, location: String, url: String?): String {
            val tag = "$SOURCE|"
            val id = jobId?.trim()
            if (!id.isNullOrBlank()) {
                return "${tag}id:$id"
            }
            val u = url?.trim()?.lowercase()
            if (!u.isNullOrBlank()) {
                return "${tag}url:${sha256(u)}"
            }
            val t = jobTitle.trim().lowercase().replace(Regex("\\s+"), " ")
            val c = company.trim().lowercase().replace(Regex("\\s+"), " ")
            val l = location.trim().lowercase().replace(Regex("\\s+"), " ")
            return "${tag}fallback:${sha256("$t|$c|$l")}"
        }

        private fun sha256(value: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }

    override val sourceName: String get() = SOURCE

    override suspend fun search(criteria: JobSearchCriteria): JobSearchResult {
        FcLog.i(FcLog.TAG_WORKER, "LinkedIn search started", mapOf(
            "role" to criteria.role,
            "location" to criteria.location,
            "experience" to criteria.experience,
        ))

        val allJobs = mutableListOf<ParsedJob>()
        var start = 0
        val pageSize = JobAlertDefaults.DEFAULT_PAGE_SIZE
        val maxResults = JobAlertDefaults.MAX_RESULTS

        while (start < maxResults) {
            val url = buildUrl(criteria, start, pageSize)

            val result = fetchPage(url)
            if (result.error != null) {
                FcLog.e(FcLog.TAG_WORKER, "LinkedIn page fetch error", null, mapOf(
                    "start" to start,
                    "error" to result.error?.toString(),
                ))
                return if (allJobs.isNotEmpty()) {
                    JobSearchResult(jobs = allJobs, source = SOURCE)
                } else {
                    result
                }
            }

            if (result.jobs.isEmpty()) {
                FcLog.d(FcLog.TAG_WORKER, "No more LinkedIn jobs", mapOf(
                    "start" to start,
                ))
                break
            }

            val before = allJobs.size
            allJobs.addAll(result.jobs)
            FcLog.d(FcLog.TAG_WORKER, "LinkedIn page added", mapOf(
                "batchSize" to result.jobs.size,
                "total" to allJobs.size,
            ))

            if (result.jobs.size < pageSize) {
                FcLog.d(FcLog.TAG_WORKER, "Last LinkedIn page reached", mapOf(
                    "returned" to result.jobs.size,
                    "pageSize" to pageSize,
                ))
                break
            }

            start += pageSize
        }

        FcLog.i(FcLog.TAG_WORKER, "LinkedIn search complete", mapOf(
            "totalJobs" to allJobs.size,
        ))
        return JobSearchResult(jobs = allJobs, source = SOURCE)
    }

    private suspend fun fetchPage(url: String): JobSearchResult {
        return withContext(Dispatchers.IO) {
            try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .header("Referer", "https://www.linkedin.com/jobs/search/")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            val code = response.code

            FcLog.d(FcLog.TAG_WORKER, "LinkedIn fetch response", mapOf(
                "httpCode" to code,
                "bodyLength" to body.length,
            ))

            when {
                code == 429 -> {
                    FcLog.w(FcLog.TAG_WORKER, "LinkedIn rate limited (HTTP 429)")
                    JobSearchResult(
                        jobs = emptyList(), source = SOURCE,
                        error = JobSearchError.RateLimited, isRateLimited = true
                    )
                }
                code in 400..499 -> {
                    FcLog.w(FcLog.TAG_WORKER, "LinkedIn client error", mapOf(
                        "httpCode" to code,
                    ))
                    JobSearchResult(
                        jobs = emptyList(), source = SOURCE,
                        error = JobSearchError.HttpError(code)
                    )
                }
                code !in 200..299 -> {
                    FcLog.w(FcLog.TAG_WORKER, "LinkedIn server error", mapOf(
                        "httpCode" to code,
                    ))
                    JobSearchResult(
                        jobs = emptyList(), source = SOURCE,
                        error = JobSearchError.HttpError(code)
                    )
                }
                body.isBlank() -> {
                    FcLog.w(FcLog.TAG_WORKER, "LinkedIn empty response body")
                    JobSearchResult(
                        jobs = emptyList(), source = SOURCE,
                        error = JobSearchError.EmptyResponse
                    )
                }
                isBlockedPage(body) -> {
                    FcLog.w(FcLog.TAG_WORKER, "LinkedIn block/redirect page detected")
                    JobSearchResult(
                        jobs = emptyList(), source = SOURCE,
                        error = JobSearchError.Blocked
                    )
                }
                else -> {
                    val jobs = parseJobs(body)
                    FcLog.d(FcLog.TAG_WORKER, "LinkedIn parsed jobs", mapOf(
                        "count" to jobs.size,
                    ))
                    JobSearchResult(jobs = jobs, source = SOURCE)
                }
            }
        } catch (e: Exception) {
            FcLog.e(FcLog.TAG_WORKER, "LinkedIn network error", e, mapOf(
                "error" to e.message,
            ))
            JobSearchResult(
                jobs = emptyList(), source = SOURCE,
                error = JobSearchError.NetworkError(e.message ?: "Unknown network error")
            )
        }
        }
    }

    private fun isBlockedPage(html: String): Boolean {
        val lower = html.lowercase()
        return lower.contains("please verify you're a human") ||
                lower.contains("unusual traffic") ||
                lower.contains("authwall") ||
                lower.contains("captcha") ||
                lower.contains("security verification") ||
                lower.contains("cf-browser-verification") ||
                lower.contains("access denied") ||
                lower.contains("robot") ||
                (html.length < 200 && lower.contains("challenge"))
    }

    internal fun parseJobs(html: String): List<ParsedJob> {
        val doc: Document = Jsoup.parse(html)
        val cards = doc.select("div.base-card, div.job-search-card, div[class*='base-card'], li[class*='jobs-search-results__list-item'] article")
        if (cards.isEmpty()) {
            FcLog.d(FcLog.TAG_WORKER, "No LinkedIn card elements found, trying fallback")
            val fallbackCards = doc.select("div[class*='search-result']")
            if (fallbackCards.isEmpty()) {
                FcLog.d(FcLog.TAG_WORKER, "Fallback selectors also empty")
                return emptyList()
            }
            return parseFallbackCards(fallbackCards)
        }

        val jobs = mutableListOf<ParsedJob>()
        for (card in cards) {
            try {
                val job = parseSingleCard(card)
                if (job != null) jobs.add(job)
            } catch (e: Exception) {
                FcLog.w(FcLog.TAG_WORKER, "Skipping LinkedIn card parse error", mapOf(
                    "error" to e.message,
                ))
            }
        }

        if (jobs.isEmpty() && cards.isNotEmpty()) {
            FcLog.w(FcLog.TAG_WORKER, "Cards found but zero parsed, trying fallback")
            return parseFallbackCards(cards)
        }

        return jobs
    }

    private fun parseSingleCard(card: org.jsoup.nodes.Element): ParsedJob? {
        val linkEl = card.selectFirst("a.base-card__full-link[href]")
            ?: card.selectFirst("a[href*='/jobs/view']")
            ?: card.selectFirst("a.base-card__full-link")
            ?: card.selectFirst("a[href*='linkedin.com/jobs/view']")
        val url = linkEl?.attr("href")?.trim()
            ?.let { if (it.startsWith("http")) it else "https://www.linkedin.com${it}" }
            ?.substringBefore("?")

        val jobId = extractJobId(card, url)

        val titleEl = card.selectFirst("h3.base-search-card__title")
            ?: card.selectFirst(".base-search-card__title")
            ?: card.selectFirst("h3[class*='title']")
            ?: card.selectFirst("a[class*='title']")
            ?: card.selectFirst("h3")
        val title = titleEl?.text()?.trim() ?: return null

        val companyEl = card.selectFirst("h4.base-search-card__subtitle")
            ?: card.selectFirst(".base-search-card__subtitle")
            ?: card.selectFirst("h4[class*='subtitle']")
            ?: card.selectFirst("h4[class*='company']")
            ?: card.selectFirst("h4")
        val company = companyEl?.text()?.trim() ?: ""

        val locationEl = card.selectFirst("span.job-search-card__location")
            ?: card.selectFirst(".job-search-card__location")
            ?: card.selectFirst("[class*='location']")
            ?: card.selectFirst("[class*='metadata'] span")
        val location = locationEl?.text()?.trim() ?: ""

        val dateEl = card.selectFirst(".job-search-card__listdate")
            ?: card.selectFirst(".job-search-card__listdate--new")
            ?: card.selectFirst("time[class*='listdate']")
            ?: card.selectFirst("time")
        val postedDate = dateEl?.attr("datetime")?.takeIf { it.isNotBlank() } ?: dateEl?.text()?.trim()

        val salaryEl = card.selectFirst("[class*='salary']")
        val salary = salaryEl?.text()?.trim()

        val descEl = card.selectFirst("[class*='description'], [class*='snippet'], p")
        val description = descEl?.text()?.trim() ?: ""

        if (title.isBlank()) return null

        val fingerprint = computeFingerprint(jobId, title, company, location, url)

        return ParsedJob(
            jobId = jobId,
            title = title,
            company = company,
            location = location,
            description = description,
            url = url,
            salary = salary,
            postedDate = postedDate,
            fingerprint = fingerprint
        )
    }

    private fun parseFallbackCards(cards: org.jsoup.select.Elements): List<ParsedJob> {
        val jobs = mutableListOf<ParsedJob>()
        for (card in cards) {
            try {
                val anchors = card.select("a[href]")
                var url: String? = null
                for (a in anchors) {
                    val href = a.attr("href")
                    if (href.contains("/jobs/view") || href.contains("linkedin.com/jobs")) {
                        url = if (href.startsWith("http")) href else "https://www.linkedin.com$href"
                        break
                    }
                }

                val title = extractFallbackText(card, "h3, h2, [class*='title'], [class*='job-title']") ?: continue
                val company = extractFallbackText(card, "h4, [class*='company'], [class*='subtitle'], [class*='org']") ?: ""
                val location = extractFallbackText(card, "[class*='location'], [class*='loc'], span.metadata") ?: ""
                val postedDate = extractFallbackText(card, "time, [class*='date'], [class*='listdate']")

                val jobId = extractJobId(card, url)
                val fingerprint = computeFingerprint(jobId, title, company, location, url)

                jobs.add(ParsedJob(
                    jobId = jobId, title = title, company = company, location = location,
                    url = url, postedDate = postedDate, fingerprint = fingerprint
                ))
            } catch (e: Exception) {
                FcLog.w(FcLog.TAG_WORKER, "Skipping malformed LinkedIn job card", mapOf(
                    "error" to e.message,
                ))
            }
        }
        return jobs
    }

    private fun extractFallbackText(card: org.jsoup.nodes.Element, selectors: String): String? {
        for (sel in selectors.split(", ")) {
            val el = card.selectFirst(sel)
            val text = el?.text()?.trim()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    internal fun buildUrl(criteria: JobSearchCriteria, start: Int, count: Int): String {
        val safeCount = count.coerceIn(1, 50)
        val builder = SEARCH_URL.toHttpUrl().newBuilder()

        buildKeywords(criteria).takeIf { it.isNotBlank() }?.let { builder.addQueryParameter("keywords", it) }
        criteria.location.trim().takeIf { it.isNotBlank() }?.let { builder.addQueryParameter("location", it) }
        mapDatePosted(criteria.datePosted)?.let { builder.addQueryParameter("f_TPR", it) }
        mapExperience(criteria.experience)?.let { builder.addQueryParameter("f_E", it) }
        mapJobType(criteria)?.let { builder.addQueryParameter("f_JT", it) }
        if (criteria.activelyHiring) builder.addQueryParameter("f_AL", "true")
        if (criteria.remote == true) builder.addQueryParameter("f_WT", "3")

        builder.addQueryParameter("sortBy", "R")
        builder.addQueryParameter("start", start.coerceAtLeast(0).toString())
        builder.addQueryParameter("count", safeCount.toString())

        return builder.build().toString()
    }

    private fun buildKeywords(criteria: JobSearchCriteria): String {
        val parts = mutableListOf<String>()
        criteria.role.split(",", "/", "|")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .firstOrNull()
            ?.let { parts.add(it) }
        criteria.skills
            .flatMap { it.split(",", "/", "|") }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .take(8)
            .forEach { parts.add(it) }
        if (!criteria.company.isNullOrBlank()) {
            parts.add(criteria.company.trim())
        }
        return parts.joinToString(" ")
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex("\\s+"), " ")
            .take(240)
    }

    private fun mapDatePosted(datePosted: String?): String? {
        return when (datePosted?.lowercase()) {
            null, "", "hour", "past hour", "r3600" -> "r3600"
            "24h", "past 24 hours", "r86400" -> "r86400"
            "week", "past week", "r604800" -> "r604800"
            "month", "past month", "r2592000" -> "r2592000"
            else -> null
        }
    }

    private fun mapExperience(experience: String): String? {
        return when (experience.lowercase()) {
            "intern", "internship" -> "1"
            "entry", "entry level" -> "2"
            "associate", "mid", "mid level" -> "3"
            "senior", "senior level", "mid-senior", "mid senior" -> "4"
            "lead", "director" -> "5"
            "executive" -> "6"
            else -> null
        }
    }

    private fun mapJobType(criteria: JobSearchCriteria): String? {
        val mapped = criteria.jobTypes.mapNotNull { type ->
            when (type.lowercase().trim()) {
                "full-time", "full time", "fulltime", "f" -> "F"
                "contract", "contractor", "c" -> "C"
                "part-time", "part time", "parttime", "p" -> "P"
                "temporary", "temp", "t" -> "T"
                "internship", "intern", "i" -> "I"
                else -> null
            }
        }
        return (if (mapped.isEmpty()) listOf("F", "C") else mapped).distinct().joinToString(",")
    }

    private fun extractJobId(card: org.jsoup.nodes.Element, url: String?): String? {
        val attrs = listOf(
            card.attr("data-entity-urn"),
            card.attr("data-id"),
            card.attr("data-job-id"),
            card.selectFirst("[data-entity-urn]")?.attr("data-entity-urn"),
            card.selectFirst("[data-id]")?.attr("data-id"),
            card.selectFirst("[data-job-id]")?.attr("data-job-id"),
        )
        attrs.firstOrNull { !it.isNullOrBlank() }?.let { raw ->
            Regex("(\\d{6,})").find(raw)?.value?.let { return it }
        }
        if (!url.isNullOrBlank()) {
            Regex("/jobs/view/(\\d+)").find(url)?.groupValues?.getOrNull(1)?.let { return it }
            Regex("currentJobId=(\\d+)").find(url)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return null
    }
}
