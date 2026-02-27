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

@Path("/pets")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PetResource {

    @GET
    public List<Pet> list() {
        return Pet.listAll(Sort.ascending("id"));
    }

    @GET
    @Path("/{id}")
    public Pet get(@PathParam("id") Long id) {
        Pet pet = Pet.findById(id);
        if (pet == null) {
            throw new WebApplicationException(404);
        }
        return pet;
    }

    @POST
    @Transactional
    public Response create(Pet pet) {
        pet.persist();
        return Response.status(Response.Status.CREATED).entity(pet).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public Pet update(@PathParam("id") Long id, Pet pet) {
        Pet existing = Pet.findById(id);
        if (existing == null) {
            throw new WebApplicationException(404);
        }
        existing.name = pet.name;
        existing.species = pet.species;
        existing.age = pet.age;
        return existing;
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") Long id) {
        boolean deleted = Pet.deleteById(id);
        if (!deleted) {
            throw new WebApplicationException(404);
        }
        return Response.noContent().build();
    }
}
