package com.a4a.g8api.services

import com.a4a.g8api.database.MagicLinkService
import com.a4a.g8api.database.SessionService
import com.a4a.g8api.database.SubscriptionService
import com.a4a.g8api.database.UsersService
import com.a4a.g8api.plugins.dbQuery
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.slf4j.LoggerFactory

/**
 * Daily housekeeping for the auth/subscription tables.
 *
 * Removes:
 * - Magic links expired more than 7 days ago.
 * - Sessions expired or revoked more than 7 days ago.
 * - Orphan users: created more than 30 days ago, never produced a non-revoked
 *   session and never subscribed (hard-deleted, distinct from RGPD soft-deletes).
 * - Webhook events older than 90 days.
 *
 * Idempotent: running it twice in a row is harmless. Each delete is its own
 * transaction; we don't need a single big tx because the operations don't
 * depend on each other's intermediate state.
 */
class CleanupService {

    private val log = LoggerFactory.getLogger("cleanup")

    suspend fun runCleanup(now: kotlinx.datetime.Instant = Clock.System.now()): CleanupReport {
        val sevenDaysAgo = now.minus(7, DateTimeUnit.DAY, TimeZone.UTC).toLocalDateTime(TimeZone.UTC)
        val thirtyDaysAgo = now.minus(30, DateTimeUnit.DAY, TimeZone.UTC).toLocalDateTime(TimeZone.UTC)
        val ninetyDaysAgo = now.minus(90, DateTimeUnit.DAY, TimeZone.UTC).toLocalDateTime(TimeZone.UTC)

        val magicLinksDeleted = dbQuery {
            MagicLinkService.MagicLinks.deleteWhere {
                Op.build { MagicLinkService.MagicLinks.expiresAt less sevenDaysAgo }
            }
        }

        val sessionsDeleted = dbQuery {
            SessionService.Sessions.deleteWhere {
                Op.build {
                    (SessionService.Sessions.expiresAt less sevenDaysAgo) or
                        (SessionService.Sessions.revokedAt.isNotNull() and
                            (SessionService.Sessions.revokedAt less sevenDaysAgo))
                }
            }
        }

        val orphansDeleted = dbQuery {
            // Users with at least one non-revoked session — these are active.
            val usersWithLiveSession = SessionService.Sessions
                .selectAll()
                .where { Op.build { SessionService.Sessions.revokedAt.isNull() } }
                .map { it[SessionService.Sessions.userId] }
                .toSet()

            // Users that ever subscribed (regardless of current status).
            val usersWithSubscription = SubscriptionService.Subscriptions
                .selectAll()
                .map { it[SubscriptionService.Subscriptions.userId] }
                .toSet()

            val excluded = usersWithLiveSession + usersWithSubscription

            UsersService.Users.deleteWhere {
                Op.build {
                    (UsersService.Users.createdAt less thirtyDaysAgo) and
                        UsersService.Users.deletedAt.isNull() and
                        (UsersService.Users.id notInList excluded.toList())
                }
            }
        }

        val webhooksDeleted = dbQuery {
            SubscriptionService.WebhookEvents.deleteWhere {
                Op.build { SubscriptionService.WebhookEvents.processedAt less ninetyDaysAgo }
            }
        }

        val report = CleanupReport(
            magicLinksDeleted = magicLinksDeleted,
            sessionsDeleted = sessionsDeleted,
            orphanUsersDeleted = orphansDeleted,
            webhookEventsDeleted = webhooksDeleted,
        )
        log.info("cleanup.completed: $report")
        return report
    }
}

data class CleanupReport(
    val magicLinksDeleted: Int,
    val sessionsDeleted: Int,
    val orphanUsersDeleted: Int,
    val webhookEventsDeleted: Int,
)
