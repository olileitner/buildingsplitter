# Release checklist

Use this checklist for a standard release (`vX.Y.Z`).

- [ ] Verify target version in `build.gradle` (`version` and `Plugin-Version`).
- [ ] Run local tests: `./gradlew test`.
- [ ] Build local artifact: `./gradlew jar`.
- [ ] Run a JOSM smoke test with the built plugin (manual functional check).
- [ ] Commit release changes.
- [ ] Push commit to `main`.
- [ ] Create release tag: `git tag vX.Y.Z`.
- [ ] Push tag: `git push origin vX.Y.Z`.
- [ ] Check GitHub Actions release workflow run for the tag.
- [ ] Verify GitHub Release page:
  - [ ] Release name is correct.
  - [ ] JAR asset is present (`buildingsplitter.jar`).

