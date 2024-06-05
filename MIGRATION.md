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
