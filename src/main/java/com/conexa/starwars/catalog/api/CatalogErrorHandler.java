package com.conexa.starwars.catalog.api;

import com.conexa.starwars.catalog.application.CatalogFailure;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = CatalogController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CatalogErrorHandler {
    @ExceptionHandler(CatalogFailure.class)
    ProblemDetail externalFailure(CatalogFailure failure) {
        int status =
                switch (failure.reason()) {
                    case INVALID_RESPONSE -> 502;
                    case UNAVAILABLE -> 503;
                    case TIMEOUT -> 504;
                };
        var problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatusCode.valueOf(status), "Unable to query the external catalog.");
        problem.setProperty("code", "CATALOG_" + failure.reason().name());
        return problem;
    }

    @ExceptionHandler({
        HandlerMethodValidationException.class,
        MethodArgumentTypeMismatchException.class
    })
    ProblemDetail invalidRequest(Exception exception) {
        var problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatusCode.valueOf(400),
                        "page must be positive, size must be between 1 and 10, id must be a positive UID, and name must contain between 1 and 80 characters.");
        problem.setProperty("code", "CATALOG_INVALID_REQUEST");
        return problem;
    }
}
