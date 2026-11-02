package io.adexchange.app.campaign;

import io.adexchange.app.security.AuthFilter;

import io.adexchange.auction.model.Campaign;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/campaigns")
class CampaignController {

    record CampaignIn(
            @NotBlank @Size(max = 80) String name,
            String advertiserId,
            @Min(10_000) @Max(1_000_000_000L) long bidCpmMicros,          // $0.01 .. $1000 CPM
            @Min(100_000) @Max(10_000_000_000_000L) long budgetMicros,     // $0.10 .. $10M
            @Size(max = 50) Set<@Size(max = 8) String> geos,
            @Size(max = 10) Set<@Size(max = 16) String> devices,
            @Size(max = 50) Set<@Size(max = 32) String> segments,
            @Size(max = 50) Set<@Size(max = 32) String> categories,
            @Size(max = 24) List<@Min(0) @Max(23) Integer> hours,
            @Size(max = 10) List<@Pattern(regexp = "\\d{2,4}x\\d{2,4}") String> sizes,
            @Min(0) @Max(1000) int freqCap,
            long startMs,
            long endMs,
            boolean active) {}

    private final CampaignService service;

    CampaignController(CampaignService service) {
        this.service = service;
    }

    private static Set<String> nz(Set<String> s) {
        return s == null ? Set.of() : Set.copyOf(s);
    }

    private static Campaign toDomain(String id, String adv, CampaignIn in) {
        if (in.endMs() <= in.startMs()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endMs must be after startMs");
        int mask = 0;
        if (in.hours() != null) for (int h : in.hours()) mask |= 1 << h;
        Set<Campaign.Size> sizes = in.sizes() == null ? Set.of() : in.sizes().stream().map(s -> {
            String[] p = s.split("x");
            return new Campaign.Size(Integer.parseInt(p[0]), Integer.parseInt(p[1]));
        }).collect(Collectors.toSet());
        return new Campaign(id, adv, in.name(), in.bidCpmMicros(), in.budgetMicros(), nz(in.geos()), nz(in.devices()),
                nz(in.segments()), nz(in.categories()), mask, sizes, in.freqCap(), in.startMs(), in.endMs(), in.active());
    }

    static Map<String, Object> view(Campaign c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        m.put("advertiserId", c.advertiserId());
        m.put("name", c.name());
        m.put("bidCpmMicros", c.bidCpmMicros());
        m.put("budgetMicros", c.budgetMicros());
        m.put("geos", new TreeSet<>(c.geos()));
        m.put("devices", new TreeSet<>(c.devices()));
        m.put("segments", new TreeSet<>(c.segments()));
        m.put("categories", new TreeSet<>(c.categories()));
        List<Integer> hours = new ArrayList<>();
        for (int h = 0; h < 24; h++) if (c.hourMask() != 0 && (c.hourMask() & (1 << h)) != 0) hours.add(h);
        m.put("hours", hours);
        m.put("sizes", c.sizes().stream().map(s -> s.w() + "x" + s.h()).sorted().toList());
        m.put("freqCap", c.freqCap());
        m.put("startMs", c.startMs());
        m.put("endMs", c.endMs());
        m.put("active", c.active());
        return m;
    }

    private Campaign owned(String id, io.adexchange.auth.Jwt.Claims cl) {
        Campaign c = service.get(id);
        // 404 (not 403) for other tenants' campaigns so ids can't be probed
        if (c == null || (!cl.admin() && !c.advertiserId().equals(cl.advertiserId())))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return c;
    }

    @GetMapping
    List<Map<String, Object>> list(HttpServletRequest req) {
        io.adexchange.auth.Jwt.Claims cl = AuthFilter.claims(req);
        Collection<Campaign> src = cl.admin() ? service.all() : service.forAdvertiser(cl.advertiserId());
        return src.stream().sorted(Comparator.comparing(Campaign::id)).map(CampaignController::view).toList();
    }

    @GetMapping("/{id}")
    Map<String, Object> get(@PathVariable String id, HttpServletRequest req) {
        return view(owned(id, AuthFilter.claims(req)));
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CampaignIn in, HttpServletRequest req) {
        io.adexchange.auth.Jwt.Claims cl = AuthFilter.claims(req);
        String adv = cl.admin() ? (in.advertiserId() == null || in.advertiserId().isBlank() ? "adv-1" : in.advertiserId()) : cl.advertiserId();
        Campaign c = service.save(toDomain("c-" + UUID.randomUUID().toString().substring(0, 8), adv, in));
        return ResponseEntity.status(HttpStatus.CREATED).body(view(c));
    }

    @PutMapping("/{id}")
    Map<String, Object> update(@PathVariable String id, @Valid @RequestBody CampaignIn in, HttpServletRequest req) {
        Campaign existing = owned(id, AuthFilter.claims(req));
        return view(service.save(toDomain(id, existing.advertiserId(), in)));
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable String id, HttpServletRequest req) {
        owned(id, AuthFilter.claims(req));
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
