package io.adexchange.app.stats;

import io.adexchange.app.campaign.CampaignService;
import io.adexchange.app.config.ExchangeProperties;
import io.adexchange.app.fault.FaultInjector;
import io.adexchange.app.security.AuthFilter;

import io.adexchange.app.sim.SimulatorService;
import io.adexchange.app.track.EventStats;
import io.adexchange.auction.model.Campaign;
import io.adexchange.auction.scoring.PctrModel;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
class StatsController {
    record StartIn(String scenario, Double rate, Double botFraction, Double dupFraction, Double floodFraction,
                   Integer campaigns, Long seed, Double budgetScale) {}

    record FaultIn(Boolean redisDown, Long latencyMs) {}

    private final StatsService stats;
    private final SimulatorService sim;
    private final FaultInjector faults;
    private final CampaignService campaigns;
    private final EventStats eventStats;
    private final PctrModel model;
    private final ExchangeProperties props;

    StatsController(StatsService stats, SimulatorService sim, FaultInjector faults, CampaignService campaigns,
                    EventStats eventStats, PctrModel model, ExchangeProperties props) {
        this.stats = stats;
        this.sim = sim;
        this.faults = faults;
        this.campaigns = campaigns;
        this.eventStats = eventStats;
        this.model = model;
        this.props = props;
    }

    private static io.adexchange.auth.Jwt.Claims admin(HttpServletRequest r) {
        io.adexchange.auth.Jwt.Claims c = AuthFilter.claims(r);
        if (!c.admin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin only");
        return c;
    }

    /** Unauthenticated, aggregate-only: safe to show on a public landing page. */
    @GetMapping("/public/summary")
    Map<String, Object> summary() {
        Map<String, Object> s = stats.latest(true, "");
        if (s.isEmpty()) return Map.of();
        return Map.of("rps", s.get("rps"), "latencyMs", s.get("latencyMs"), "noBidRate", s.get("noBidRate"),
                "overspendPct", s.get("overspendPct"), "simulating", ((Map<?, ?>) s.get("sim")).get("running"));
    }

    @GetMapping(value = "/stream", produces = "text/event-stream")
    SseEmitter stream(HttpServletRequest r) {
        io.adexchange.auth.Jwt.Claims c = AuthFilter.claims(r);
        return stats.subscribe(c.admin(), c.advertiserId());
    }

    @GetMapping("/snapshot")
    Map<String, Object> snapshot(HttpServletRequest r) {
        io.adexchange.auth.Jwt.Claims c = AuthFilter.claims(r);
        return stats.latest(c.admin(), c.advertiserId());
    }

    @GetMapping("/timeseries")
    List<Map<String, Object>> timeseries(HttpServletRequest r) {
        admin(r);
        return stats.history();
    }

    @GetMapping("/campaigns/{id}/pacing")
    List<double[]> pacing(@PathVariable String id, HttpServletRequest r) {
        io.adexchange.auth.Jwt.Claims c = AuthFilter.claims(r);
        Campaign camp = campaigns.get(id);
        if (camp == null || (!c.admin() && !camp.advertiserId().equals(c.advertiserId())))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return stats.pacingSeries(id);
    }

    @GetMapping("/fraud/recent")
    List<EventStats.Flag> fraud(HttpServletRequest r) {
        admin(r);
        return eventStats.recentFlags();
    }

    @GetMapping("/sim/presets")
    List<SimulatorService.Config> presets(HttpServletRequest r) {
        admin(r);
        return List.of("steady", "ramp", "spike", "soak", "chaos", "compressed-day").stream().map(SimulatorService::preset).toList();
    }

    @PostMapping("/sim/start")
    Map<String, Object> start(@RequestBody StartIn in, HttpServletRequest r) {
        admin(r);
        SimulatorService.Config d = SimulatorService.preset(in.scenario() == null ? "steady" : in.scenario());
        double rate = in.rate() == null ? d.rate() : Math.max(1, Math.min(20_000, in.rate()));
        return sim.start(new SimulatorService.Config(d.scenario(), rate,
                clamp(in.botFraction(), d.botFraction()), clamp(in.dupFraction(), d.dupFraction()), clamp(in.floodFraction(), d.floodFraction()),
                in.campaigns() == null ? d.campaigns() : Math.max(6, Math.min(200, in.campaigns())),
                in.seed() == null ? d.seed() : in.seed(), in.budgetScale() == null ? d.budgetScale() : Math.max(0.01, Math.min(100, in.budgetScale()))));
    }

    private static double clamp(Double v, double def) {
        return v == null ? def : Math.max(0, Math.min(1, v));
    }

    @PostMapping("/sim/stop")
    Map<String, Object> stop(HttpServletRequest r) {
        admin(r);
        sim.stop();
        return sim.status();
    }

    @GetMapping("/sim/status")
    Map<String, Object> status(HttpServletRequest r) {
        admin(r);
        return sim.status();
    }

    @PostMapping("/sim/faults")
    FaultInjector.State faults(@RequestBody FaultIn in, HttpServletRequest r) {
        admin(r);
        if (in.redisDown() != null) faults.redisDown.set(in.redisDown());
        if (in.latencyMs() != null) faults.extraLatencyMs.set(Math.max(0, Math.min(2000, in.latencyMs())));
        return faults.state();
    }

    /** Hot-reload pCTR weights produced by ml/ (also polled every 5 s). */
    @PostMapping("/model/reload")
    Map<String, Object> reload(HttpServletRequest r) throws Exception {
        admin(r);
        boolean loaded = model.reloadIfChanged(Path.of(props.modelPath()));
        return Map.of("loaded", loaded, "version", model.version(), "source", model.source());
    }

    @Scheduled(fixedDelay = 5000)
    void pollModel() {
        try {
            model.reloadIfChanged(Path.of(props.modelPath()));
        } catch (Exception ignored) {
            // bad file: keep serving the previous weights
        }
    }
}
