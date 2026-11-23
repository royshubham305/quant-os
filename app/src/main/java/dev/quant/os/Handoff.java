package dev.quant.os;

import android.net.Uri;

/** The part after "quant-open:" / "quant-browse:" — commas and all. */
final class Handoff {

    private Handoff() {
    }

    static String target(Uri u) {
        String ssp = u == null ? null : u.getSchemeSpecificPart();
        return ssp == null ? "" : ssp;
    }
}
