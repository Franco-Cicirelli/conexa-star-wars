package com.conexa.starwars.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ApiInfoController {

    @GetMapping
    public ApiInfoResponse info() {
        return new ApiInfoResponse("conexa-star-wars", "API ready to use");
    }

    public record ApiInfoResponse(String application, String message) {}
}
