# G8-api

# Demonstrated in this POC
- [x] Simple Get and Post endpoints
- [x] Authentication and Authorization using a JWT
- [x] Configuration file
- [x] Logging
- [x] Database Access
- [x] Swagger documentation
- [x] Tests

# How to test this app

All endpoints can be called using Swagger. Swagger was also configured with default value so you can just execute the endpoints with little changes, except for the authentication step :)

1. Configure gradle, on the root directory run :
   1. `chmod +x ./gradlew`
   2. `./gradlew build`
   3. `./gradlew run`
2. Navigate to `http://127.0.0.1:8080/swagger`, this should show the Swagger UI
3. Use this simple test case to call all the endpoints
   1. Get All Farmers, should return an empty list
   2. Create a farmer, this should add a new farmer in the db
   3. Creating a farmer should also print a log, search for the following log in the Run Terminal : `Farmer - Created a new farmer`
   4. Get All Farmers, should now return a list with one item
   5. Get Products for the authenticated farmer, should return an access forbidden error as you are not authenticated
   6. Authenticate and Get JWT by providing the email/password, this should return a token in the response
   7. Copy the token and click on the lock icon on the right side of the header of the `GET
      /api/farmer/{id}/product` endpoint. This should show you a value field, past your token there and click `Authorize`
   8. Now call again the Get products endpoint, it should return a list with a single item

Completing this test case should validate all the features check in the previous chapter.

# Notes

We are using h2 db for now.

**Not experimented yet**
- Dependency injection with [Koin](https://insert-koin.io/docs/reference/koin-ktor/ktor/)
- Database migrations with [Flyway](https://flywaydb.org/)

**Negative**
- Best Devex is with Intellij Ultimate ~600 € / year
- Low support for other IDEs
- Swagger needs additional plugins to auto generate
- No Identity component, needs to reimplement an auth process manually
- Low traction on Reddit
- No way to generate a project from the IDE for the community edition (had use a web tool on Jetbrains portal)
- Needs to manually configure Build & Run Configuration
- After adding a new dependency (`io.ktor:ktor-client-content-negotiation`) I had to go on File->Invalidate caches, and then click the button "Invalidate and restart" the IDE for it to be imported (and recognized by autocompletion). Seriously ???? Is this a Joke ???
- No native database migration tool. A paying [Redgate tools, FlyWay](https://flywaydb.org/) is recommended.
- Documentation is sometimes outdated (ex : For database integration with exposed)

**Positive**
- Documentation is nice but not always obvious (ex : Looking for how to use the config file does not explain all the requirements)
- For the Native server, Ktor provides a logger that prints everything to the standard output