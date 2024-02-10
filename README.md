# G8-api

# Demonstrated in this POC
- [x] Simple Get and Post endpoints
- [x] Authentication and Authorization using a JWT
- [x] Configuration file
- [x] Logging
- [ ] Database Access
- [x] Swagger documentation
- [x] Tests

**Negative**
- Best Devex is with Intellij Ultimate ~600 € / year
- Low support for other IDEs
- Swagger needs additional plugins to auto generate
- No Identity component, needs to reimplement an auth process manually
- Low traction on Reddit
- No way to generate a project from the IDE for the community edition (had use a web tool on Jetbrains portal)
- Needs to manually configure Build & Run Configuration
- After adding a new dependency (`io.ktor:ktor-client-content-negotiation`) I had to go on File->Invalidate caches, and then click the button "Invalidate and restart" the IDE for it to be imported (and recognized by autocompletion). Seriously ???? Is this a Joke ???

**Positive**
- Documentation is nice but not always obvious (ex : Looking for how to use the config file does not explain all the requirements)
- For the Native server, Ktor provides a logger that prints everything to the standard output