# Profiles and persistence

Profiles are saved locally through the existing `ProfileStore`. Structured profiles are
editable Android models; complete strict tcptun-go JSON profiles are preserved as raw JSON.
The app validates both the Android model and a non-listening core runtime construction before
persisting an imported profile.

## Running profiles

Active structured profiles become tagged native outbounds in one runtime plan. The plan also
contains a dynamic balance pool and a direct outbound. Row actions hot-start or hot-stop one
outbound without rebuilding the Android VPN interface.

Managed route rules retain their stable profile references. A stopped profile remains a valid
rule target and becomes usable again when that profile is started.

## Persistence rules

- Profile IDs and active IDs are persisted by the existing store.
- Mutation revisions protect concurrent UI/service updates.
- Import and delete operations are bounded and recoverable.
- Credentials are not included in diagnostics or ordinary logs.
- Existing migrations, raw JSON fields, and compatibility behavior remain unchanged.

## Validation

Use the existing validators before save:

```text
Android profile validation -> bridge JSON construction -> tcptun-go validation
```

Profile persistence coverage lives in JVM store/codec tests and Android contract/UI tests.
