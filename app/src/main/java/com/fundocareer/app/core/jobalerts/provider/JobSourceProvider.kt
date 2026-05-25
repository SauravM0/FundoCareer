package com.fundocareer.app.core.jobalerts.provider

import com.fundocareer.app.core.jobalerts.JobSearchCriteria
import com.fundocareer.app.core.jobalerts.JobSearchResult

interface JobSourceProvider {

    suspend fun search(criteria: JobSearchCriteria): JobSearchResult

    val sourceName: String
}
