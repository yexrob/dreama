package com.limelight.local.dlna

/** A NOTIFY for one subscriber: its callback URLs in order, tried until one takes it. */
class Delivery(val sid: String, val messages: List<NotifyMessage>)

/**
 * The receiver's HTTP side: descriptions, control calls and subscriptions,
 * as a pure function of the request so it can be tested without sockets.
 */
class DlnaHandler(
    private val renderer: MediaRenderer,
    private val subs: Subscriptions,
    private val deviceXml: () -> String,
) {
    /** The response, and the subscription whose first NOTIFY is due once the response is out. */
    class Handled(val response: HttpResponse, val subscribed: Subscription? = null)

    fun handle(req: HttpRequest, now: Long): Handled {
        val path = Http.pathOf(req.path)
        return when (req.method) {
            "GET", "HEAD" -> Handled(get(path))
            "POST", "M-POST" -> Handled(control(req, path, now))
            "SUBSCRIBE" -> subscribe(req, path, now)
            "UNSUBSCRIBE" -> Handled(unsubscribe(req))
            else -> Handled(HttpResponse.empty(405, "Method Not Allowed"))
        }
    }

    private fun get(path: String): HttpResponse {
        if (path == "/" || path.equals(Descriptions.DESCRIPTION_PATH, true) || path.endsWith("description.xml", true)) {
            return HttpResponse.xml(deviceXml())
        }
        val service = UpnpService.entries.firstOrNull { path.equals(it.scpdPath, true) }
            ?: return HttpResponse.empty(404, "Not Found")
        return HttpResponse.xml(Descriptions.scpdXml(service))
    }

    private fun control(req: HttpRequest, path: String, now: Long): HttpResponse {
        // M-POST names the header after its MAN namespace ("01-SOAPACTION")
        val soapAction = req.header("soapaction")
            ?: req.headers.entries.firstOrNull { it.key.endsWith("-soapaction") }?.value
        val call = Soap.parse(req.bodyText, soapAction) ?: return fault(401, "Invalid Action")
        val service = UpnpService.forPath(path)
            ?: UpnpService.entries.firstOrNull { call.serviceType?.startsWith(it.type.substringBeforeLast(':')) == true }
            ?: return HttpResponse.empty(404, "Not Found")
        return when (val r = renderer.control(service, call, now)) {
            is SoapResult.Ok -> HttpResponse(200, "OK", listOf(XML, "EXT" to ""),
                Soap.response(service.type, call.action, r.out).toByteArray(Charsets.UTF_8))
            is SoapResult.Fault -> fault(r.code, r.description)
        }
    }

    private fun fault(code: Int, text: String) = HttpResponse(500, "Internal Server Error", listOf(XML, "EXT" to ""),
        Soap.fault(code, text).toByteArray(Charsets.UTF_8))

    private fun subscribe(req: HttpRequest, path: String, now: Long): Handled {
        val service = UpnpService.forPath(path) ?: return Handled(HttpResponse.empty(404, "Not Found"))
        val timeout = Gena.parseTimeout(req.header("timeout"))
        val sid = req.header("sid")
        if (!sid.isNullOrBlank()) {
            val s = subs.renew(sid, timeout, now) ?: return Handled(PRECONDITION)
            return Handled(HttpResponse.empty(200, "OK", accepted(s)))
        }
        val nt = req.header("nt")
        val callbacks = Gena.parseCallbacks(req.header("callback"))
        if (callbacks.isEmpty() || (nt != null && !nt.trim().equals("upnp:event", true))) {
            return Handled(PRECONDITION)
        }
        val s = subs.subscribe(service, callbacks, timeout, now)
        return Handled(HttpResponse.empty(200, "OK", accepted(s)), s)
    }

    private fun accepted(s: Subscription) = listOf("SID" to s.sid, "TIMEOUT" to Gena.timeoutHeader(s.timeout))

    private fun unsubscribe(req: HttpRequest): HttpResponse {
        val sid = req.header("sid") ?: return PRECONDITION
        return if (subs.unsubscribe(sid)) HttpResponse.empty(200, "OK") else PRECONDITION
    }

    fun eventBody(service: UpnpService, vars: List<EventVar>): String = when (service) {
        UpnpService.AVTransport -> Gena.propertySet(listOf("LastChange" to Gena.lastChange(Gena.AVT_NS, vars)))
        UpnpService.RenderingControl -> Gena.propertySet(listOf("LastChange" to Gena.lastChange(Gena.RCS_NS, vars)))
        UpnpService.ConnectionManager -> Gena.propertySet(listOf("SourceProtocolInfo" to "",
            "SinkProtocolInfo" to Descriptions.SINK_PROTOCOL_INFO, "CurrentConnectionIDs" to "0"))
    }

    /** The first NOTIFY for a new subscriber, with everything evented; null if it is already gone. */
    fun initial(s: Subscription): Delivery? {
        val seq = subs.claimInitial(s.sid) ?: return null
        return delivery(s, seq, eventBody(s.service, renderer.eventState(s.service)))
    }

    /** NOTIFYs for every live subscriber of [service] about [vars]. */
    fun deliveries(service: UpnpService, vars: List<EventVar>, now: Long): List<Delivery> {
        val targets = subs.forEvent(service, now)
        if (targets.isEmpty()) {
            return emptyList()
        }
        val body = eventBody(service, vars)
        return targets.mapNotNull { (s, seq) -> delivery(s, seq, body) }
    }

    private fun delivery(s: Subscription, seq: Long, body: String): Delivery? {
        val messages = s.callbacks.mapNotNull { Gena.notify(it, s.sid, seq, body) }
        return if (messages.isEmpty()) null else Delivery(s.sid, messages)
    }

    private companion object {
        val XML = "Content-Type" to "text/xml; charset=\"utf-8\""
        val PRECONDITION = HttpResponse.empty(412, "Precondition Failed")
    }
}
