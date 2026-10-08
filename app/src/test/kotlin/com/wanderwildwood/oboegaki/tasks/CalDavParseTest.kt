package com.wanderwildwood.oboegaki.tasks

import org.junit.Assert.assertEquals
import org.junit.Test

class CalDavParseTest {

    private val home = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav" xmlns:cs="http://calendarserver.org/ns/" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.com/ns">
 <d:response><d:href>/remote.php/dav/calendars/ada/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/calendars/ada/personal/</d:href><d:propstat><d:prop><d:displayname>Personal</d:displayname><d:resourcetype><d:collection/><cal:calendar/></d:resourcetype><cal:supported-calendar-component-set><cal:comp name="VEVENT"/><cal:comp name="VTODO"/></cal:supported-calendar-component-set><cs:getctag>http://sabre.io/ns/sync/7</cs:getctag><d:current-user-privilege-set><d:privilege><d:read/></d:privilege><d:privilege><d:write/></d:privilege></d:current-user-privilege-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/calendars/ada/events/</d:href><d:propstat><d:prop><d:displayname>Events</d:displayname><d:resourcetype><d:collection/><cal:calendar/></d:resourcetype><cal:supported-calendar-component-set><cal:comp name="VEVENT"/></cal:supported-calendar-component-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/calendars/ada/groceries_shared_by_tomas/</d:href><d:propstat><d:prop><d:displayname>Groceries (Tomas)</d:displayname><d:resourcetype><d:collection/><cal:calendar/></d:resourcetype><cal:supported-calendar-component-set><cal:comp name="VTODO"/></cal:supported-calendar-component-set><d:current-user-privilege-set><d:privilege><d:read/></d:privilege></d:current-user-privilege-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/calendars/ada/trashbin/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><nc:trash-bin/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/calendars/ada/inbox/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><cal:schedule-inbox/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
</d:multistatus>"""

    @Test
    fun onlyCalendarsThatHoldTasks() {
        val lists = parseCalendars(home.byteInputStream())
        assertEquals(
            listOf(
                RemoteList("/remote.php/dav/calendars/ada/personal/", "Personal", false, "http://sabre.io/ns/sync/7"),
                RemoteList("/remote.php/dav/calendars/ada/groceries_shared_by_tomas/", "Groceries (Tomas)", true, null),
            ),
            lists,
        )
    }

    @Test
    fun aReportsTasksWithCrlfPutBack() {
        val body = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav">
<d:response><d:href>/remote.php/dav/calendars/ada/personal/a.ics</d:href><d:propstat><d:prop><d:getetag>"abc"</d:getetag><cal:calendar-data>BEGIN:VCALENDAR
BEGIN:VTODO
UID:a
END:VTODO
END:VCALENDAR
</cal:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
</d:multistatus>"""
        val item = parseReport(body.byteInputStream()).single()
        assertEquals("\"abc\"", item.etag)
        assertEquals("BEGIN:VCALENDAR\r\nBEGIN:VTODO\r\nUID:a\r\nEND:VTODO\r\nEND:VCALENDAR\r\n", item.text)
    }
}
