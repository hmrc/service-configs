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
