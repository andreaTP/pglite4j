package org.acme;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PetControllerTest {

    @LocalServerPort private int port;

    private int buddyId;
    private int whiskersId;
    private int goldieId;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    @Order(1)
    void createBuddy() {
        buddyId =
                given().contentType(ContentType.JSON)
                        .body("{\"name\":\"Buddy\",\"species\":\"Dog\",\"age\":3}")
                        .when()
                        .post("/pets")
                        .then()
                        .statusCode(201)
                        .body("name", is("Buddy"))
                        .body("species", is("Dog"))
                        .body("age", is(3))
                        .extract()
                        .path("id");
    }

    @Test
    @Order(2)
    void createWhiskers() {
        whiskersId =
                given().contentType(ContentType.JSON)
                        .body("{\"name\":\"Whiskers\",\"species\":\"Cat\",\"age\":5}")
                        .when()
                        .post("/pets")
                        .then()
                        .statusCode(201)
                        .body("name", is("Whiskers"))
                        .extract()
                        .path("id");
    }

    @Test
    @Order(3)
    void createGoldie() {
        goldieId =
                given().contentType(ContentType.JSON)
                        .body("{\"name\":\"Goldie\",\"species\":\"Fish\",\"age\":1}")
                        .when()
                        .post("/pets")
                        .then()
                        .statusCode(201)
                        .body("name", is("Goldie"))
                        .extract()
                        .path("id");
    }

    @Test
    @Order(4)
    void listAll() {
        given().when()
                .get("/pets")
                .then()
                .statusCode(200)
                .body("size()", is(3))
                .body("[0].name", is("Buddy"))
                .body("[1].name", is("Whiskers"))
                .body("[2].name", is("Goldie"));
    }

    @Test
    @Order(5)
    void getById() {
        given().when()
                .get("/pets/" + buddyId)
                .then()
                .statusCode(200)
                .body("name", is("Buddy"))
                .body("species", is("Dog"))
                .body("age", is(3));
    }

    @Test
    @Order(6)
    void updateBuddy() {
        given().contentType(ContentType.JSON)
                .body("{\"name\":\"Buddy\",\"species\":\"Dog\",\"age\":4}")
                .when()
                .put("/pets/" + buddyId)
                .then()
                .statusCode(200)
                .body("name", is("Buddy"))
                .body("age", is(4));
    }

    @Test
    @Order(7)
    void verifyUpdate() {
        given().when().get("/pets/" + buddyId).then().statusCode(200).body("age", is(4));
    }

    @Test
    @Order(8)
    void deleteGoldie() {
        given().when().delete("/pets/" + goldieId).then().statusCode(204);
    }

    @Test
    @Order(9)
    void listAfterDelete() {
        given().when()
                .get("/pets")
                .then()
                .statusCode(200)
                .body("size()", is(2))
                .body("[0].name", is("Buddy"))
                .body("[1].name", is("Whiskers"));
    }

    @Test
    @Order(10)
    void getDeletedReturns404() {
        given().when().get("/pets/" + goldieId).then().statusCode(404);
    }
}
