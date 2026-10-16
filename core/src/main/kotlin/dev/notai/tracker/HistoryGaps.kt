package dev.notai.tracker

import java.util.TreeMap

enum class HistoryGapKind { UNRECORDED, PAUSE, RECOVERY_UNCERTAIN }
data class HistoryGap(val id: String,val start: Long,val end: Long,val kind: HistoryGapKind,
    val sessionIds: List<String>,val uncertainSegmentIds: List<String>)
/** Gaps in recording, never an inference of inactivity. Future time is excluded. */
class HistoryGapReview internal constructor(val token: Token,val asOf: Moment,val start: Long,val requestedEnd: Long,
    val observedEnd: Long,val gaps: List<HistoryGap>,val unplacedUncertainSegmentIds: List<String>)
object HistoryGaps {
    fun inspect(data: Dataset,now: Moment,start: Long,end: Long): HistoryGapReview {
        validateMoment(now);validate(data)
        ensure(start >= 0 && end > start && end <= MAX_TIME,"Invalid gap window",ErrorCode.INVALID_INTERVAL)
        val finish = minOf(end,now.wall)
        if(finish <= start) return HistoryGapReview(token(data),now,start,end,finish,emptyList(),emptyList())
        data class Edge(val category: Int,val id: String,val sessionId: String,val delta: Int)
        val events = TreeMap<Long,MutableList<Edge>>()
        events[start] = mutableListOf();events[finish] = mutableListOf()
        fun span(from: Long,to: Long,category: Int,id: String,session: String) {
            val a=maxOf(start,from);val b=minOf(finish,to);if(b<=a) return
            events.getOrPut(a) { mutableListOf() } += Edge(category,id,session,1)
            events.getOrPut(b) { mutableListOf() } += Edge(category,id,session,-1)
        }
        val unplaced = mutableListOf<String>()
        val materialized=data.segments.map { materialize(it,now) }
        for(s in materialized) {
            val stop=s.end ?: s.observedUntil ?: now.wall
            if(s.uncertain) {
                if(stop <= s.start) unplaced += s.id else span(s.start,stop,1,s.id,s.sessionId)
            } else if(s.durationMs>0) span(s.start,stop,0,s.id,s.sessionId)
        }
        val sessions=data.sessions.associateBy { it.id }
        for((id,own) in data.segments.groupBy { it.sessionId }) {
            val rows=own.sortedWith(compareBy({it.start},{it.id}))
            rows.zipWithNext().forEach { (a,b) -> a.end?.let { span(it,b.start,2,id,id) } }
            if(sessions.getValue(id).status==SessionStatus.PAUSED) rows.lastOrNull()?.end?.let { span(it,now.wall,2,id,id) }
        }
        val active=Array(3) { mutableMapOf<String,Pair<Int,String>>() };val result=mutableListOf<HistoryGap>()
        val points=events.keys.toList()
        for(i in 0 until points.lastIndex) {
            val at=points[i];val next=points[i+1]
            for(e in events.getValue(at)) {
                val count=(active[e.category][e.id]?.first ?: 0)+e.delta
                if(count==0) active[e.category].remove(e.id) else active[e.category][e.id]=count to e.sessionId
            }
            if(active[0].isNotEmpty()) continue
            val category=if(active[1].isNotEmpty()) 1 else if(active[2].isNotEmpty()) 2 else 3
            val kind=when(category) { 1->HistoryGapKind.RECOVERY_UNCERTAIN;2->HistoryGapKind.PAUSE;else->HistoryGapKind.UNRECORDED }
            val source=if(category<3) active[category] else emptyMap()
            val ids=source.values.map { it.second }.distinct().sorted();val uncertain=if(category==1) source.keys.sorted() else emptyList()
            fun gap(a: Long,b: Long)=HistoryGap(payloadHash(listOf(data.datasetId,a.toString(),b.toString(),kind.name)+ids+uncertain),a,b,kind,frozenList(ids),frozenList(uncertain))
            val previous=result.lastOrNull()
            if(previous?.end==at && previous.kind==kind && previous.sessionIds==ids && previous.uncertainSegmentIds==uncertain)
                result[result.lastIndex]=gap(previous.start,next)
            else result += gap(at,next)
        }
        return HistoryGapReview(token(data),now,start,end,finish,frozenList(result),frozenList(unplaced.sorted()))
    }
}
