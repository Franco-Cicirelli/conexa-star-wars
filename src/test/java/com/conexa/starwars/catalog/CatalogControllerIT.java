package com.conexa.starwars.catalog;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.conexa.starwars.support.CatalogIntegrationSupport;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CatalogControllerIT extends CatalogIntegrationSupport {
    @Test
    void mapsPageAndSizePreservingFixtureOrderAndTotalsWithOneCall() throws Exception {
        swapi.stubFor(
                get(urlPathEqualTo("/api/people"))
                        .withQueryParam("page", equalTo("2"))
                        .withQueryParam("limit", equalTo("2"))
                        .willReturn(okJson(fixture())));

        var response = request("?page=2&size=2");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(response.body(), "$.items[*].id"))
                .containsExactly("3", "4");
        assertThat(JsonPath.<List<String>>read(response.body(), "$.items[*].name"))
                .containsExactly("R2-D2", "Darth Vader");
        assertThat(JsonPath.<Integer>read(response.body(), "$.page")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(response.body(), "$.size")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(response.body(), "$.totalItems")).isEqualTo(82);
        assertThat(JsonPath.<Integer>read(response.body(), "$.totalPages")).isEqualTo(41);
        swapi.verify(1, getRequestedFor(urlEqualTo("/api/people?page=2&limit=2")));
        assertThat(swapi.getAllServeEvents()).hasSize(1);
    }

    @Test
    void defaultsToFirstPageOfTen() throws Exception {
        swapi.stubFor(get(urlEqualTo("/api/people?page=1&limit=10")).willReturn(okJson(fixture())));
        var response = request("");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(response.body(), "$.page")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(response.body(), "$.size")).isEqualTo(10);
        assertThat(swapi.getAllServeEvents()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "?page=0",
                "?page=-1",
                "?size=0",
                "?size=11",
                "?page=abc",
                "?size=2147483648"
            })
    void rejectsInvalidRequestBeforeCallingSwapi(String query) throws Exception {
        var response = request(query);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(response.body(), "$.code"))
                .isEqualTo("CATALOG_INVALID_REQUEST");
        assertThat(swapi.getAllServeEvents()).isEmpty();
    }

    @Test
    void invalidEnvelopeIsBadGateway() throws Exception {
        swapi.stubFor(
                get(urlPathEqualTo("/api/people")).willReturn(okJson("{\"message\":\"ok\"}")));
        assertFailure(502, "CATALOG_INVALID_RESPONSE");
    }

    @Test
    void unavailableProviderRemainsAnError() throws Exception {
        swapi.stubFor(
                get(urlPathEqualTo("/api/people"))
                        .willReturn(aResponse().withStatus(503).withBody("unavailable")));
        assertFailure(503, "CATALOG_UNAVAILABLE");
    }

    @Test
    void readTimeoutIsGatewayTimeout() throws Exception {
        swapi.stubFor(
                get(urlPathEqualTo("/api/people"))
                        .willReturn(okJson(fixture()).withFixedDelay(3500)));
        assertFailure(504, "CATALOG_TIMEOUT");
    }

    @ParameterizedTest
    @CsvSource({"people,skywalker,11,3", "starships,wing,12,5", "vehicles,speeder,14,8"})
    void searchesCompleteSetAndPaginatesLocally(String resource, String term, String id, int total)
            throws Exception {
        swapi.stubFor(
                get(urlPathEqualTo("/api/" + resource + "/"))
                        .withQueryParam("name", equalTo(term))
                        .willReturn(okJson(fixture(resource + "-name"))));
        var response = request(resource, "?name=" + term + "&page=2&size=1");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(response.body(), "$.items[*].id"))
                .containsExactly(id);
        assertThat(JsonPath.<Integer>read(response.body(), "$.totalItems")).isEqualTo(total);
        assertThat(JsonPath.<Integer>read(response.body(), "$.totalPages")).isEqualTo(total);
        assertThat(JsonPath.<Integer>read(response.body(), "$.page")).isEqualTo(2);
        swapi.verify(1, getRequestedFor(urlEqualTo("/api/" + resource + "/?name=" + term)));
        assertThat(swapi.getAllServeEvents()).hasSize(1);
    }

    @ParameterizedTest
    @CsvSource({
        "people,1,Luke Skywalker",
        "films,1,A New Hope",
        "starships,9,Death Star",
        "vehicles,4,Sand Crawler"
    })
    void combinesIdAndNameWithAndUsingOneDetailCall(String resource, String id, String name)
            throws Exception {
        swapi.stubFor(
                get(urlEqualTo("/api/" + resource + "/" + id))
                        .willReturn(okJson(fixture(resource + "-detail"))));
        var response =
                request(resource, "?id=" + id + "&name=" + encode(name.toUpperCase()) + "&size=1");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(response.body(), "$.items[*].name"))
                .containsExactly(name);
        assertThat(JsonPath.<Integer>read(response.body(), "$.totalItems")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(response.body(), "$.totalPages")).isEqualTo(1);
        assertThat(swapi.getAllServeEvents()).hasSize(1);

        swapi.resetRequests();
        var mismatch = request(resource, "?id=" + id + "&name=empire");
        assertEmpty(mismatch, 0, 0);
        assertThat(swapi.getAllServeEvents()).hasSize(1);
    }

    @ParameterizedTest
    @CsvSource({"people,1", "films,1", "starships,9", "vehicles,4"})
    void detailPaginatesLocallyWithoutOffsetOverflow(String resource, String id) throws Exception {
        swapi.stubFor(
                get(urlEqualTo("/api/" + resource + "/" + id))
                        .willReturn(okJson(fixture(resource + "-detail"))));
        assertEmpty(request(resource, "?id=" + id + "&page=2147483647&size=10"), 1, 1);
        assertThat(swapi.getAllServeEvents()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"people", "films", "starships", "vehicles"})
    void confirmedDetailAbsenceIsAnEmptyPage(String resource) throws Exception {
        swapi.stubFor(
                get(urlEqualTo("/api/" + resource + "/999999999"))
                        .willReturn(
                                aResponse()
                                        .withStatus(404)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(fixture(resource + "-uid-missing"))));
        assertEmpty(request(resource, "?id=999999999"), 0, 0);
        assertThat(swapi.getAllServeEvents()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"people", "films", "starships", "vehicles"})
    void unknown404IsNotAnAbsence(String resource) throws Exception {
        swapi.stubFor(
                get(urlEqualTo("/api/" + resource + "/1"))
                        .willReturn(
                                aResponse()
                                        .withStatus(404)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{\"message\":\"route missing\"}")));
        assertThat(request(resource, "?id=1").statusCode()).isEqualTo(502);
    }

    @ParameterizedTest
    @ValueSource(strings = {"people", "starships", "vehicles"})
    void nameAbsenceAndLocalOutOfRangeAreEmpty(String resource) throws Exception {
        swapi.stubFor(
                get(urlPathEqualTo("/api/" + resource + "/"))
                        .willReturn(okJson(fixture(resource + "-name-missing"))));
        assertEmpty(request(resource, "?name=zzzz-no-such-name-987654321"), 0, 0);
        swapi.stubFor(
                get(urlPathEqualTo("/api/" + resource + "/"))
                        .willReturn(okJson(fixture(resource + "-name"))));
        int total = JsonPath.<List<?>>read(fixture(resource + "-name"), "$.result").size();
        assertEmpty(
                request(
                        resource,
                        "?name="
                                + (resource.equals("people")
                                        ? "skywalker"
                                        : resource.equals("starships") ? "wing" : "speeder")
                                + "&page=2147483647&size=1"),
                total,
                total);
    }

    @Test
    void filmsSortNumericallyFilterAndComputeTotalsBeforePaging() throws Exception {
        var document = JsonPath.parse(fixture("films-page-2"));
        var films = new ArrayList<>(document.<List<Object>>read("$.result"));
        Collections.reverse(films);
        swapi.stubFor(
                get(urlEqualTo("/api/films"))
                        .willReturn(okJson(document.set("$.result", films).jsonString())));
        var response = request("films", "?page=2&size=2");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(response.body(), "$.items[*].id"))
                .containsExactly("3", "4");
        assertThat(JsonPath.<Integer>read(response.body(), "$.totalItems")).isEqualTo(6);
        assertThat(JsonPath.<Integer>read(response.body(), "$.totalPages")).isEqualTo(3);
        swapi.verify(1, getRequestedFor(urlEqualTo("/api/films")));
        swapi.resetRequests();
        response = request("films", "?name=EMPIRE&size=1");
        assertThat(JsonPath.<List<String>>read(response.body(), "$.items[*].name"))
                .containsExactly("The Empire Strikes Back");
        assertThat(JsonPath.<Integer>read(response.body(), "$.totalItems")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(response.body(), "$.totalPages")).isEqualTo(1);
        assertEmpty(request("films", "?name=unknown"), 0, 0);
        assertEmpty(request("films", "?page=2147483647&size=10"), 6, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"people", "starships", "vehicles"})
    void unfilteredListsPreserveProviderOrderAndTotals(String resource) throws Exception {
        swapi.stubFor(
                get(urlEqualTo("/api/" + resource + "?page=2&limit=2"))
                        .willReturn(okJson(fixture(resource + "-page-2"))));
        var response = request(resource, "?page=2&size=2");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(response.body(), "$.items[*].id"))
                .containsExactlyElementsOf(
                        JsonPath.read(fixture(resource + "-page-2"), "$.results[*].uid"));
        assertThat(JsonPath.<Integer>read(response.body(), "$.totalItems"))
                .isEqualTo(JsonPath.read(fixture(resource + "-page-2"), "$.total_records"));
        assertThat(JsonPath.<Integer>read(response.body(), "$.totalPages"))
                .isEqualTo(JsonPath.read(fixture(resource + "-page-2"), "$.total_pages"));
        assertThat(swapi.getAllServeEvents()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"people", "films", "starships", "vehicles"})
    void validatesAllResourcesBeforeCallingProvider(String resource) throws Exception {
        for (String query :
                List.of(
                        "?id=",
                        "?id=0",
                        "?id=-1",
                        "?id=abc",
                        "?id=1.2",
                        "?name=",
                        "?name=%20%20",
                        "?name=" + "a".repeat(81),
                        "?page=0",
                        "?size=11")) {
            var response = request(resource, query);
            assertThat(response.statusCode()).as(resource + query).isEqualTo(400);
            assertThat(JsonPath.<String>read(response.body(), "$.code"))
                    .isEqualTo("CATALOG_INVALID_REQUEST");
        }
        assertThat(swapi.getAllServeEvents()).isEmpty();
    }

    @Test
    void trimsNameAndEncodesItAsOneQueryValue() throws Exception {
        String name = "Luke & #%Skywalker";
        swapi.stubFor(
                get(urlPathEqualTo("/api/people/"))
                        .withQueryParam("name", equalTo(name))
                        .willReturn(okJson("{\"message\":\"ok\",\"result\":[]}")));
        assertEmpty(request("people", "?name=" + encode("  " + name + "  ")), 0, 0);
        assertThat(swapi.getAllServeEvents()).hasSize(1);
    }

    @Test
    void trimsNameBeforeCheckingLengthAndFiltersCaseInsensitively() throws Exception {
        swapi.stubFor(
                get(urlPathEqualTo("/api/people/"))
                        .withQueryParam("name", equalTo("SKYWALKER"))
                        .willReturn(okJson(fixture("people-name"))));
        var response =
                request("people", "?name=" + encode(" ".repeat(80) + "SKYWALKER" + " ".repeat(80)));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(response.body(), "$.totalItems")).isEqualTo(3);
    }

    @Test
    void rejectsPartialSearchMetadataInsteadOfReportingPartialTotals() throws Exception {
        swapi.stubFor(
                get(urlPathEqualTo("/api/people/"))
                        .willReturn(
                                okJson("{\"message\":\"ok\",\"result\":[],\"total_pages\":2}")));
        assertThat(request("people", "?name=luke").statusCode()).isEqualTo(502);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"/people?id=1", "/films", "/starships?name=wing", "/vehicles?name=speeder"})
    void quotaErrorsAreNotEmptyResults(String path) throws Exception {
        swapi.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(429)));
        var response = requestPath(path);
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(JsonPath.<String>read(response.body(), "$.code"))
                .isEqualTo("CATALOG_UNAVAILABLE");
        assertThat(swapi.getAllServeEvents()).hasSize(1);
    }

    @Test
    void ambiguousNamesReadAllPagesAndMatchLiterallyBeforeCounting() throws Exception {
        String first = fixture("people-size-10").replace("Luke Skywalker", "Pilot [A]");
        String second =
                "{\"message\":\"ok\",\"total_records\":11,\"total_pages\":2,"
                        + "\"results\":[{\"uid\":\"11\",\"name\":\"Pilot [B]\"}]}";
        var firstPage =
                JsonPath.parse(first)
                        .set("$.total_records", 11)
                        .set("$.total_pages", 2)
                        .jsonString();
        swapi.stubFor(get(urlEqualTo("/api/people?page=1&limit=10")).willReturn(okJson(firstPage)));
        swapi.stubFor(get(urlEqualTo("/api/people?page=2&limit=10")).willReturn(okJson(second)));
        var response = request("people", "?name=" + encode("Pilot [") + "&page=2&size=1");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(response.body(), "$.items[*].name"))
                .containsExactly("Pilot [B]");
        assertThat(JsonPath.<Integer>read(response.body(), "$.totalItems")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(response.body(), "$.totalPages")).isEqualTo(2);
        assertThat(swapi.getAllServeEvents()).hasSize(2);
        swapi.verify(0, getRequestedFor(urlPathEqualTo("/api/people/")));
    }

    @Test
    void incompleteTraversalDoesNotReturnPartialMatches() throws Exception {
        var firstPage =
                JsonPath.parse(fixture("people-size-10"))
                        .set("$.total_records", 11)
                        .set("$.total_pages", 2)
                        .jsonString();
        swapi.stubFor(get(urlEqualTo("/api/people?page=1&limit=10")).willReturn(okJson(firstPage)));
        swapi.stubFor(
                get(urlEqualTo("/api/people?page=2&limit=10"))
                        .willReturn(aResponse().withStatus(503)));
        var response = request("people", "?name=" + encode("["));
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(swapi.getAllServeEvents()).hasSize(2);
    }

    @Test
    void traversalHasAPageLimit() throws Exception {
        var firstPage =
                JsonPath.parse(fixture("people-size-10"))
                        .set("$.total_records", 210)
                        .set("$.total_pages", 21)
                        .jsonString();
        swapi.stubFor(get(urlEqualTo("/api/people?page=1&limit=10")).willReturn(okJson(firstPage)));
        assertThat(request("people", "?name=" + encode("[")).statusCode()).isEqualTo(502);
        assertThat(swapi.getAllServeEvents()).hasSize(1);
    }

    @Test
    void acceptsMaximumNameLength() throws Exception {
        swapi.stubFor(
                get(urlPathEqualTo("/api/people/"))
                        .withQueryParam("name", equalTo("a".repeat(80)))
                        .willReturn(okJson("{\"message\":\"ok\",\"result\":[]}")));
        assertEmpty(request("people", "?name=" + "a".repeat(80)), 0, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/people?id=1", "/films", "/starships?name=wing"})
    void incompatibleEnvelopesAreErrorsForEveryOperation(String path) throws Exception {
        swapi.stubFor(get(anyUrl()).willReturn(okJson("{\"message\":\"ok\",\"result\":[{}]}")));
        assertThat(requestPath(path).statusCode()).isEqualTo(502);
    }

    private void assertEmpty(HttpResponse<String> response, int total, int pages) {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<?>>read(response.body(), "$.items")).isEmpty();
        assertThat(JsonPath.<Integer>read(response.body(), "$.totalItems")).isEqualTo(total);
        assertThat(JsonPath.<Integer>read(response.body(), "$.totalPages")).isEqualTo(pages);
    }

    private void assertFailure(int status, String code) throws Exception {
        var response = request("");
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo(code);
        assertThat(swapi.getAllServeEvents()).hasSize(1);
    }
}
