package com.jellemax.detour.presentation

import com.jellemax.detour.data.Group
import com.jellemax.detour.data.GroupMember
import com.jellemax.detour.data.RiderId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pure mapping behind the Circles list screen: the member-line strings
 * CircleListSection has always joined by hand, and the invited/accepted
 * split. See CirclesStore.kt for the mutable store this reads circles from,
 * and CirclesListPresenter for the thin load kick that feeds it.
 */
class CirclesListStateTest {

    private val me = RiderId("me")

    private fun member(
        id: String,
        username: String,
        status: String = "accepted",
        sharing: Boolean = true,
    ) = GroupMember(id = RiderId(id), username = username, status = status, sharing = sharing)

    private fun circle(
        id: String = "c1",
        name: String = "Family",
        status: String = "accepted",
        members: List<GroupMember> = emptyList(),
    ) = Group(id = id, name = name, kind = "circle", status = status, members = members)

    @Test fun memberLineJoinsUsernamesWithACommaSpaceSeparatorInMemberOrder() {
        val state = circlesListStateFrom(
            listOf(circle(members = listOf(member("1", "ada"), member("2", "bob")))),
            riderId = me,
        )
        assertEquals("ada, bob", state.accepted.single().memberLine)
    }

    @Test fun anInvitedMemberIsMarkedWithInvitedSuffixExactlyAsToday() {
        val state = circlesListStateFrom(
            listOf(circle(members = listOf(member("1", "ada", status = "invited")))),
            riderId = me,
        )
        assertEquals("ada (invited)", state.accepted.single().memberLine)
    }

    @Test fun onlyInvitedMembersGetTheSuffixNotAcceptedOnes() {
        val state = circlesListStateFrom(
            listOf(circle(members = listOf(member("1", "ada", status = "accepted"), member("2", "bob", status = "invited")))),
            riderId = me,
        )
        assertEquals("ada, bob (invited)", state.accepted.single().memberLine)
    }

    @Test fun aSingleMemberCircleReadsAsJustThatUsername() {
        val state = circlesListStateFrom(
            listOf(circle(members = listOf(member("1", "ada")))),
            riderId = me,
        )
        assertEquals("ada", state.accepted.single().memberLine)
    }

    @Test fun anAcceptedCircleLandsInTheAcceptedListNotInvited() {
        val state = circlesListStateFrom(listOf(circle(status = "accepted")), riderId = me)
        assertEquals(1, state.accepted.size)
        assertTrue(state.invited.isEmpty())
        assertEquals(false, state.accepted.single().isInvited)
    }

    @Test fun anInvitedCircleLandsInTheInvitedListNotAccepted() {
        val state = circlesListStateFrom(listOf(circle(status = "invited")), riderId = me)
        assertEquals(1, state.invited.size)
        assertTrue(state.accepted.isEmpty())
        assertEquals(true, state.invited.single().isInvited)
    }

    @Test fun sharingReflectsTheRidersOwnMembershipNotAnyMembers() {
        val state = circlesListStateFrom(
            listOf(
                circle(
                    members = listOf(
                        member("other", "bob", sharing = true),
                        member("me", "ada", sharing = false),
                    ),
                ),
            ),
            riderId = me,
        )
        assertEquals(false, state.accepted.single().sharing)
    }

    @Test fun sharingIsTrueWhenTheRidersOwnRowHasSharingOn() {
        val state = circlesListStateFrom(
            listOf(
                circle(
                    members = listOf(
                        member("other", "bob", sharing = false),
                        member("me", "ada", sharing = true),
                    ),
                ),
            ),
            riderId = me,
        )
        assertEquals(true, state.accepted.single().sharing)
    }

    @Test fun sharingIsFalseWhenTheRiderHasNoMembershipRowAtAll() {
        val state = circlesListStateFrom(
            listOf(circle(members = listOf(member("other", "bob", sharing = true)))),
            riderId = me,
        )
        assertEquals(false, state.accepted.single().sharing)
    }

    @Test fun idAndNamePassThroughUnchanged() {
        val state = circlesListStateFrom(listOf(circle(id = "circle-42", name = "Roommates")), riderId = me)
        val row = state.accepted.single()
        assertEquals("circle-42", row.id)
        assertEquals("Roommates", row.name)
    }
}
