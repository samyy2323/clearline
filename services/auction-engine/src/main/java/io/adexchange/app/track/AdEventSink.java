package io.adexchange.app.track;

import java.util.Map;

/** Where tracking events go after the tracker has judged them. Must never block or throw into the request path. */
public interface AdEventSink {

    void publish(AdEvent event);

    Map<String, Object> stats();
}
