package dev.quant.os;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Host blocklist for the in-app browser and the native page surface.
 * A request never leaves the device when its host matches an entry or is a
 * subdomain of one. This is a static list: no updates, no network fetch.
 */
final class AdBlock {

    private static final Set<String> DOMAINS = new HashSet<>();

    static {
        String[] hosts = {
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "google-analytics.com", "googletagmanager.com", "googletagservices.com",
            "adservice.google.com", "pagead2.googlesyndication.com", "ads.google.com",
            "analytics.google.com", "adwords.google.com", "amazon-adsystem.com",
            "adsystem.amazon.com", "assoc-amazon.com", "adnxs.com", "adnxs-simple.com",
            "adsrvr.org", "rubiconproject.com", "pubmatic.com", "openx.net",
            "casalemedia.com", "criteo.com", "criteo.net", "taboola.com",
            "outbrain.com", "revcontent.com", "mgid.com", "zedo.com",
            "advertising.com", "adform.net", "smartadserver.com", "yieldmo.com",
            "sharethrough.com", "teads.tv", "spotxchange.com", "spotx.tv",
            "tremorhub.com", "bidswitch.net", "contextweb.com", "gumgum.com",
            "indexww.com", "innovid.com", "lijit.com", "sovrn.com", "triplelift.com",
            "33across.com", "1rx.io", "360yield.com", "adcolony.com", "applovin.com",
            "chartboost.com", "unityads.unity3d.com", "vungle.com", "ironsrc.com",
            "supersonicads.com", "tapjoy.com", "mopub.com", "inmobi.com",
            "startapp.com", "adcash.com", "propellerads.com", "popads.net",
            "popcash.net", "exoclick.com", "juicyads.com", "trafficjunky.com",
            "adsterra.com", "hilltopads.net", "clickadu.com", "onclickads.net",
            "adf.ly", "shorte.st", "scorecardresearch.com", "quantserve.com",
            "comscore.com", "nielsen.com", "omtrdc.net", "demdex.net",
            "everesttech.net", "bluekai.com", "krxd.net", "exelator.com", "agkn.com",
            "mathtag.com", "turn.com", "tapad.com", "hotjar.com", "fullstory.com",
            "mouseflow.com", "crazyegg.com", "luckyorange.com", "inspectlet.com",
            "clicktale.net", "segment.io", "segment.com", "mixpanel.com",
            "amplitude.com", "heap.io", "heapanalytics.com", "kissmetrics.com",
            "branch.io", "appsflyer.com", "adjust.com", "kochava.com", "newrelic.com",
            "nr-data.net", "bugsnag.com", "sentry-cdn.com", "facebook.net",
            "connect.facebook.net", "pixel.facebook.com", "ads-twitter.com",
            "static.ads-twitter.com", "analytics.twitter.com", "ads.linkedin.com",
            "px.ads.linkedin.com", "snap.licdn.com", "ads.pinterest.com",
            "trk.pinterest.com", "analytics.tiktok.com", "ads.tiktok.com",
            "business-api.tiktok.com", "an.yandex.ru", "mc.yandex.ru", "adroll.com",
            "clarity.ms", "moatads.com", "ads.yahoo.com", "adtech.yahoo.com",
            "flashtalking.com", "serving-sys.com", "sizmek.com", "adentifi.com",
            "adsafeprotected.com", "doubleverify.com", "iasds01.com",
            "adsymptotic.com", "rlcdn.com", "crwdcntrl.net"
        };
        for (String host : hosts) DOMAINS.add(host);
    }

    private AdBlock() {
    }

    static boolean blocked(String host) {
        if (host == null || host.isEmpty()) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (DOMAINS.contains(h)) return true;
        int dot = h.indexOf('.');
        while (dot >= 0 && dot + 1 < h.length()) {
            if (DOMAINS.contains(h.substring(dot + 1))) return true;
            dot = h.indexOf('.', dot + 1);
        }
        return false;
    }
}
