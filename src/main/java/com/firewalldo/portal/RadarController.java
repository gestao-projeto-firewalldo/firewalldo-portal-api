package com.firewalldo.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.firewalldo.portal.SessionService.Actor;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/settings/radar")
class RadarController {
    private final RadarService radar;
    RadarController(RadarService radar){this.radar=radar;}
    @GetMapping Map<String,Object> status(@RequestAttribute Actor actor){actor.requireAdmin();return radar.status();}
    @PutMapping Map<String,Object> save(@RequestAttribute Actor actor,@RequestBody JsonNode body){actor.requireAdmin();radar.save(body);return radar.status();}
    @PostMapping("/collect") @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String,Object> collect(@RequestAttribute Actor actor){actor.requireAdmin();return Map.of("runId",radar.submit(true));}
}
