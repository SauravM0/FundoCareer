package com.fundocareer.app.core.jobalerts.provider

import android.util.Log
import com.fundocareer.app.core.jobalerts.JobAlertDefaults
import com.fundocareer.app.core.jobalerts.JobSearchCriteria
import com.fundocareer.app.core.jobalerts.JobSearchError
import com.fundocareer.app.core.jobalerts.JobSearchResult
import com.fundocareer.app.core.jobalerts.ParsedJob
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

class LinkedInGuestJobSourceProvider(
    private val client: OkHttpClient = LinkedInGuestJobSourceProvider.defaultClient()
) : JobSourceProvider {

    companion object {
        const val SOURCE = "LINKEDIN"
        private const val TAG = "LinkedInJobProvider"
        private const val SEARCH_URL = "https://www.linkedin.com/jobs-guest/jobs/api/seeMoreJobPostings/search"

        private const val TIMEOUT_SECONDS = 30L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        fun computeFingerprint(jobTitle: String, company: String, location: String, url: String?): String {
            val tag = "$SOURCE|"
            val u = url?.trim()?.lowercase()
            if (!u.isNullOrBlank()) {
                return "${tag}url:$u"
            }
            val t = jobTitle.trim().lowercase().replace(Regex("\\s+"), " ")
            val c = company.trim().lowercase().replace(Regex("\\s+"), " ")
            val l = location.trim().lowercase().replace(Regex("\\s+"), " ")
            return "${tag}title:$t|company:$c|location:$l"
        }
    }

    override val sourceName: String get() = SOURCE

    override suspend fun search(criteria: JobSearchCriteria): JobSearchResult {
        Log.i(TAG, "search: role=${criteria.role}, location=${criteria.location}, experience=${criteria.experience}")

        val allJobs = mutableListOf<ParsedJob>()
        var start = 0
        val pageSize = JobAlertDefaults.DEFAULT_PAGE_SIZE
        val maxResults = JobAlertDefaults.MAX_RESULTS

        while (start < maxResults) {
            val url = buildUrl(criteria, start, pageSize)
            Log.d(TAG, "Fetching: $url")

            val result = fetchPage(url)
            if (result.error != null) {
                Log.e(TAG, "Page fetch error: ${result.error}")
                return if (allJobs.isNotEmpty()) {
                    JobSearchResult(jobs = allJobs, source = SOURCE)
                } else {
                    result
                }
            }

            if (result.jobs.isEmpty()) {
                Log.d(TAG, "No more jobs at start=$start")
                break
            }

            val before = allJobs.size
            allJobs.addAll(result.jobs)
            Log.d(TAG, "Added ${result.jobs.size} jobs (total=${allJobs.size}, new=${allJobs.size - before})")

            if (result.jobs.size < pageSize) {
                Log.d(TAG, "Last page (returned ${result.jobs.size} < $pageSize)")
                break
            }

            start += pageSize
        }

        Log.i(TAG, "Search complete: ${allJobs.size} total jobs")
        return JobSearchResult(jobs = allJobs, source = SOURCE)
    }

    private suspend fun fetchPage(url: String): JobSearchResult {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            val code = response.code

            Log.d(TAG, "HTTP $code | body length=${body.length}")

            when {
                code == 429 -> {
                    Log.w(TAG, "Rate limited (HTTP 429)")
                    JobSearchResult(
                        jobs = emptyList(), source = SOURCE,
                        error = JobSearchError.RateLimited, isRateLimited = true
                    )
                }
                code in 400..499 -> {
                    Log.w(TAG, "Client error HTTP $code")
                    JobSearchResult(
                        jobs = emptyList(), source = SOURCE,
                        error = JobSearchError.HttpError(code)
                    )
                }
                code !in 200..299 -> {
                    Log.w(TAG, "Server error HTTP $code")
                    JobSearchResult(
                        jobs = emptyList(), source = SOURCE,
                        error = JobSearchError.HttpError(code)
                    )
                }
                body.isBlank() -> {
                    Log.w(TAG, "Empty response body")
                    JobSearchResult(
                        jobs = emptyList(), source = SOURCE,
                        error = JobSearchError.EmptyResponse
                    )
                }
                isBlockedPage(body) -> {
                    Log.w(TAG, "Block/redirect page detected")
                    JobSearchResult(
                        jobs = emptyList(), source = SOURCE,
                        error = JobSearchError.Blocked
                    )
                }
                else -> {
                    val jobs = parseJobs(body)
                    Log.d(TAG, "Parsed ${jobs.size} jobs from page")
                    JobSearchResult(jobs = jobs, source = SOURCE)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error: ${e.message}", e)
            JobSearchResult(
                jobs = emptyList(), source = SOURCE,
                error = JobSearchError.NetworkError(e.message ?: "Unknown network error")
            )
        }
    }

    private fun isBlockedPage(html: String): Boolean {
        val lower = html.lowercase()
        return lower.contains("please verify you're a human") ||
                lower.contains("unusual traffic") ||
                lower.contains("cf-browser-verification") ||
                lower.contains("access denied") ||
                lower.contains("robot") ||
                (html.length < 200 && lower.contains("captcha") || lower.contains("challenge"))
    }

    internal fun parseJobs(html: String): List<ParsedJob> {
        val doc: Document = Jsoup.parse(html)
        val cards = doc.select("div.base-card, div.job-search-card, div[class*='base-card'], li[class*='jobs-search-results__list-item'] article")
        if (cards.isEmpty()) {
            Log.d(TAG, "No card elements found with primary selectors, trying fallback")
            val fallbackCards = doc.select("div[class*='search-result']")
            if (fallbackCards.isEmpty()) {
                Log.d(TAG, "Fallback selectors also empty. Body preview: ${html.take(500)}")
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
                Log.w(TAG, "Skipping card parse error: ${e.message}")
            }
        }

        if (jobs.isEmpty() && cards.isNotEmpty()) {
            Log.w(TAG, "Cards found but zero parsed. Trying fallback per-card approach.")
            return parseFallbackCards(cards)
        }

        return jobs
    }

    private fun parseSingleCard(card: org.jsoup.nodes.Element): ParsedJob? {
        val linkEl = card.selectFirst("a[href*='/jobs/view']")
            ?: card.selectFirst("a.base-card__full-link")
            ?: card.selectFirst("a[href*='linkedin.com/jobs/view']")
        val url = linkEl?.attr("href")?.trim()
            ?.let { if (it.startsWith("http")) it else "https://www.linkedin.com${it}" }

        val titleEl = card.selectFirst(".base-search-card__title")
            ?: card.selectFirst("h3[class*='title']")
            ?: card.selectFirst("a[class*='title']")
            ?: card.selectFirst("h3")
        val title = titleEl?.text()?.trim() ?: return null

        val companyEl = card.selectFirst(".base-search-card__subtitle")
            ?: card.selectFirst("h4[class*='subtitle']")
            ?: card.selectFirst("h4[class*='company']")
            ?: card.selectFirst("h4")
        val company = companyEl?.text()?.trim() ?: ""

        val locationEl = card.selectFirst(".job-search-card__location")
            ?: card.selectFirst("[class*='location']")
            ?: card.selectFirst("[class*='metadata'] span")
        val location = locationEl?.text()?.trim() ?: ""

        val dateEl = card.selectFirst(".job-search-card__listdate")
            ?: card.selectFirst("time[class*='listdate']")
            ?: card.selectFirst("time")
        val postedDate = dateEl?.text()?.trim()

        val salaryEl = card.selectFirst("[class*='salary']")
        val salary = salaryEl?.text()?.trim()

        val descEl = card.selectFirst("[class*='description'], [class*='snippet'], p")
        val description = descEl?.text()?.trim() ?: ""

        if (title.isBlank()) return null

        val fingerprint = computeFingerprint(title, company, location, url)

        return ParsedJob(
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
                val company = extractFallbackText(card, "h4, [class*='company'], [class*='subtitle'], [class*='org'])") ?: ""
                val location = extractFallbackText(card, "[class*='location'], [class*='loc'], span.metadata") ?: ""
                val postedDate = extractFallbackText(card, "time, [class*='date'], [class*='listdate']")

                val fingerprint = computeFingerprint(title, company, location, url)

                jobs.add(ParsedJob(
                    title = title, company = company, location = location,
                    url = url, postedDate = postedDate, fingerprint = fingerprint
                ))
            } catch (_: Exception) { }
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
        val params = mutableListOf<String>()

        val keywords = buildKeywords(criteria)
        if (keywords.isNotBlank()) {
            params.add("keywords=${encodeParam(keywords)}")
        }

        if (criteria.location.isNotBlank()) {
            params.add("location=${encodeParam(criteria.location)}")
        }

        val tpr = mapDatePosted(criteria.datePosted)
        if (tpr != null) params.add("f_TPR=$tpr")

        val exp = mapExperience(criteria.experience)
        if (exp != null) params.add("f_E=$exp")

        val jobType = mapJobType(criteria)
        if (jobType != null) params.add("f_JT=$jobType")

        if (criteria.remote == true) {
            params.add("f_WT=3")
        }

        params.add("start=$start")
        params.add("count=$count")
        params.add("sortBy=DD")

        return "$SEARCH_URL?${params.joinToString("&")}"
    }

    private fun buildKeywords(criteria: JobSearchCriteria): String {
        val parts = mutableListOf(criteria.role)
        if (criteria.skills.isNotEmpty()) {
            parts.addAll(criteria.skills)
        }
        if (!criteria.company.isNullOrBlank()) {
            parts.add(criteria.company)
        }
        return parts.joinToString(" ")
    }

    private fun mapDatePosted(datePosted: String?): String? {
        return when (datePosted?.lowercase()) {
            "24h", "past 24 hours", "r86400" -> "r86400"
            "week", "past week", "r604800" -> "r604800"
            "month", "past month", "r2592000" -> "r2592000"
            else -> null
        }
    }

    private fun mapExperience(experience: String): String? {
        return when (experience.lowercase()) {
            "entry", "entry level", "internship" -> "2"
            "mid", "mid level", "associate" -> "4"
            "senior", "senior level" -> "3"
            "lead", "director", "executive" -> "5"
            else -> null
        }
    }

    private fun mapJobType(criteria: JobSearchCriteria): String? {
        return null
    }

    private fun encodeParam(value: String): String {
        return java.net.URLEncoder.encode(value, "UTF-8")
    }
}
