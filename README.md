
# service-configs

This service is used to retrieve data to power the [Catalogue](https://github.com/hmrc/catalogue-frontend), including:

 * Bobby Rules
 * Nginx Configs
 * Micro-service config sourced from Github for `app-config-<env>`, `app-config-common` etc repos

### Setting up Github tokens locally (required for viewing bobby rules)

You need to have a file in your home directory at `~/.github/.credentials`

Where the format is: 

```
api-url: "https://api.github.com"
user:	<yourgithubuser>
token:	<youraccesstoken>
```

> See [here](https://help.github.com/en/articles/creating-a-personal-access-token-for-the-command-line) for how
 to generate a personal access token


### License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html").
