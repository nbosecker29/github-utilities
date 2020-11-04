# github-utilities
Uses the Github API to provide some useful utilities, such as analyzing pull requests.

## To Build
Run `./gradlew packageDistribution`.
This will build a jar for the application and move it and a bash script to execute it to the `dist` directory.

## To Run
Once you've built the app, you can execute it from the `dist/` directory.

**NOTE:** You need to have a Github Access Token configured on your system as an environment variable called `GITHUB_OAUTH` in order for it to connect and
read from the repository (must be public or you must own it/have read permissions if it's private) specified below.

Example - Print Merged Stats
```
github-utilities/dist> ./github-utilities --analyze merged --pr-limit 10 --repo-name <your-repo-name>
```

Example - Print in JSON
```
github-utilities/dist> ./github-utilities --output json --analyze open --pr-limit 5 --repo-name <your-repo-name>
```

## To Build Graal Native Image
You will need to install GraalVM (Java 11 CE edition) and install the `native-image` utility. Follow directions here: https://www.graalvm.org/examples/java-kotlin-aot, then to build
the actual application native-image:
```
./build-graal-image.sh
```

This will produce an executable called `github-utilities-native` that can be executed using similar commands from above.
