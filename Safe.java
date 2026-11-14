package com.ezvisuals;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps one broken feature from crashing the game: logs the first error of each place only. */
public final class Safe {
    private Safe() {}
    private static final Logger LOG = LoggerFactory.getLogger("ezvisuals");
    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

    public static void err(String where, Throwable t) {
        if (SEEN.add(where)) LOG.error("[ezvisuals] '{}' failed, it is skipped from now on (see stack trace)", where, t);
    }
}
