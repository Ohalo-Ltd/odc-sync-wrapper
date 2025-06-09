# AGENTS

This repository contains a Java 17 command line application built with Maven. The project
has a small test suite under `src/test/java` using JUnit 5. Use the following
rules when contributing:

* Use four spaces for indentation in Java source files.
* Run `mvn test` before every commit to ensure tests pass. Don't use the `-q` option.
* Add new unit tests for any new functionality.
* The application expects `DXR_BASE_URL` and `DXR_API_KEY` environment variables
  when run, but should also be used in integration tests.
* Keep sample data in `samples/sample.txt` so tests continue to work.
