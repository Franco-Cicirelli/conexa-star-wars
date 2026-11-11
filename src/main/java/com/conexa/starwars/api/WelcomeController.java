package com.conexa.starwars.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class WelcomeController {

    @GetMapping
    public WelcomeResponse welcome() {
        return new WelcomeResponse("conexa-star-wars", "API ready to use");
    }

    public record WelcomeResponse(String application, String message) {}
}
