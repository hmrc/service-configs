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
                configChanged: false, // Default to false, will be updated later
                configId: configId,
                lastUpdated: event.timestamp
            };

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

### Update configChanged field for migrated deployment events

```javascript
function updateConfigChanged() {
    const targetDb = db.getSiblingDB('service-configs');
    const batchSize = 1000;

    const collections = targetDb.deploymentEvents.distinct("serviceName");

    targetDb.deploymentEvents.createIndex({ serviceName: 1 });
    targetDb.deploymentEvents.createIndex({ environment: 1 });
    targetDb.deploymentEvents.createIndex({ lastUpdated: 1 });

    collections.forEach(serviceName => {
        let environments = targetDb.deploymentEvents.distinct("environment", { serviceName: serviceName });

        environments.forEach(environment => {
            let query = { serviceName: serviceName, environment: environment };
            let cursor = targetDb.deploymentEvents.find(query, { _id: 0, serviceName: 1, environment: 1, lastUpdated: 1, configId: 1 }).sort({ lastUpdated: 1 });

            let bulkUpdate = targetDb.deploymentEvents.initializeUnorderedBulkOp();
            let count = 0;

            let previousConfigId = null;

            while (cursor.hasNext()) {
                let event = cursor.next();
                const configIdWithoutVersion = event.configId.split("_").slice(2).join("_");

                let configChanged = false;

                if (previousConfigId && previousConfigId !== configIdWithoutVersion) {
                    configChanged = true;
                }

                bulkUpdate.find({ serviceName: event.serviceName, environment: event.environment, lastUpdated: event.lastUpdated, configId: event.configId }).updateOne({ $set: { configChanged: configChanged } });

                count++;

                if (count === batchSize || !cursor.hasNext()) {
                    bulkUpdate.execute();
                    bulkUpdate = targetDb.deploymentEvents.initializeUnorderedBulkOp();
                    count = 0;
                }

                previousConfigId = configIdWithoutVersion;
            }

            print(`Processed batches for serviceName: ${serviceName} and environment: ${environment}`);
        });
    });

    print("ConfigChanged update completed!");
}
updateConfigChanged();
```