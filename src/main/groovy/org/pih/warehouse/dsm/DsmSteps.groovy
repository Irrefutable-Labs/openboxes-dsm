package org.pih.warehouse.dsm

import dsm.types.proto.ConnectReplyV1

/** What every DSM service here does the same way: bounded waiting, and reading a Connect reply. */
class DsmSteps {

    /** How long one DSM step may take before it is reported as not having happened (the DSM suites' own bound). */
    static final long STEP_TIMEOUT_MS = 240_000L

    static final long POLL_MS = 500L

    private DsmSteps() {
    }

    /** `probe`'s first non-null answer within the step bound, or a refusal naming `what` did not happen. */
    static <T> T until(String what, Closure<T> probe) {
        long deadline = System.currentTimeMillis() + STEP_TIMEOUT_MS
        while (true) {
            T found = probe()
            if (found != null) {
                return found
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new DsmCustodyException("${what}: not within ${STEP_TIMEOUT_MS / 1000} s")
            }
            Thread.sleep(POLL_MS)
        }
    }

    static ConnectReplyV1 reply(ConnectReplyV1 reply, ConnectReplyV1.ReplyCase wanted, String method) {
        if (reply.replyCase != wanted) {
            throw new DsmHostException(method, "the route answered ${reply.replyCase}, not ${wanted}")
        }
        return reply
    }
}
