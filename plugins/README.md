# Plugins

JavaScript plugins for the kinframe frame (docs/plugins.md). Each folder is one plugin:

```
<id>/
  plugin.json      the manifest (§3.1)
  main.js          one ES module with a default export (§3.2)
  locales/*.json   translations: the plugin's own strings, and "@name" for its name
  fixtures/        recorded data for the tests (never secrets)
```

The `kinframe.*` folders here are built into the frame app (copied into its assets at build time; fixtures stay out).
`examples/` holds community-style plugins that are tested but not shipped; they were written to find out what the view
format still lacks (docs/plugins.md §11.1).

Tests run every plugin in the real QuickJS sandbox on the JVM: `android/frame/src/test/java/app/kinframe/frame/plugins/js/`.
