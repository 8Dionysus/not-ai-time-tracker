package dev.notai.tracker

class ScheduleDeliveryFixtureStore: ScheduleDeliveryStore {
    var persisted = ScheduleDeliveryState()
    var beforeWrite: ((ScheduleDeliveryState)->Unit)? = null
    var afterWrite: ((ScheduleDeliveryState)->Unit)? = null
    @Synchronized override fun <T> locked(block: ()->T) = block()
    override fun read() = codec.decodeFromString<ScheduleDeliveryState>(codec.encodeToString(persisted))
    override fun writeDurably(state: ScheduleDeliveryState) {
        beforeWrite?.invoke(state);persisted = codec.decodeFromString(codec.encodeToString(state));afterWrite?.invoke(state)
    }
}
class ScheduleDeliveryFixturePlatform: ScheduleDeliveryPlatform {
    var capability = ScheduleDeliveryAvailability()
    var pending: ScheduleAlarmBatch? = null
    val postCalls = mutableListOf<ScheduleReminderTicket>()
    val notices = mutableMapOf<String,ScheduleReminderTicket>()
    var prepareAction: ((ScheduleReminderTicket,ScheduleProjection)->Unit)? = null
    var postAction: ((ScheduleReminderTicket,ScheduleProjection)->Boolean)? = null
    override fun availability() = capability
    override fun schedule(batch: ScheduleAlarmBatch) { pending = batch }
    override fun cancelAlarm() { pending = null }
    override fun prepare(ticket: ScheduleReminderTicket,projection: ScheduleProjection): PreparedScheduleNotice {
        prepareAction?.invoke(ticket,projection)
        return PreparedScheduleNotice {
            postCalls += ticket
            val accepted = postAction?.invoke(ticket,projection) ?: true
            if(accepted) notices[ticket.identity] = ticket
            accepted
        }
    }
    override fun visible() = notices.values.toList()
    override fun cancelNotice(ticket: ScheduleReminderTicket) { notices.remove(ticket.identity) }
}
