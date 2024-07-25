# Migration to 1.65.0


Ensure all deploymentIds are unique (and align with releases-api)

```javascript
db.getCollection('deployedConfig').find({"deploymentId": {$regex: /^arn/}}).forEach(function(doc) {
  const serviceName = doc.serviceName;
  const environment = doc.environment;
  const timestamp   = doc.lastUpdated;

  const epochMillis = new Date(timestamp).getTime();

  const newDeploymentId = `gen-${serviceName}-${environment}-${epochMillis}`;

  db.getCollection('deployedConfig').updateOne(
    { _id: doc._id },
    { $set: { deploymentId: newDeploymentId } }
  );
});
```

clear the configChanged flag and start populating again

```javascript
db.getCollection('deploymentEvents').updateMany({}, {"$unset": {"configChanged": ""}})
```

# Migration to 1.55.0

```javascript
db.getCollection("bobbyWarningsNotificationsRunDate").renameCollection("deprecationWarningsNotificationRunDate")
```

# Migration to 1.39.0
```javascript
db.getCollection('deploymentConfig').dropIndex("name_1_environment_1")
db.getCollection('deploymentConfig').dropIndex("environment_1")
db.getCollection('deploymentConfig').updateMany({}, {$set: {"applied": true, envVars: {}, jvm: {}}})

db.getCollection('deploymentConfigSnapshots').aggregate([
  {$project:
    {
      _id        : 1,
      date       : 1,
      latest     : 1,
      deleted    : 1,
      serviceName: "$deploymentConfig.name",
      environment: "$deploymentConfig.environment",
      slots      :  { $toInt: "$deploymentConfig.slots" },
      instances  :  { $toInt: "$deploymentConfig.instances" }
    }
  },
  { $out: "resourceUsage" }
], {allowDiskUse:true})

db.getCollection('deploymentConfigSnapshots').drop()
```

# Migration to 0.192.0

```javascript
db.getCollection('deployedConfig').updateMany({}, {$set: {"lastUpdated": new ISODate("2023-07-12T00:00:00Z")}})()
```

# Migration to 0.167.0

```javascript
db.getCollection("appConfig").drop()
db.getCollection("appConfigCommon").drop()
db.getCollection("lastHashString").drop()
```

# Migration to 0.162.0

```javascript
db.getCollection("appConfigBase").drop()
db.getCollection("appConfigCommon").drop()
db.getCollection("appConfigEnv").drop()
db.getCollection("lastHashString").drop()
```

# Migration to 0.157.0

```javascript
db.getCollection("lastHashString").drop()
```

# Migration to 0.100.0

```javascript
db.getCollection('slugConfigurations').aggregate([
  {$project:
    {
      _id: 1,
      uri: 1,
      created: { $toDate: "$created" },
      name: 1,
      version: 1,
      dependencies: 1,
      applicationConfig: 1,
      slugConfig: 1,
      latest: 1,
      development: 1,
      integration: 1,
      qa: 1,
      staging: 1,
      externaltest: 1,
      production: 1
    }
  },
  { $out: "slugConfigurations-new" }
], {allowDiskUse:true})
```

Switch collections over
```javascript
db.getCollection("slugConfigurations").renameCollection("slugConfigurations-bak")
db.getCollection("slugConfigurations-new").renameCollection("slugConfigurations")
```

## Rollback

Switch collections over
```javascript
db.getCollection("slugConfigurations").renameCollection("slugConfigurations-new")
db.getCollection("slugConfigurations-bak").renameCollection("slugConfigurations")
```

## Populate DeploymentEvents collection from historic releases-api data

```javascript
function migrateReleaseEvents() {
    const sourceDb = db.getSiblingDB('releases');
    const targetDb = db.getSiblingDB('service-configs');
    const batchSize = 1000;

    let lastId = null;
    let hasMore = true;

    while (hasMore) {
        let query = lastId
            ? { _id: { $gt: lastId }, eventType: 'deployment-complete' }
            : { eventType: 'deployment-complete' };
        let batch = sourceDb['release-events'].find(query).sort({ _id: 1 }).limit(batchSize).toArray();

        if (batch.length === 0) {
            hasMore = false;
            break;
        }

        let bulk = targetDb.deploymentEvents.initializeUnorderedBulkOp();

        batch.forEach(event => {
            const serviceName = event.serviceName;
            const version = event.version;
            let configId = serviceName + "_" + version;

            if (event.config && Array.isArray(event.config)) {
                configId = event.config.reduce((acc, c) => {
                    return acc + "_" + c.repoName + "_" + c.commitId.substring(0, 7);
                }, configId);
            } else {
                configId = "";
            }

            const newEvent = {
                serviceName: serviceName,
                environment: event.environment,
                version: version,
                deploymentId: event.deploymentId,
                lastUpdated: event.timestamp
            };

            if (configId !== "") {
                newEvent.configId = configId;
            }

            bulk.insert(newEvent);
            lastId = event._id;
        });

        bulk.execute();
        print(`Processed batch with lastId: ${lastId}`);
    }

    print("Data migration completed!");
}
migrateReleaseEvents();
```
