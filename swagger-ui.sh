#!/usr/bin/env bash

echo "Swagger-ui started at http://localhost:8009"
docker run -p 8009:8080 \
    -e URLS="[
    {url: 'http://localhost:8460/service-configs/swagger/swagger.json', name: 'service-configs'},
    ]" \
    -e VALIDATOR_URL=null \
    -e DISPLAY_REQUEST_DURATION=true \
    --name swagger-ui \
    --rm \
    swaggerapi/swagger-ui
