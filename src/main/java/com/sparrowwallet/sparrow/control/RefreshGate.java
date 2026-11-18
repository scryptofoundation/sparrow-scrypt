package com.sparrowwallet.sparrow.control;

/**
 * When a view that reads in the background starts a read: only while it is shown, one read at a time, a refresh asked
 * for during a read run once after it, and a read abandoned when the view is hidden (its result is dropped). A refresh
 * asked for while hidden makes the view stale, and it reads when shown again. Each read gets a ticket; only the current
 * ticket's result is used. Not thread safe: used on the FX thread.
 */
final class RefreshGate {
    private boolean shown;
    private boolean running;
    private boolean again;
    private boolean stale = true;
    private int generation;

    /** A refresh is wanted: the ticket of a read to start now, or -1. */
    int request() {
        if(!shown) {
            stale = true;
            return -1;
        }
        if(running) {
            again = true;
            return -1;
        }
        return start();
    }

    /** The read with this ticket ended: true when its result is current and should be shown. */
    boolean finish(int ticket) {
        if(!running || ticket != generation) {
            return false;
        }
        running = false;
        return true;
    }

    /** After a read ended: the ticket of the read asked for meanwhile, or -1. */
    int next() {
        if(again && shown && !running) {
            return start();
        }
        again = false;
        return -1;
    }

    /** The view is shown: the ticket of a read to start now when it is stale, or -1. */
    int show() {
        shown = true;
        return stale && !running ? start() : -1;
    }

    /** The view is hidden: true when a read was running and is now abandoned (the caller cancels it). */
    boolean hide() {
        shown = false;
        if(!running) {
            return false;
        }
        running = false;
        again = false;
        stale = true;
        generation++;
        return true;
    }

    private int start() {
        running = true;
        stale = false;
        again = false;
        return ++generation;
    }
}
