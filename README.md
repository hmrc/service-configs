
# service-configs

This service is used to retrieve data to power the [Catalogue](https://github.com/hmrc/catalogue-frontend), including:

 * Bobby Rules
 * Nginx Configs (including shutter switch information)
 * Micro-service config sourced from Github for `app-config-<env>`, `app-config-common` etc repos

## Swagger UI

To get a feel for the endpoints exposted by `service-configs`, you can use
[Swagger UI](https://swagger.io/tools/swagger-ui/).

You an access it at https://catalogue-labs.tax.service.gov.uk/swagger-ui/ :)

### Setting up Github tokens locally (required for viewing bobby rules)

You need to have a file in your home directory at `~/.github/.credentials`.

Where the format is: 

```
api-url: "https://api.github.com"
user:	<yourgithubuser>
token:	<youraccesstoken>
```

> See [here](https://help.github.com/en/articles/creating-a-personal-access-token-for-the-command-line) for how
 to generate a personal access token
 
This is the format used by the hmrc [github-client](https://github.com/hmrc/github-client), which is a dependency.

### Running swagger locally

To load the UI, run `./swagger-ui.sh` after you have `service-configs` running.

> You need to have docker set up for this. You can populate the version used in the UI if you run with
`sbt 'run -DAPP_VERSION=<version>'. This gets injected automatically on k8s`

Then just open the UI which will be running here http://localhost:8009! 

> The `swagger.json` is served from `service-configs` at `/swagger/swagger.json`.

### License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html").
