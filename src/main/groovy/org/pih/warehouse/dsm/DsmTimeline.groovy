package org.pih.warehouse.dsm

import java.util.concurrent.atomic.AtomicLong

/**
 * What OpenBoxes itself did, for the overlay's WEB2 side: its ordinary writes, in memory, as they happened. It is a
 * display record of this process and carries no authority.
 */
class DsmTimeline {

    private static final int KEPT = 500
    private static final AtomicLong SEQ = new AtomicLong()
    private static final LinkedList<Map> ENTRIES = new LinkedList<>()

    private DsmTimeline() {
    }

    static void record(String what, String who, String request = null) {
        synchronized (ENTRIES) {
            ENTRIES.addLast([seq: SEQ.incrementAndGet(), what: what, who: who, request: request, at: System.currentTimeMillis()])
            if (ENTRIES.size() > KEPT) {
                ENTRIES.removeFirst()
            }
        }
    }

    static List<Map> since(long seq) {
        synchronized (ENTRIES) {
            return ENTRIES.findAll { Map entry -> (entry.seq as long) > seq }
        }
    }
}
