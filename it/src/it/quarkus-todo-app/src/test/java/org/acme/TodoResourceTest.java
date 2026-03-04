package org.acme;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TodoResourceTest {

    @Test
    @Order(1)
    void preExistingDataIsPresent() {
        // The database is restored from seed-data.zip which contains
        // 3 todos from a previous session — no import.sql, no schema
        // generation, the app boots on a non-clean database.
        given().when()
                .get("/todos")
                .then()
                .statusCode(200)
                .body("size()", is(3))
                .body("[0].title", is("Buy groceries"))
                .body("[0].completed", is(true))
                .body("[1].title", is("Walk the dog"))
                .body("[1].completed", is(false))
                .body("[2].title", is("Deploy app"))
                .body("[2].completed", is(false));
    }

    @Test
    @Order(2)
    void addNewTodo() {
        given().contentType(ContentType.JSON)
                .body("{\"title\":\"Fix bug\",\"completed\":false}")
                .when()
                .post("/todos")
                .then()
                .statusCode(201)
                .body("title", is("Fix bug"))
                .body("completed", is(false));
    }

    @Test
    @Order(3)
    void completeTodo() {
        // Mark "Walk the dog" as completed
        int id = given().when().get("/todos").then().extract().path("[1].id");

        given().contentType(ContentType.JSON)
                .body("{\"title\":\"Walk the dog\",\"completed\":true}")
                .when()
                .put("/todos/" + id)
                .then()
                .statusCode(200)
                .body("completed", is(true));
    }

    @Test
    @Order(4)
    void deleteTodo() {
        // Delete "Deploy app"
        int id = given().when().get("/todos").then().extract().path("[2].id");

        given().when().delete("/todos/" + id).then().statusCode(204);
    }

    @Test
    @Order(5)
    void finalState() {
        given().when()
                .get("/todos")
                .then()
                .statusCode(200)
                .body("size()", is(3))
                .body("[0].title", is("Buy groceries"))
                .body("[0].completed", is(true))
                .body("[1].title", is("Walk the dog"))
                .body("[1].completed", is(true))
                .body("[2].title", is("Fix bug"))
                .body("[2].completed", is(false));
    }

    @Test
    @Order(6)
    void getNonExistentReturns404() {
        given().when().get("/todos/999999").then().statusCode(404);
    }
}
