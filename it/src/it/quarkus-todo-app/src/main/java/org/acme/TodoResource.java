package org.acme;

import io.quarkus.panache.common.Sort;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

@Path("/todos")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TodoResource {

    @GET
    public List<Todo> list() {
        return Todo.listAll(Sort.ascending("id"));
    }

    @GET
    @Path("/{id}")
    public Todo get(@PathParam("id") Long id) {
        Todo todo = Todo.findById(id);
        if (todo == null) {
            throw new WebApplicationException(404);
        }
        return todo;
    }

    @POST
    @Transactional
    public Response create(Todo todo) {
        todo.persist();
        return Response.status(Response.Status.CREATED).entity(todo).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public Todo update(@PathParam("id") Long id, Todo todo) {
        Todo existing = Todo.findById(id);
        if (existing == null) {
            throw new WebApplicationException(404);
        }
        existing.title = todo.title;
        existing.completed = todo.completed;
        return existing;
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") Long id) {
        boolean deleted = Todo.deleteById(id);
        if (!deleted) {
            throw new WebApplicationException(404);
        }
        return Response.noContent().build();
    }
}
