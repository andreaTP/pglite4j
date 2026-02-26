# Quarkus Pet Clinic — PGLite4j Demo

A minimal Quarkus CRUD app backed by PGLite4j — an embedded PostgreSQL running entirely in the JVM via WebAssembly. No external database needed.

## Run

```bash
mvn quarkus:dev
```

## API

### Create a pet

```bash
curl -X POST http://localhost:8080/pets \
  -H 'Content-Type: application/json' \
  -d '{"name":"Buddy","species":"Dog","age":3}'

curl -X POST http://localhost:8080/pets \
  -H 'Content-Type: application/json' \
  -d '{"name":"Whiskers","species":"Cat","age":5}'

curl -X POST http://localhost:8080/pets \
  -H 'Content-Type: application/json' \
  -d '{"name":"Goldie","species":"Fish","age":1}'
```

### List all pets

```bash
curl http://localhost:8080/pets
```

### Get a pet by ID

```bash
curl http://localhost:8080/pets/1
```

### Update a pet

```bash
curl -X PUT http://localhost:8080/pets/1 \
  -H 'Content-Type: application/json' \
  -d '{"name":"Buddy","species":"Dog","age":4}'
```

### Delete a pet

```bash
curl -X DELETE http://localhost:8080/pets/3
```
