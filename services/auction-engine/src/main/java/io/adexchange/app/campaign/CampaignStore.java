package io.adexchange.app.campaign;

import io.adexchange.auction.model.Campaign;

import java.util.Collection;
import java.util.List;

/**
 * System of record for campaigns plus the outbox that carries change events to the auction engines' index.
 * Lite: in memory, relay applies events to the local index. Full: Postgres, relay publishes to a compacted Kafka topic.
 */
public interface CampaignStore {

    Campaign save(Campaign c);

    boolean delete(String id);

    Campaign get(String id);

    Collection<Campaign> all();

    List<Campaign> forAdvertiser(String advertiserId);

    /** Replaces the whole set (demo seeding / simulator reset); emits delete events for anything removed. */
    void replaceAll(Collection<Campaign> cs);

    /** Change events written but not yet published. */
    int outboxDepth();

    /** Publishes pending change events; returns how many were published. */
    int relay();
}
