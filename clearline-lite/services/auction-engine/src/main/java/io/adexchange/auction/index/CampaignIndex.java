package io.adexchange.auction.index;

import io.adexchange.auction.model.BidRequest;
import io.adexchange.auction.model.Campaign;

import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Immutable in-memory inverted index (geo, device, segment, category, daypart) over dense campaign ordinals.
 * Each posting list already includes wildcard campaigns so a query is a handful of bitset ANDs with no
 * hashing of campaign ids and no network. A new snapshot is swapped in atomically by {@link IndexHolder}.
 */
public final class CampaignIndex {

    private final Campaign[] byOrdinal;
    private final Map<String, BitSet> geo = new HashMap<>(), device = new HashMap<>(),
            category = new HashMap<>(), segment = new HashMap<>();
    private final BitSet wildGeo = new BitSet(), wildDevice = new BitSet(),
            wildCategory = new BitSet(), wildSegment = new BitSet();
    private final BitSet[] hour = new BitSet[24];

    private CampaignIndex(Collection<Campaign> campaigns) {
        this.byOrdinal = campaigns.stream().filter(Campaign::active).toArray(Campaign[]::new);
        for (int h = 0; h < 24; h++) hour[h] = new BitSet();
        for (int i = 0; i < byOrdinal.length; i++) {
            Campaign c = byOrdinal[i];
            post(geo, wildGeo, c.geos(), i);
            post(device, wildDevice, c.devices(), i);
            post(category, wildCategory, c.categories(), i);
            post(segment, wildSegment, c.segments(), i);
            for (int h = 0; h < 24; h++) if (c.servesHour(h)) hour[h].set(i);
        }
        // fold wildcards into every keyed posting list so queries need no OR for geo/device/category
        for (var m : java.util.List.of(Map.entry(geo, wildGeo), Map.entry(device, wildDevice), Map.entry(category, wildCategory)))
            m.getKey().values().forEach(b -> b.or(m.getValue()));
    }

    private static void post(Map<String, BitSet> map, BitSet wild, Set<String> keys, int ord) {
        if (keys.isEmpty()) {
            wild.set(ord);
            return;
        }
        for (String k : keys) map.computeIfAbsent(k, x -> new BitSet()).set(ord);
    }

    public static CampaignIndex build(Collection<Campaign> campaigns) {
        return new CampaignIndex(campaigns);
    }

    public int size() {
        return byOrdinal.length;
    }

    public Campaign campaign(int ordinal) {
        return byOrdinal[ordinal];
    }

    /** Candidate ordinals for the request (targeting only; flight/budget/pacing are checked later). */
    public BitSet candidates(BidRequest r, int hourOfDay) {
        BitSet res = (BitSet) hour[hourOfDay].clone();
        res.and(geo.getOrDefault(r.geo(), wildGeo));
        res.and(device.getOrDefault(r.device(), wildDevice));
        res.and(category.getOrDefault(r.siteCategory(), wildCategory));
        if (!res.isEmpty()) {
            BitSet seg = (BitSet) wildSegment.clone();
            for (int i = 0, n = r.segments().size(); i < n; i++) {
                BitSet b = segment.get(r.segments().get(i));
                if (b != null) seg.or(b);
            }
            res.and(seg);
        }
        return res;
    }
}
