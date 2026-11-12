package life.bloomary.app.application.di

import java.time.Instant
import life.bloomary.app.features.drafts.model.DraftSchedule
import life.bloomary.app.features.drafts.model.DraftScheduling
import life.bloomary.app.features.linkedinpost.api.LinkedInPostCredential
import life.bloomary.app.features.linkedinpost.api.LinkedInPostService
import life.bloomary.app.features.linkedinpost.api.ScheduledPostResult

/** Composes the posting slice into the drafts slice's scheduling contract (FSD: only the app layer joins slices). */
class LinkedInDraftScheduling(private val postService: LinkedInPostService) : DraftScheduling {
    override suspend fun schedule(serverID: String, at: Instant, reviewConfirmed: Boolean, memberID: String, accessToken: String) =
        postService.schedule(serverID, at, reviewConfirmed, LinkedInPostCredential(accessToken, memberID)).toDraftSchedule()

    override suspend fun unschedule(serverID: String, memberID: String, accessToken: String) =
        postService.unschedule(serverID, LinkedInPostCredential(accessToken, memberID))

    override suspend fun scheduleStatus(scheduleID: String, memberID: String, accessToken: String) =
        postService.scheduleStatus(scheduleID, LinkedInPostCredential(accessToken, memberID)).toDraftSchedule()

    override suspend fun scheduledPosts(memberID: String, accessToken: String) =
        postService.scheduledPosts(LinkedInPostCredential(accessToken, memberID)).map { it.toDraftSchedule() }

    private fun ScheduledPostResult.toDraftSchedule() =
        DraftSchedule(scheduleId = scheduleId, draftId = draftId, status = status, scheduledAt = scheduledAt, message = message)
}
