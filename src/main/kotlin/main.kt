import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.required
import org.kohsuke.github.GHIssueState
import org.kohsuke.github.GHPullRequest
import org.kohsuke.github.GHPullRequestReviewState
import org.kohsuke.github.GHRepository
import org.kohsuke.github.GitHubBuilder
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset

private const val OpenPRType = "OPEN"
private const val MergedPRType = "MERGED"
private val AllowedAnalysisTypes = listOf(OpenPRType, MergedPRType)
private const val TextOutputType = "TEXT"
private const val JsonOutputType = "JSON"
private val AllowedOutputTypes = listOf(TextOutputType, JsonOutputType)

private val JsonMapper = jacksonObjectMapper()

fun analyze(PRType: String, prPullLimit: Int, repoName: String, outputType: String, includeIndividualStats: Boolean) {
    val github = GitHubBuilder.fromEnvironment().build()
    val repo = github.getRepository(repoName)
    if (PRType.equals(OpenPRType, ignoreCase = true)) {
        handleOpenPrAnalysis(repo, prPullLimit, outputType)
    } else {
        handleMergedPrAnalysis(repo, prPullLimit, outputType, includeIndividualStats)
    }
}

private fun handleMergedPrAnalysis(
    repo: GHRepository,
    PRPullLimit: Int,
    outputType: String,
    includeIndividualStats: Boolean
) {
    if (outputType.equals(TextOutputType, ignoreCase = true))
        println("\nClosed PR Statistics (limit ${PRPullLimit})\nWorking...")

    // get PRs that were successfully merged
    val mergedPRs = repo.queryPullRequests().state(GHIssueState.CLOSED)
        .list()
        .take(PRPullLimit)
        .filter { it.mergedAt != null }
        .filter { it.labels.map { label -> label.name }.contains("exclude-from-analysis").not() }
    val timeToMergeDurations = mergedPRs.map {
        Duration.between(
            it.createdAt.toInstant().atZone(ZoneOffset.UTC).toLocalDateTime(),
            it.mergedAt.toInstant().atZone(ZoneOffset.UTC).toLocalDateTime()
        )
    }
    val timeToFirstReviewDurations = mergedPRs.map {
        val reviews = it.listReviews()
        if (reviews.any()) { // if a PR didn't have a review but was merged
            val firstReview = reviews.first { it.state != GHPullRequestReviewState.PENDING }
            val firstReviewTime = firstReview.createdAt.toInstant().atZone(ZoneOffset.UTC).toLocalDateTime()
            Duration.between(
                it.createdAt.toInstant().atZone(ZoneOffset.UTC).toLocalDateTime(),
                firstReviewTime
            )
        } else {
            null
        }
    }.filterNotNull()

    // return early as we don't have any PRs to analyze
    if (mergedPRs.count() == 0) {
        println("No PRs found to analyze.")
        return
    }

    val averageTimeToFirstReview =
        Duration.ofSeconds(timeToFirstReviewDurations.map { it.seconds }.sum() / mergedPRs.count())
    val averageTimeToMerge = Duration.ofSeconds(timeToMergeDurations.map { it.seconds }.sum() / mergedPRs.count())
    val repoContributors = repo.listContributors().map { it.login }
    val individualContributorStats = if (includeIndividualStats) getIndividualStatistics(repoContributors, mergedPRs) else emptyList()

    if (outputType.equals(JsonOutputType, ignoreCase = true)) {
        // yes this could be a function to make it a little less redundant... oh well
        val json = JsonMapper.writeValueAsString(
            mapOf(
                "average" to mapOf(
                    "firstReview" to averageTimeToFirstReview.seconds,
                    "merge" to averageTimeToMerge.seconds
                ),
                "max" to mapOf(
                    "firstReview" to timeToFirstReviewDurations.maxOrNull()!!.seconds,
                    "merge" to timeToMergeDurations.maxOrNull()!!.seconds
                ),
                "min" to mapOf(
                    "firstReview" to timeToFirstReviewDurations.minOrNull()!!.seconds,
                    "merge" to timeToMergeDurations.minOrNull()!!.seconds
                ),
                "individualStats" to individualContributorStats.map {
                    mapOf(
                        "name" to it.author,
                        "submittedReviews" to it.submittedReviews,
                        "wasRequestedReviews" to it.wasRequestedReviews
                    )
                }
            )
        )
        println(json)
    } else {
        printMergeStats("Average", averageTimeToFirstReview, averageTimeToMerge)
        printMergeStats("Max", timeToFirstReviewDurations.maxOrNull()!!, timeToMergeDurations.maxOrNull()!!)
        printMergeStats("Min", timeToFirstReviewDurations.minOrNull()!!, timeToMergeDurations.minOrNull()!!)
        individualContributorStats.filter { it.wasRequestedReviews > 0 || it.submittedReviews > 0 }.map {
            println(
                "Contributor Stats - Name: ${it.author} " +
                "- Submitted Reviews: ${it.submittedReviews} " +
                "- Was Requested Review: ${it.wasRequestedReviews}"
            )
        }
    }
}

