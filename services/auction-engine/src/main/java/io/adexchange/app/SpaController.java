package io.adexchange.app;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Client-side routes of the dashboard: serve the SPA shell so refresh and deep links work. */
@Controller
class SpaController {
    @GetMapping({"/live", "/campaigns", "/reports", "/pricing", "/health", "/simulator"})
    String spa() {
        return "forward:/index.html";
    }
}