private fun printMergeStats(prefix: String, firstReviewDuration: Duration, mergeDuration: Duration) {
    println("$prefix Time to First Review: ${firstReviewDuration.toDays()} days, ${firstReviewDuration.toHoursPart()} hours, ${firstReviewDuration.toMinutesPart()} minutes.")
    println("$prefix Time to Merge: ${mergeDuration.toDays()} days, ${mergeDuration.toHoursPart()} hours, ${mergeDuration.toMinutesPart()} minutes.")
}

private fun getIndividualStatistics(contributorNames: List<String>, prs: List<GHPullRequest>): List<ContributorStats> {
    val prsWithReviews = prs.map { it to it.listReviews() }
    return contributorNames.map { collaboratorName ->
        val prReviewResult = prsWithReviews.map { pr ->
            val didReview = pr.second.any { ghPrReview -> ghPrReview.user.login.equals(collaboratorName) }
            val wasRequested = pr.first.requestedReviewers.map { it.login }.contains(collaboratorName)
            didReview to wasRequested
        }
        ContributorStats(
            collaboratorName,
            prReviewResult.filter { it.first }.count(),
            prReviewResult.filter { it.second }.count()
        )
    }
}

data class ContributorStats(
    val author: String,
    val submittedReviews: Int,
    val wasRequestedReviews: Int
)

private fun handleOpenPrAnalysis(repo: GHRepository, PRPullLimit: Int, outputType: String) {
    if (outputType.equals(TextOutputType, ignoreCase = true)) println("Open PRs\nWorking...")

    val openPRs = repo.queryPullRequests().state(GHIssueState.OPEN).list().take(PRPullLimit)
    if (outputType.equals(JsonOutputType, ignoreCase = true)) {
        val json = JsonMapper.writeValueAsString(openPRs.map {
            mapOf(
                "number" to it.number,
                "title" to it.title,
                "createdAt" to it.createdAt.toInstant().atZone(ZoneOffset.UTC).toEpochSecond()
            )
        })
        println(json)
    } else {
        openPRs.forEach {
            val openDuration = Duration.between(
                it.createdAt.toInstant().atZone(ZoneOffset.UTC).toLocalDateTime(),
                LocalDateTime.now(ZoneOffset.UTC)
            )
            println("PR: ${it.number} ${it.title} has been open for ${openDuration.toDays()} days, ${openDuration.toHoursPart()} hours.")
        }
    }
}

fun main(args: Array<String>) {
    val parser = ArgParser("Github PR Utility")
    val analyzePRType by parser.option(
        ArgType.String,
        fullName = "analyze",
        shortName = "a",
        description = "Analyze PR Type - ${AllowedAnalysisTypes}"
    ).required()
    val pullRequestPullLimit by parser.option(
        ArgType.Int,
        fullName = "pr-limit",
        shortName = "l",
        description = "Limit the amount of PRs to analyze."
    ).default(10)
    val repositoryName by parser.option(
        ArgType.String,
        fullName = "repo-name",
        shortName = "r",
        description = "The repository to analyze (user must have read permission)."
    ).required()
    val outputType by parser.option(
        ArgType.String,
        fullName = "output",
        shortName = "o",
        description = "How to output the analytics results. - ${AllowedOutputTypes}"
    ).default(TextOutputType)
    val includeIndividualStats by parser.option(
        ArgType.Boolean,
        fullName = "individual-stats",
        shortName = "i",
        description = "Should include statistics on each individual contributor."
    ).default(false)
    parser.parse(args)

    if (!AllowedAnalysisTypes.contains(analyzePRType.toUpperCase())) {
        throw RuntimeException("--analyze parameter must be of value ${AllowedAnalysisTypes}")
    }
    if (!AllowedOutputTypes.contains(outputType.toUpperCase())) {
        throw RuntimeException("--output parameter must be of value ${AllowedOutputTypes}")
    }

    analyze(analyzePRType, pullRequestPullLimit, repositoryName, outputType, includeIndividualStats)
}
